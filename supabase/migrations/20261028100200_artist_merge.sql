-- Spec 101 (US5): find and merge duplicate artist identities.
--
-- Two active artists are duplicates when their Korean or English names match after folding case,
-- width, spaces and punctuation. An admin merges one into the other: every draft and published
-- credit moves to the survivor and keeps the label readers already saw, a credit that would repeat
-- the survivor on one version is removed, and the merged artist is archived. Open drafts that
-- change move to a new revision. Two different people who share a name are not duplicates: rename
-- one of them (for example with a birth year or the Hanja name) instead of merging.

begin;

create or replace function content_private.normalize_artist_name(p_name text)
returns text
language sql
immutable
parallel safe
security invoker
set search_path = ''
as $function$
  select nullif(
    regexp_replace(
      lower(normalize(coalesce(p_name, ''), NFKC)),
      '[[:space:][:punct:]·・‧•‐‑‒–—―‘’“”「」『』〈〉《》【】、。]+',
      '',
      'g'
    ),
    ''
  );
$function$;

comment on function content_private.normalize_artist_name(text) is
  'Folds an artist name for duplicate detection: NFKC (width), lower case, no spaces or punctuation. Null when nothing remains.';

revoke all on function content_private.normalize_artist_name(text)
  from public, anon, authenticated;
grant execute on function content_private.normalize_artist_name(text)
  to service_role;

-- Groups of active artists sharing a normalised Korean or English name. A group whose members
-- equal another group's members is reported once, under its Korean key.
create or replace function content_private.artist_duplicate_groups()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  with active as (
    select
      artist.id,
      artist.name_ko,
      artist.name_en,
      artist.created_at,
      content_private.normalize_artist_name(artist.name_ko) as key_ko,
      content_private.normalize_artist_name(artist.name_en) as key_en,
      (
        select count(distinct version.exhibition_id)
        from content.exhibition_version_artists as credit
        join content.exhibition_versions as version on version.id = credit.version_id
        where credit.artist_id = artist.id
      )::integer as credit_count
    from content.artists as artist
    where artist.archived_at is null
  ),
  keyed as (
    select 'ko:' || active.key_ko as key, 0 as rank, active.id
    from active
    where active.key_ko is not null
    union all
    select 'en:' || active.key_en as key, 1 as rank, active.id
    from active
    where active.key_en is not null
  ),
  grouped as (
    select keyed.key, min(keyed.rank) as rank, array_agg(keyed.id order by keyed.id) as members
    from keyed
    group by keyed.key
    having count(*) > 1
  ),
  distinct_groups as (
    select distinct on (grouped.members) grouped.key, grouped.members
    from grouped
    order by grouped.members, grouped.rank, grouped.key
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'key', duplicate.key,
        'artists', (
          select jsonb_agg(
            jsonb_build_object(
              'id', member.id,
              'name_ko', member.name_ko,
              'name_en', member.name_en,
              'credit_count', member.credit_count,
              'archived', false
            )
            order by member.credit_count desc, member.created_at, member.id
          )
          from active as member
          where member.id = any(duplicate.members)
        )
      )
      order by duplicate.key
    ),
    '[]'::jsonb
  )
  from distinct_groups as duplicate;
$function$;

revoke all on function content_private.artist_duplicate_groups()
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_find_duplicate_artists_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.admin_assert_staff('admin'::content.staff_role);
  return content_private.artist_duplicate_groups();
end;
$function$;

create or replace function content_private.admin_merge_artists_impl(
  p_survivor_id uuid,
  p_merged_id uuid,
  p_reason text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_id uuid := content_private.admin_assert_staff(
    'admin'::content.staff_role
  );
  v_reason text := btrim(coalesce(p_reason, ''));
  v_survivor content.artists%rowtype;
  v_merged content.artists%rowtype;
  v_removed jsonb;
  v_moved jsonb;
  v_changed_versions uuid[];
  v_bumped_drafts jsonb;
  v_exhibition_id text;
  v_response jsonb;
begin
  if p_survivor_id = p_merged_id then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'same_artist';
  end if;
  if length(v_reason) not between 1 and 400 then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'reason_invalid';
  end if;

  -- Lock order matches a draft save (exhibition, version, then artist FOR SHARE in
  -- apply_exhibition_art_metadata): first every exhibition whose open draft credits the merged
  -- artist, then both identities in a stable order, so a racing save waits instead of deadlocking.
  perform 1
  from content.exhibitions as exhibition
  where exists (
    select 1
    from content.exhibition_versions as version
    join content.exhibition_version_artists as credit on credit.version_id = version.id
    where version.exhibition_id = exhibition.id
      and version.status = 'draft'::content.exhibition_version_status
      and credit.artist_id = p_merged_id
  )
  order by exhibition.id
  for update;

  perform 1
  from content.artists as artist
  where artist.id in (p_survivor_id, p_merged_id)
  order by artist.id
  for update;

  select artist.* into v_survivor from content.artists as artist where artist.id = p_survivor_id;
  if not found then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'survivor_not_found';
  end if;
  select artist.* into v_merged from content.artists as artist where artist.id = p_merged_id;
  if not found then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'merged_not_found';
  end if;
  if v_survivor.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'survivor_archived';
  end if;
  if v_merged.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'artist_merge_invalid',
      detail = 'merged_archived';
  end if;

  -- A version that already credits the survivor keeps that credit; the merged one is removed.
  -- Each removed and moved row is kept in the audit row so the merge can be reversed by hand.
  with removed as (
    delete from content.exhibition_version_artists as credit
    where credit.artist_id = p_merged_id
      and exists (
        select 1
        from content.exhibition_version_artists as other
        where other.version_id = credit.version_id
          and other.artist_id = p_survivor_id
      )
    returning credit.*
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'version_id', removed.version_id,
        'sort_order', removed.sort_order,
        'name_ko', removed.name_ko,
        'name_en', removed.name_en,
        'grounding', removed.grounding,
        'created_at', removed.created_at,
        'created_by', removed.created_by
      )
      order by removed.version_id, removed.sort_order
    ),
    '[]'::jsonb
  )
  into v_removed
  from removed;

  -- Every other credit is re-pointed; its name_ko and name_en snapshot stays as published.
  with moved as (
    update content.exhibition_version_artists as credit
    set artist_id = p_survivor_id
    where credit.artist_id = p_merged_id
    returning credit.*
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'version_id', moved.version_id,
        'sort_order', moved.sort_order,
        'name_ko', moved.name_ko,
        'name_en', moved.name_en,
        'grounding', moved.grounding
      )
      order by moved.version_id, moved.sort_order
    ),
    '[]'::jsonb
  )
  into v_moved
  from moved;

  select coalesce(array_agg(distinct (changed.value ->> 'version_id')::uuid), '{}'::uuid[])
  into v_changed_versions
  from jsonb_array_elements(v_removed || v_moved) as changed(value);

  -- An editor holding the merged id in an open draft gets a revision conflict and reloads,
  -- instead of art_metadata_artist_unknown on the next save.
  with bumped as (
    update content.exhibition_versions as version
    set revision = version.revision + 1,
        updated_by = v_actor_id
    where version.id = any(v_changed_versions)
      and version.status = 'draft'::content.exhibition_version_status
    returning version.id, version.exhibition_id, version.revision
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'version_id', bumped.id,
        'exhibition_id', bumped.exhibition_id,
        'revision', bumped.revision
      )
      order by bumped.exhibition_id
    ),
    '[]'::jsonb
  )
  into v_bumped_drafts
  from bumped;

  update content.artists as artist
  set archived_at = now(),
      updated_by = v_actor_id
  where artist.id = p_merged_id;

  for v_exhibition_id in
    select distinct exhibition.id
    from content.exhibitions as exhibition
    where exhibition.published_version_id = any(v_changed_versions)
    order by exhibition.id
  loop
    perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  end loop;

  v_response := jsonb_build_object(
    'survivor_id', p_survivor_id,
    'merged_id', p_merged_id,
    'moved_credits', jsonb_array_length(v_moved),
    'removed_duplicate_credits', jsonb_array_length(v_removed)
  );

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
    v_actor_id,
    'artist.merged',
    'artist',
    p_merged_id::text,
    v_response || jsonb_build_object(
      'reason', v_reason,
      'survivor_labels', jsonb_build_object('name_ko', v_survivor.name_ko, 'name_en', v_survivor.name_en),
      'merged_labels', jsonb_build_object('name_ko', v_merged.name_ko, 'name_en', v_merged.name_en),
      'moved', v_moved,
      'removed', v_removed,
      'bumped_drafts', v_bumped_drafts
    )
  );

  return v_response;
end;
$function$;

revoke all on function
  content_private.admin_find_duplicate_artists_impl(),
  content_private.admin_merge_artists_impl(uuid, uuid, text)
from public, anon, authenticated, service_role;
grant execute on function
  content_private.admin_find_duplicate_artists_impl(),
  content_private.admin_merge_artists_impl(uuid, uuid, text)
to authenticated;

create or replace function public.admin_find_duplicate_artists()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select content_private.admin_find_duplicate_artists_impl();
$function$;

create or replace function public.admin_merge_artists(
  p_survivor_id uuid,
  p_merged_id uuid,
  p_reason text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_merge_artists_impl(p_survivor_id, p_merged_id, p_reason);
$function$;

revoke all on function
  public.admin_find_duplicate_artists(),
  public.admin_merge_artists(uuid, uuid, text)
from public, anon, authenticated, service_role;
grant execute on function
  public.admin_find_duplicate_artists(),
  public.admin_merge_artists(uuid, uuid, text)
to authenticated;

commit;
