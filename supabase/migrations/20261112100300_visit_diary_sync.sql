-- Spec 103 M4: the visit diary on the private My Gallr account sync (US1–US3).
--
-- Visits gain a visit date, a feeling, up to three tags, a private note and the time the entry was last saved. Six
-- mutation kinds join the five of 20260814140000, in the app's wire format
-- (shared/src/commonMain/.../MyGallrAccountApiClient.kt): update_visit and merge_visit send the add_visit record plus
-- visit_date, feeling, tags, note and entry_updated_at, every key present and null when empty; delete_entry,
-- publish_note, unpublish_note and acknowledge_note_approval send only exhibition_id. The rules mirror the app's local
-- reducer (MyGallrAccountSync.kt) and mergeEntry (EntryMerge.kt):
--
-- update_visit  creates a missing entry; otherwise replaces the four diary fields and the time only when the stored
--               time is null or the incoming one is strictly later (a tie keeps the server row). An entry left
--               without a note withdraws its 감상.
-- merge_visit   creates a missing entry; otherwise fills only what the account left empty, joins two differing notes
--               account first with a blank line, and keeps the later time. A join that would pass 562 characters
--               keeps the account's note, so a merge never fails the sync.
-- delete_entry  deletes the entry and its 감상.
-- publish_note  makes the entry's note (at most 280 characters) the 감상: inserted, or updated and asked for review
--               again, except when the same text is already approved. The M1 trigger (20261112100000) applies the
--               author rules, so new or changed text is pending at once. No entry or no publishable note: no-op.
-- unpublish_note deletes the 감상; the private note stays.
-- acknowledge_note_approval records that the author saw an approval (approved 감상 only).
--
-- New kinds raise only for a malformed record (visit_invalid, or mutation_record_invalid for a date or time that
-- cannot be read); semantic conflicts are no-ops that still record their receipt, so one stale mutation cannot block
-- an outbox. Every 1.15 kind keeps its behaviour, and an unknown kind still fails the whole call with 22023
-- mutation_kind_invalid. The archive adds the five diary keys to each visit and a public_notes list of the account's
-- own 감상, with or without an entry; 1.15 decodes the archive ignoring unknown keys.

alter table content_private.my_gallr_visits
  add column if not exists visit_date date,
  add column if not exists feeling text,
  add column if not exists tags text[] not null default '{}',
  add column if not exists note text,
  add column if not exists entry_updated_at timestamptz;

create or replace function content_private.visit_tags_valid(p_tags text[])
returns boolean
language sql
immutable
set search_path = ''
as $$
  select coalesce(
    cardinality(p_tags) <= 3
      and cardinality(p_tags) = (select count(distinct tag) from unnest(p_tags) as tag)
      and not exists (
        select 1
        from unnest(p_tags) as tag
        where tag is null
          or tag not in (
            'the_work', 'the_space', 'food_for_thought', 'calm_to_linger', 'playful', 'moving', 'overwhelming',
            'heavy', 'see_again'
          )
      ),
    false
  );
$$;

revoke all on function content_private.visit_tags_valid(text[]) from public, anon, authenticated;

alter table content_private.my_gallr_visits drop constraint if exists my_gallr_visits_feeling;
alter table content_private.my_gallr_visits
  add constraint my_gallr_visits_feeling
  check (feeling is null or feeling in ('loved', 'fine', 'not_for_me'));

alter table content_private.my_gallr_visits drop constraint if exists my_gallr_visits_tags;
alter table content_private.my_gallr_visits
  add constraint my_gallr_visits_tags
  check (content_private.visit_tags_valid(tags));

alter table content_private.my_gallr_visits drop constraint if exists my_gallr_visits_note;
alter table content_private.my_gallr_visits
  add constraint my_gallr_visits_note
  check (note is null or char_length(note) between 1 and 562);

alter table content_private.my_gallr_visits drop constraint if exists my_gallr_visits_visit_date;
alter table content_private.my_gallr_visits
  add constraint my_gallr_visits_visit_date
  check (visit_date is null or visit_date >= date '2000-01-01');

comment on column content_private.my_gallr_visits.note is
  'The private diary note: 1–280 characters from the entry sheet, up to 562 after a sign-in merge joins two.';
comment on column content_private.my_gallr_visits.entry_updated_at is
  'When the diary fields were last saved on a device; update_visit keeps the later save.';

create or replace function content_private.my_gallr_archive_json(p_user_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select jsonb_build_object(
    'revision', archive.revision,
    'visits', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'client_record_id', visit.client_record_id,
            'exhibition_id', visit.exhibition_id,
            'snapshot', jsonb_build_object(
              'name_ko', visit.name_ko,
              'name_en', visit.name_en,
              'venue_name_ko', visit.venue_name_ko,
              'venue_name_en', visit.venue_name_en,
              'opening_date', visit.opening_date,
              'closing_date', visit.closing_date,
              'cover_image_url', visit.cover_image_url
            ),
            'created_at', visit.visited_at,
            'visit_date', visit.visit_date,
            'feeling', visit.feeling,
            'tags', to_jsonb(visit.tags),
            'note', visit.note,
            'entry_updated_at', visit.entry_updated_at
          ) order by visit.visited_at desc, visit.exhibition_id
        )
        from content_private.my_gallr_visits as visit
        where visit.user_id = archive.user_id
      ),
      '[]'::jsonb
    ),
    'followed_galleries', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'gallery_key', gallery.gallery_key,
            'gallery_id', gallery.gallery_id,
            'snapshot', jsonb_build_object(
              'name_ko', gallery.name_ko,
              'name_en', gallery.name_en,
              'city_ko', gallery.city_ko,
              'city_en', gallery.city_en,
              'region_ko', gallery.region_ko,
              'region_en', gallery.region_en
            ),
            'known_exhibition_ids', gallery.known_exhibition_ids,
            'followed_at', gallery.followed_at
          ) order by gallery.followed_at desc, gallery.gallery_key
        )
        from content_private.my_gallr_followed_galleries as gallery
        where gallery.user_id = archive.user_id
      ),
      '[]'::jsonb
    ),
    'public_notes', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'exhibition_id', thought.exhibition_id,
            'thought_id', thought.id,
            'content', thought.content,
            'status', thought.moderation_status,
            'rejection_reason', thought.rejection_reason,
            'requested_at', thought.requested_at,
            'decided_at', thought.decided_at,
            'approved_at', thought.approved_at,
            'approval_seen', thought.approval_seen_at is not null
          ) order by thought.requested_at desc, thought.exhibition_id
        )
        from public.thoughts as thought
        where thought.user_id = archive.user_id
      ),
      '[]'::jsonb
    )
  )
  from content_private.my_gallr_archives as archive
  where archive.user_id = p_user_id;
$function$;

create or replace function content_private.sync_my_gallr_archive_impl(
  p_mutations jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := auth.uid();
  v_mutation jsonb;
  v_mutation_id uuid;
  v_kind text;
  v_request_hash text;
  v_existing_hash text;
  v_revision bigint;
  v_record jsonb;
  v_snapshot jsonb;
  v_gallery_id uuid;
  v_known_ids text[];
  v_exhibition_id text;
  v_visit_date date;
  v_feeling text;
  v_tags text[];
  v_note text;
  v_entry_updated_at timestamptz;
  v_thought public.thoughts%rowtype;
begin
  if v_user_id is null
     or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_mutations is null or jsonb_typeof(p_mutations) <> 'array' then
    raise exception using errcode = '22023', message = 'mutations_invalid';
  end if;
  if jsonb_array_length(p_mutations) > 100 then
    raise exception using errcode = '22023', message = 'too_many_mutations';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('my-gallr:' || v_user_id::text, 0)
  );
  insert into content_private.my_gallr_archives (user_id)
  values (v_user_id)
  on conflict (user_id) do nothing;

  for v_mutation in select value from jsonb_array_elements(p_mutations) loop
    if jsonb_typeof(v_mutation) <> 'object' then
      raise exception using errcode = '22023', message = 'mutation_invalid';
    end if;
    begin
      v_mutation_id := (v_mutation ->> 'mutation_id')::uuid;
    exception when others then
      raise exception using errcode = '22023', message = 'mutation_id_invalid';
    end;
    v_kind := v_mutation ->> 'kind';
    if v_kind not in (
      'add_visit', 'remove_visit', 'follow_gallery',
      'unfollow_gallery', 'acknowledge_gallery',
      'update_visit', 'merge_visit', 'delete_entry',
      'publish_note', 'unpublish_note', 'acknowledge_note_approval'
    ) then
      raise exception using errcode = '22023', message = 'mutation_kind_invalid';
    end if;
    v_request_hash := encode(extensions.digest(v_mutation::text, 'sha256'), 'hex');

    select receipt.request_hash
    into v_existing_hash
    from content_private.my_gallr_mutation_receipts as receipt
    where receipt.user_id = v_user_id
      and receipt.mutation_id = v_mutation_id;
    if found then
      if v_existing_hash <> v_request_hash then
        raise exception using errcode = '22023', message = 'mutation_id_conflict';
      end if;
      continue;
    end if;

    v_record := coalesce(v_mutation -> 'record', '{}'::jsonb);
    v_snapshot := coalesce(v_record -> 'snapshot', '{}'::jsonb);
    v_exhibition_id := v_record ->> 'exhibition_id';

    -- The diary record of update_visit and merge_visit: the add_visit record plus five keys, null when empty.
    if v_kind in ('update_visit', 'merge_visit') then
      if length(coalesce(v_record ->> 'exhibition_id', '')) not between 1 and 200
         or length(coalesce(v_record ->> 'client_record_id', '')) not between 1 and 200
         or length(coalesce(v_snapshot ->> 'name_ko', '')) not between 1 and 500
         or length(coalesce(v_snapshot ->> 'venue_name_ko', '')) not between 1 and 500 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;

      case coalesce(jsonb_typeof(v_record -> 'visit_date'), 'null')
        when 'null' then
          v_visit_date := null;
        when 'string' then
          if (v_record ->> 'visit_date') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
            raise exception using errcode = '22023', message = 'visit_invalid';
          end if;
          v_visit_date := (v_record ->> 'visit_date')::date;
        else
          raise exception using errcode = '22023', message = 'visit_invalid';
      end case;

      case coalesce(jsonb_typeof(v_record -> 'feeling'), 'null')
        when 'null' then
          v_feeling := null;
        when 'string' then
          v_feeling := v_record ->> 'feeling';
          if v_feeling not in ('loved', 'fine', 'not_for_me') then
            raise exception using errcode = '22023', message = 'visit_invalid';
          end if;
        else
          raise exception using errcode = '22023', message = 'visit_invalid';
      end case;

      case coalesce(jsonb_typeof(v_record -> 'tags'), 'null')
        when 'null' then
          v_tags := '{}';
        when 'array' then
          if exists (
            select 1 from jsonb_array_elements(v_record -> 'tags') as tag where jsonb_typeof(tag) <> 'string'
          ) then
            raise exception using errcode = '22023', message = 'visit_invalid';
          end if;
          select coalesce(array_agg(tag.value order by tag.ordinality), '{}')
          into v_tags
          from jsonb_array_elements_text(v_record -> 'tags') with ordinality as tag (value, ordinality);
          if not content_private.visit_tags_valid(v_tags) then
            raise exception using errcode = '22023', message = 'visit_invalid';
          end if;
        else
          raise exception using errcode = '22023', message = 'visit_invalid';
      end case;

      case coalesce(jsonb_typeof(v_record -> 'note'), 'null')
        when 'null' then
          v_note := null;
        when 'string' then
          v_note := nullif(v_record ->> 'note', '');
          if char_length(v_note) > 562 then
            raise exception using errcode = '22023', message = 'visit_invalid';
          end if;
        else
          raise exception using errcode = '22023', message = 'visit_invalid';
      end case;

      case coalesce(jsonb_typeof(v_record -> 'entry_updated_at'), 'null')
        when 'null' then
          v_entry_updated_at := null;
        when 'string' then
          v_entry_updated_at := (v_record ->> 'entry_updated_at')::timestamptz;
        else
          raise exception using errcode = '22023', message = 'visit_invalid';
      end case;
    elsif v_kind in ('delete_entry', 'publish_note', 'unpublish_note', 'acknowledge_note_approval') then
      if length(coalesce(v_exhibition_id, '')) not between 1 and 200 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;
    end if;

    if v_kind = 'add_visit' then
      if length(coalesce(v_record ->> 'exhibition_id', '')) not between 1 and 200
         or length(coalesce(v_record ->> 'client_record_id', '')) not between 1 and 200
         or length(coalesce(v_snapshot ->> 'name_ko', '')) not between 1 and 500
         or length(coalesce(v_snapshot ->> 'venue_name_ko', '')) not between 1 and 500 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;
      insert into content_private.my_gallr_visits (
        user_id, exhibition_id, client_record_id,
        name_ko, name_en, venue_name_ko, venue_name_en,
        opening_date, closing_date, cover_image_url, visited_at
      ) values (
        v_user_id,
        v_record ->> 'exhibition_id',
        v_record ->> 'client_record_id',
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        v_snapshot ->> 'venue_name_ko',
        coalesce(v_snapshot ->> 'venue_name_en', ''),
        (v_snapshot ->> 'opening_date')::date,
        (v_snapshot ->> 'closing_date')::date,
        nullif(v_snapshot ->> 'cover_image_url', ''),
        (v_record ->> 'created_at')::timestamptz
      ) on conflict (user_id, exhibition_id) do nothing;
    elsif v_kind = 'remove_visit' then
      if length(coalesce(v_record ->> 'exhibition_id', '')) not between 1 and 200 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;
      delete from content_private.my_gallr_visits
      where user_id = v_user_id
        and exhibition_id = v_record ->> 'exhibition_id';
    elsif v_kind = 'follow_gallery' then
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000
         or length(coalesce(v_snapshot ->> 'name_ko', '')) not between 1 and 500 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      begin
        v_gallery_id := nullif(v_record ->> 'gallery_id', '')::uuid;
      exception when others then
        raise exception using errcode = '22023', message = 'gallery_id_invalid';
      end;
      select coalesce(array_agg(distinct known_id), '{}')
      into v_known_ids
      from jsonb_array_elements_text(
        coalesce(v_record -> 'known_exhibition_ids', '[]'::jsonb)
      ) as known_id;
      if cardinality(v_known_ids) > 500 then
        raise exception using errcode = '22023', message = 'too_many_known_exhibitions';
      end if;
      insert into content_private.my_gallr_followed_galleries (
        user_id, gallery_key, gallery_id,
        name_ko, name_en, city_ko, city_en, region_ko, region_en,
        known_exhibition_ids, followed_at
      ) values (
        v_user_id,
        v_record ->> 'gallery_key',
        v_gallery_id,
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        coalesce(v_snapshot ->> 'city_ko', ''),
        coalesce(v_snapshot ->> 'city_en', ''),
        coalesce(v_snapshot ->> 'region_ko', ''),
        coalesce(v_snapshot ->> 'region_en', ''),
        v_known_ids,
        (v_record ->> 'followed_at')::timestamptz
      ) on conflict (user_id, gallery_key) do update
      set gallery_id = coalesce(
            content_private.my_gallr_followed_galleries.gallery_id,
            excluded.gallery_id
          ),
          known_exhibition_ids = (
            select array_agg(distinct exhibition_id)
            from unnest(
              content_private.my_gallr_followed_galleries.known_exhibition_ids
              || excluded.known_exhibition_ids
            ) as exhibition_id
          ),
          updated_at = now();
    elsif v_kind = 'unfollow_gallery' then
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      delete from content_private.my_gallr_followed_galleries
      where user_id = v_user_id
        and gallery_key = v_record ->> 'gallery_key';
    elsif v_kind = 'acknowledge_gallery' then
      select coalesce(array_agg(distinct known_id), '{}')
      into v_known_ids
      from jsonb_array_elements_text(
        coalesce(v_record -> 'known_exhibition_ids', '[]'::jsonb)
      ) as known_id;
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000
         or cardinality(v_known_ids) > 500 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      update content_private.my_gallr_followed_galleries
      set known_exhibition_ids = (
            select array_agg(distinct exhibition_id)
            from unnest(known_exhibition_ids || v_known_ids) as exhibition_id
          ),
          updated_at = now()
      where user_id = v_user_id
        and gallery_key = v_record ->> 'gallery_key';
    elsif v_kind = 'update_visit' then
      insert into content_private.my_gallr_visits (
        user_id, exhibition_id, client_record_id,
        name_ko, name_en, venue_name_ko, venue_name_en,
        opening_date, closing_date, cover_image_url, visited_at,
        visit_date, feeling, tags, note, entry_updated_at
      ) values (
        v_user_id,
        v_exhibition_id,
        v_record ->> 'client_record_id',
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        v_snapshot ->> 'venue_name_ko',
        coalesce(v_snapshot ->> 'venue_name_en', ''),
        (v_snapshot ->> 'opening_date')::date,
        (v_snapshot ->> 'closing_date')::date,
        nullif(v_snapshot ->> 'cover_image_url', ''),
        (v_record ->> 'created_at')::timestamptz,
        v_visit_date, v_feeling, v_tags, v_note, v_entry_updated_at
      ) on conflict (user_id, exhibition_id) do update
      set visit_date = excluded.visit_date,
          feeling = excluded.feeling,
          tags = excluded.tags,
          note = excluded.note,
          entry_updated_at = excluded.entry_updated_at,
          updated_at = now()
      where content_private.my_gallr_visits.entry_updated_at is null
         or excluded.entry_updated_at > content_private.my_gallr_visits.entry_updated_at;
      -- An entry left without a note withdraws its 감상, whichever save won.
      if not exists (
        select 1
        from content_private.my_gallr_visits as visit
        where visit.user_id = v_user_id
          and visit.exhibition_id = v_exhibition_id
          and visit.note is not null
      ) then
        delete from public.thoughts
        where user_id = v_user_id
          and exhibition_id = v_exhibition_id;
      end if;
    elsif v_kind = 'merge_visit' then
      insert into content_private.my_gallr_visits (
        user_id, exhibition_id, client_record_id,
        name_ko, name_en, venue_name_ko, venue_name_en,
        opening_date, closing_date, cover_image_url, visited_at,
        visit_date, feeling, tags, note, entry_updated_at
      ) values (
        v_user_id,
        v_exhibition_id,
        v_record ->> 'client_record_id',
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        v_snapshot ->> 'venue_name_ko',
        coalesce(v_snapshot ->> 'venue_name_en', ''),
        (v_snapshot ->> 'opening_date')::date,
        (v_snapshot ->> 'closing_date')::date,
        nullif(v_snapshot ->> 'cover_image_url', ''),
        (v_record ->> 'created_at')::timestamptz,
        v_visit_date, v_feeling, v_tags, v_note, v_entry_updated_at
      ) on conflict (user_id, exhibition_id) do update
      set visit_date = coalesce(content_private.my_gallr_visits.visit_date, excluded.visit_date),
          feeling = coalesce(content_private.my_gallr_visits.feeling, excluded.feeling),
          tags = case
            when cardinality(content_private.my_gallr_visits.tags) > 0 then content_private.my_gallr_visits.tags
            else excluded.tags
          end,
          note = case
            when content_private.my_gallr_visits.note is null then excluded.note
            when excluded.note is null or excluded.note = content_private.my_gallr_visits.note
              then content_private.my_gallr_visits.note
            when char_length(content_private.my_gallr_visits.note) + 2 + char_length(excluded.note) > 562
              then content_private.my_gallr_visits.note
            else content_private.my_gallr_visits.note || E'\n\n' || excluded.note
          end,
          entry_updated_at = greatest(
            content_private.my_gallr_visits.entry_updated_at,
            excluded.entry_updated_at
          ),
          updated_at = now();
    elsif v_kind = 'delete_entry' then
      delete from content_private.my_gallr_visits
      where user_id = v_user_id
        and exhibition_id = v_exhibition_id;
      delete from public.thoughts
      where user_id = v_user_id
        and exhibition_id = v_exhibition_id;
    elsif v_kind = 'publish_note' then
      select visit.note
      into v_note
      from content_private.my_gallr_visits as visit
      where visit.user_id = v_user_id
        and visit.exhibition_id = v_exhibition_id;
      if v_note is not null and char_length(v_note) <= 280 then
        select *
        into v_thought
        from public.thoughts
        where user_id = v_user_id
          and exhibition_id = v_exhibition_id;
        if not found then
          insert into public.thoughts (user_id, exhibition_id, content)
          values (v_user_id, v_exhibition_id, v_note);
        elsif not (v_thought.moderation_status = 'approved' and v_thought.content = v_note) then
          -- The M1 trigger treats this as the author: new text, or asking again after a rejection, is pending.
          update public.thoughts
          set content = v_note,
              moderation_status = 'pending'
          where id = v_thought.id;
        end if;
      end if;
    elsif v_kind = 'unpublish_note' then
      delete from public.thoughts
      where user_id = v_user_id
        and exhibition_id = v_exhibition_id;
    elsif v_kind = 'acknowledge_note_approval' then
      update public.thoughts
      set approval_seen_at = now()
      where user_id = v_user_id
        and exhibition_id = v_exhibition_id
        and moderation_status = 'approved'
        and approval_seen_at is null;
    end if;

    update content_private.my_gallr_archives
    set revision = revision + 1,
        updated_at = now()
    where user_id = v_user_id
    returning revision into v_revision;
    insert into content_private.my_gallr_mutation_receipts (
      user_id, mutation_id, request_hash, applied_revision
    ) values (v_user_id, v_mutation_id, v_request_hash, v_revision);
  end loop;

  return content_private.my_gallr_archive_json(v_user_id);
exception
  when check_violation or invalid_datetime_format or datetime_field_overflow then
    raise exception using errcode = '22023', message = 'mutation_record_invalid';
end;
$function$;

revoke all on function
  content_private.my_gallr_archive_json(uuid),
  content_private.sync_my_gallr_archive_impl(jsonb),
  public.sync_my_gallr_archive(jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.sync_my_gallr_archive(jsonb)
to authenticated;
