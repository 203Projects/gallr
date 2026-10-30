-- Spec 101 (US5): an active artist name is unique after normalisation.
--
-- Apply only after every group reported by public.admin_find_duplicate_artists has been merged:
-- this migration aborts and lists the remaining groups otherwise. Every path that creates,
-- renames or restores an artist row then gets artist_duplicate (P0001, detail = the existing
-- artist id) instead of a raw unique violation. Archived artists never block a name.

begin;

create or replace function content_private.assert_no_duplicate_artists()
returns void
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_groups jsonb := content_private.artist_duplicate_groups();
  v_list text;
begin
  if jsonb_array_length(v_groups) = 0 then
    return;
  end if;

  select string_agg(
    format(
      '%s [%s]',
      duplicate.value ->> 'key',
      (
        select string_agg(
          format(
            '%s %s / %s',
            member.value ->> 'id',
            member.value ->> 'name_ko',
            member.value ->> 'name_en'
          ),
          ', '
          order by member.ordinality
        )
        from jsonb_array_elements(duplicate.value -> 'artists') with ordinality
          as member(value, ordinality)
      )
    ),
    '; '
    order by duplicate.ordinality
  )
  into v_list
  from jsonb_array_elements(v_groups) with ordinality as duplicate(value, ordinality);

  raise exception using
    errcode = '23505',
    message = format(
      'artist_duplicates_remain: %s groups: %s',
      jsonb_array_length(v_groups),
      v_list
    ),
    hint = 'For each group: if the rows are the same person, merge them with public.admin_merge_artists; '
      || 'if they are different people who share a name, rename one so the names differ '
      || '(for example add a birth year or the Hanja name) and do not merge. '
      || 'Then apply 20261028100300_artist_name_uniqueness again.';
end;
$function$;

revoke all on function content_private.assert_no_duplicate_artists()
  from public, anon, authenticated, service_role;

do $abort_on_remaining_duplicates$
begin
  perform content_private.assert_no_duplicate_artists();
end;
$abort_on_remaining_duplicates$;

-- The backstop. The expression must stay byte-compatible with normalize_artist_name; changing that
-- function requires reindexing these two indexes in the same migration.
create unique index if not exists artists_active_name_ko_normalized_key
  on content.artists (content_private.normalize_artist_name(name_ko))
  where archived_at is null;
create unique index if not exists artists_active_name_en_normalized_key
  on content.artists (content_private.normalize_artist_name(name_en))
  where archived_at is null;

-- Turns a duplicate into artist_duplicate with the existing id on every write path (Admin RPC,
-- backfill scripts, service-role writes, renames, restores). Writers of the same normalised names
-- serialise on a transaction lock, so the check sees a concurrent insert once it commits.
create or replace function content_private.guard_artist_duplicate_name()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_key_ko text := content_private.normalize_artist_name(new.name_ko);
  v_key_en text := content_private.normalize_artist_name(new.name_en);
  v_existing_id uuid;
begin
  if new.archived_at is not null then
    return new;
  end if;

  if v_key_ko is not null then
    perform pg_catalog.pg_advisory_xact_lock(73250, pg_catalog.hashtext('ko:' || v_key_ko));
  end if;
  if v_key_en is not null then
    perform pg_catalog.pg_advisory_xact_lock(73250, pg_catalog.hashtext('en:' || v_key_en));
  end if;

  select artist.id
  into v_existing_id
  from content.artists as artist
  where artist.archived_at is null
    and artist.id <> new.id
    and (
      content_private.normalize_artist_name(artist.name_ko) = v_key_ko
      or content_private.normalize_artist_name(artist.name_en) = v_key_en
    )
  order by artist.created_at, artist.id
  limit 1;

  if found then
    raise exception using
      errcode = 'P0001',
      message = 'artist_duplicate',
      detail = v_existing_id::text;
  end if;

  return new;
end;
$function$;

revoke all on function content_private.guard_artist_duplicate_name()
  from public, anon, authenticated, service_role;

drop trigger if exists artists_guard_duplicate_name on content.artists;
create trigger artists_guard_duplicate_name
  before insert or update of name_ko, name_en, archived_at on content.artists
  for each row
  execute function content_private.guard_artist_duplicate_name();

-- Admin creation keeps its idempotent request contract. A unique violation can still reach the
-- insert when a caller runs above READ COMMITTED; it is reported as artist_duplicate too.
create or replace function content_private.admin_create_artist_impl(
  p_name_ko text,
  p_name_en text,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_id uuid := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_name_ko text := btrim(coalesce(p_name_ko, ''));
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_artist_id uuid;
  v_existing_id uuid;
  v_response jsonb;
begin
  if length(v_name_ko) not between 1 and 200
     or length(v_name_en) not between 1 and 200 then
    raise exception using
      errcode = '22023',
      message = 'artist_names_invalid';
  end if;

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('name_ko', v_name_ko, 'name_en', v_name_en)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_actor_id,
    p_request_id,
    'admin_create_artist',
    v_fingerprint
  ) as request;
  if v_is_replay then
    return v_stored;
  end if;

  begin
    insert into content.artists (
      name_ko,
      name_en,
      created_by,
      updated_by
    ) values (
      v_name_ko,
      v_name_en,
      v_actor_id,
      v_actor_id
    ) returning id into v_artist_id;
  exception
    when unique_violation then
      select artist.id
      into v_existing_id
      from content.artists as artist
      where artist.archived_at is null
        and (
          content_private.normalize_artist_name(artist.name_ko)
            = content_private.normalize_artist_name(v_name_ko)
          or content_private.normalize_artist_name(artist.name_en)
            = content_private.normalize_artist_name(v_name_en)
        )
      order by artist.created_at, artist.id
      limit 1;

      raise exception using
        errcode = 'P0001',
        message = 'artist_duplicate',
        detail = coalesce(v_existing_id::text, '');
  end;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    request_id,
    metadata
  ) values (
    v_actor_id,
    'artist.created',
    'artist',
    v_artist_id::text,
    p_request_id,
    '{}'::jsonb
  );

  v_response := jsonb_build_object(
    'id', v_artist_id,
    'name_ko', v_name_ko,
    'name_en', v_name_en
  );
  return content_private.complete_command_request(
    v_actor_id,
    p_request_id,
    'admin_create_artist',
    v_fingerprint,
    v_response
  );
end;
$function$;

revoke all on function content_private.admin_create_artist_impl(text, text, uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_create_artist_impl(text, text, uuid)
  to authenticated;

commit;
