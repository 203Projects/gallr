-- Spec 103 M4: the diary on the account sync. Visits gain a date, a feeling, up to three tags, a private note and an
-- entry update time; six mutation kinds save, merge and delete entries and publish, withdraw and acknowledge 감상.
-- The wire format is the app's (shared/.../MyGallrAccountApiClient.kt, merged in PR #413): update_visit and
-- merge_visit send the add_visit record plus the five diary keys, always present, null when empty; the other four
-- send only exhibition_id. The server rules mirror the app's local reducer (MyGallrAccountSync.kt, EntryMerge.kt).
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(69);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

create function pg_temp.act_as_anon() returns void language sql as $$
  select set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
grant execute on function pg_temp.act_as_anon() to anon;

create function pg_temp.act_as_service() returns void language sql as $$
  select set_config('request.jwt.claims', '', true);
$$;

-- An update_visit / merge_visit record exactly as the app encodes it.
create function pg_temp.entry(
  p_exhibition text,
  p_visit_date text,
  p_feeling text,
  p_tags jsonb,
  p_note text,
  p_entry_updated_at text
) returns jsonb language sql immutable as $$
  select jsonb_build_object(
    'client_record_id', 'record-' || p_exhibition,
    'exhibition_id', p_exhibition,
    'created_at', '2026-11-01T09:00:00Z',
    'snapshot', jsonb_build_object(
      'name_ko', '전시 ' || p_exhibition, 'name_en', 'Show', 'venue_name_ko', '갤러리', 'venue_name_en', 'Gallery',
      'opening_date', '2026-10-01', 'closing_date', '2026-12-31', 'cover_image_url', null
    ),
    'visit_date', p_visit_date,
    'feeling', p_feeling,
    'tags', p_tags,
    'note', p_note,
    'entry_updated_at', p_entry_updated_at
  );
$$;
grant execute on function pg_temp.entry(text, text, text, jsonb, text, text) to authenticated;

-- The 1.15 add_visit record: no diary keys.
create function pg_temp.visit_record(p_exhibition text) returns jsonb language sql immutable as $$
  select pg_temp.entry(p_exhibition, null, null, '[]', null, null)
    - 'visit_date' - 'feeling' - 'tags' - 'note' - 'entry_updated_at';
$$;
grant execute on function pg_temp.visit_record(text) to authenticated;

create function pg_temp.exhibition(p_exhibition text) returns jsonb language sql immutable as $$
  select jsonb_build_object('exhibition_id', p_exhibition);
$$;
grant execute on function pg_temp.exhibition(text) to authenticated;

create function pg_temp.mutation(p_kind text, p_record jsonb, p_id uuid default null) returns jsonb
language sql volatile as $$
  select jsonb_build_object('mutation_id', coalesce(p_id, gen_random_uuid()), 'kind', p_kind, 'record', p_record);
$$;
grant execute on function pg_temp.mutation(text, jsonb, uuid) to authenticated;

create function pg_temp.sync(variadic p_mutations jsonb[]) returns jsonb language sql volatile as $$
  select public.sync_my_gallr_archive(to_jsonb(p_mutations));
$$;
grant execute on function pg_temp.sync(jsonb[]) to authenticated;

create function pg_temp.archive() returns jsonb language sql volatile as $$
  select public.sync_my_gallr_archive('[]'::jsonb);
$$;
grant execute on function pg_temp.archive() to authenticated;

create function pg_temp.visit(p_exhibition text) returns jsonb language sql volatile as $$
  select item from jsonb_array_elements(pg_temp.archive() -> 'visits') as item
  where item ->> 'exhibition_id' = p_exhibition;
$$;
grant execute on function pg_temp.visit(text) to authenticated;

create function pg_temp.public_note(p_exhibition text) returns jsonb language sql volatile as $$
  select item from jsonb_array_elements(pg_temp.archive() -> 'public_notes') as item
  where item ->> 'exhibition_id' = p_exhibition;
$$;
grant execute on function pg_temp.public_note(text) to authenticated;

-- The diary fields of a visit as one comparable value.
create function pg_temp.diary(p_exhibition text) returns jsonb language sql volatile as $$
  select jsonb_build_object(
    'visit_date', visit -> 'visit_date',
    'feeling', visit -> 'feeling',
    'tags', visit -> 'tags',
    'note', visit -> 'note',
    'entry_updated_at', to_jsonb((visit ->> 'entry_updated_at')::timestamptz)
  )
  from pg_temp.visit(p_exhibition) as visit;
$$;
grant execute on function pg_temp.diary(text) to authenticated;

create function pg_temp.expected_diary(
  p_visit_date text, p_feeling text, p_tags jsonb, p_note text, p_entry_updated_at timestamptz
) returns jsonb language sql immutable as $$
  select jsonb_build_object(
    'visit_date', to_jsonb(p_visit_date), 'feeling', to_jsonb(p_feeling), 'tags', p_tags,
    'note', to_jsonb(p_note), 'entry_updated_at', to_jsonb(p_entry_updated_at)
  );
$$;
grant execute on function pg_temp.expected_diary(text, text, jsonb, text, timestamptz) to authenticated;

create function pg_temp.keys(p_item jsonb) returns text language sql immutable as $$
  select string_agg(key, ',' order by key) from jsonb_object_keys(p_item) as key;
$$;
grant execute on function pg_temp.keys(jsonb) to authenticated;

-- ── Shape and access ─────────────────────────────────────────────────────────────────────────────────────────────

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'content_private' and table_name = 'my_gallr_visits'
      and column_name in ('visit_date', 'feeling', 'tags', 'note', 'entry_updated_at')
  ),
  5,
  'visits have the five diary columns'
);
select ok(
  content_private.visit_tags_valid(array['the_work', 'moving', 'see_again'])
    and content_private.visit_tags_valid(array[]::text[])
    and not content_private.visit_tags_valid(array['the_work', 'moving', 'see_again', 'heavy'])
    and not content_private.visit_tags_valid(array['the_work', 'the_work'])
    and not content_private.visit_tags_valid(array['the_work', 'great'])
    and not content_private.visit_tags_valid(null),
  'visit_tags_valid accepts up to three distinct known tags'
);
select ok(
  not has_table_privilege('anon', 'content_private.my_gallr_visits', 'SELECT')
    and not has_table_privilege('authenticated', 'content_private.my_gallr_visits', 'SELECT'),
  'nobody reads visits directly'
);
select is(
  (
    select string_agg(proc.proname, ',' order by proc.proname)
    from pg_proc as proc
    where proc.prosrc like '%my_gallr_visits%'
  ),
  'my_gallr_archive_json,sync_my_gallr_archive_impl',
  'only the account archive functions read visits, so no staff function can return a diary field'
);
select ok(
  not has_function_privilege('authenticated', 'content_private.visit_tags_valid(text[])', 'EXECUTE')
    and not has_function_privilege('anon', 'content_private.visit_tags_valid(text[])', 'EXECUTE'),
  'the tag check is not callable by clients'
);

-- ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('79000000-0000-4000-8000-000000000001', 'diary-one@example.invalid', now(), '{}'),
  ('79000000-0000-4000-8000-000000000002', 'diary-two@example.invalid', now(), '{}');

set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000001');

-- ── update_visit: create, last write wins, ties keep the server row ─────────────────────────────────────────────

select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-a', '2026-11-05', 'loved', '["the_work","moving"]', 'First note', '2026-11-05T10:00:00Z')));
select is(
  pg_temp.diary('ex079-a'),
  pg_temp.expected_diary('2026-11-05', 'loved', '["the_work","moving"]', 'First note', '2026-11-05T10:00:00Z'),
  'update_visit creates a missing entry with its diary fields'
);
select is(
  pg_temp.keys(pg_temp.visit('ex079-a')),
  'client_record_id,created_at,entry_updated_at,exhibition_id,feeling,note,snapshot,tags,visit_date',
  'an archive visit keeps the 1.15 keys and adds the five diary keys'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-a', '2026-11-04', 'fine', '[]', 'Older', '2026-11-05T09:00:00Z')));
select is(
  pg_temp.diary('ex079-a') ->> 'note',
  'First note',
  'an older update is ignored'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-a', '2026-11-04', 'fine', '[]', 'Tie', '2026-11-05T10:00:00Z')));
select is(
  pg_temp.diary('ex079-a') ->> 'note',
  'First note',
  'an update with the same time keeps the server row'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-a', null, 'fine', '[]', 'Newer note', '2026-11-05T11:00:00Z')));
select is(
  pg_temp.diary('ex079-a'),
  pg_temp.expected_diary(null, 'fine', '[]', 'Newer note', '2026-11-05T11:00:00Z'),
  'a later update replaces all four diary fields, clearing what it leaves empty'
);
select pg_temp.sync(pg_temp.mutation('add_visit', pg_temp.visit_record('ex079-a')));
select is(
  pg_temp.diary('ex079-a') ->> 'note',
  'Newer note',
  'add_visit on an existing entry still does nothing'
);
select pg_temp.sync(pg_temp.mutation('add_visit', pg_temp.visit_record('ex079-b')));
select is(
  pg_temp.diary('ex079-b'),
  pg_temp.expected_diary(null, null, '[]', null, null),
  'a 1.15 add_visit makes an empty entry'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-b', '2026-11-06', null, '[]', 'B note', '2026-11-06T10:00:00Z')));
select is(
  pg_temp.diary('ex079-b') ->> 'note',
  'B note',
  'an update fills an entry that was never saved'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-b', '2026-11-06', null, '[]', 'B with no time', null)));
select is(
  pg_temp.diary('ex079-b') ->> 'note',
  'B note',
  'an update without a time never replaces a saved entry'
);

-- ── merge_visit: the case table shared with EntryMergeTest ──────────────────────────────────────────────────────

select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m1', '2026-11-01', 'loved', '["playful"]', 'Device only', null)));
select is(
  pg_temp.diary('ex079-m1'),
  pg_temp.expected_diary('2026-11-01', 'loved', '["playful"]', 'Device only', null),
  'merge into no entry: the device entry is kept, its missing time accepted'
);
select pg_temp.sync(pg_temp.mutation('add_visit', pg_temp.visit_record('ex079-m2')));
select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m2', '2026-11-02', 'loved', '["playful"]', 'Device', '2026-11-02T10:00:00Z')));
select is(
  pg_temp.diary('ex079-m2'),
  pg_temp.expected_diary('2026-11-02', 'loved', '["playful"]', 'Device', '2026-11-02T10:00:00Z'),
  'account empty: the device fills every field'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-m3', '2026-11-03', 'fine', '["heavy"]', 'Account', '2026-11-03T10:00:00Z')));
select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m3', null, null, '[]', null, null)));
select is(
  pg_temp.diary('ex079-m3'),
  pg_temp.expected_diary('2026-11-03', 'fine', '["heavy"]', 'Account', '2026-11-03T10:00:00Z'),
  'device empty: the account entry is unchanged'
);
select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m3', '2026-11-09', 'loved', '["moving"]', 'Account', '2026-11-01T10:00:00Z')));
select is(
  pg_temp.diary('ex079-m3'),
  pg_temp.expected_diary('2026-11-03', 'fine', '["heavy"]', 'Account', '2026-11-03T10:00:00Z'),
  'both set and notes equal: the account keeps its date, feeling, tags and note'
);
select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m3', null, null, '[]', 'Device text', '2026-11-04T10:00:00Z')));
select is(
  pg_temp.diary('ex079-m3'),
  pg_temp.expected_diary(
    '2026-11-03', 'fine', '["heavy"]', E'Account\n\nDevice text', '2026-11-04T10:00:00Z'
  ),
  'notes differ: account first, then the device, and the later time is kept'
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-m4', null, null, '[]', repeat('가', 280), '2026-11-01T10:00:00Z')));
select pg_temp.sync(pg_temp.mutation('merge_visit',
  pg_temp.entry('ex079-m4', null, null, '[]', repeat('나', 280), null)));
select is(
  char_length(pg_temp.diary('ex079-m4') ->> 'note'),
  562,
  'two full notes join into 562 characters'
);
select lives_ok(
  $$select pg_temp.sync(pg_temp.mutation('merge_visit',
      pg_temp.entry('ex079-m4', null, null, '[]', 'One more', null)))$$,
  'a join that would pass 562 characters does not fail the sync'
);
select is(
  pg_temp.diary('ex079-m4') ->> 'note',
  repeat('가', 280) || E'\n\n' || repeat('나', 280),
  'a join that would pass 562 characters keeps the account note'
);

-- ── publish_note, acknowledge, unpublish ─────────────────────────────────────────────────────────────────────────

select pg_temp.sync(
  pg_temp.mutation('update_visit',
    pg_temp.entry('ex079-p', '2026-11-07', 'loved', '[]', 'Public text', '2026-11-07T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-p'))
);
select is(
  pg_temp.keys(pg_temp.public_note('ex079-p')),
  'approval_seen,approved_at,content,decided_at,exhibition_id,rejection_reason,requested_at,status,thought_id',
  'a public note carries the keys the app reads'
);
select is(
  pg_temp.public_note('ex079-p') - 'thought_id' - 'requested_at',
  jsonb_build_object(
    'exhibition_id', 'ex079-p', 'content', 'Public text', 'status', 'pending', 'rejection_reason', null,
    'decided_at', null, 'approved_at', null, 'approval_seen', false
  ),
  'publish_note copies the note and waits for review (SC-005, 1.16 path)'
);
reset role;
select pg_temp.act_as_service();
select is(
  (select count(*)::integer from public.thoughts
   where user_id = '79000000-0000-4000-8000-000000000001' and exhibition_id = 'ex079-p'
     and content = 'Public text' and moderation_status = 'pending' and not is_approved),
  1,
  'the 감상 row is pending with the note as its text'
);
update public.thoughts set moderation_status = 'approved', approved_at = now()
where user_id = '79000000-0000-4000-8000-000000000001' and exhibition_id = 'ex079-p';
set local role anon;
select pg_temp.act_as_anon();
select is(
  (select count(*)::integer from public.thoughts where exhibition_id = 'ex079-p'),
  1,
  'once approved it is public'
);
reset role;

set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000001');
select is(
  (pg_temp.public_note('ex079-p') ->> 'status') || '/' || (pg_temp.public_note('ex079-p') ->> 'approval_seen'),
  'approved/false',
  'the author''s archive shows the approval, not yet acknowledged'
);
select pg_temp.sync(pg_temp.mutation('acknowledge_note_approval', pg_temp.exhibition('ex079-p')));
select is(
  (pg_temp.public_note('ex079-p') ->> 'approval_seen')::boolean,
  true,
  'acknowledge_note_approval records that the author saw it'
);
select pg_temp.sync(pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-p')));
select is(
  (pg_temp.public_note('ex079-p') ->> 'status') || '/' || (pg_temp.public_note('ex079-p') ->> 'approval_seen'),
  'approved/true',
  'publishing the same approved text again leaves it approved'
);
select pg_temp.sync(
  pg_temp.mutation('update_visit',
    pg_temp.entry('ex079-p', '2026-11-07', 'loved', '[]', 'Public text, edited', '2026-11-08T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-p'))
);
select is(
  (pg_temp.public_note('ex079-p') ->> 'status') || '/' || (pg_temp.public_note('ex079-p') ->> 'content'),
  'pending/Public text, edited',
  'publishing edited text sends it back to review'
);
reset role;
set local role anon;
select pg_temp.act_as_anon();
select is(
  (select count(*)::integer from public.thoughts where exhibition_id = 'ex079-p'),
  0,
  'edited text leaves the public read at once'
);
reset role;

set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000001');
select pg_temp.sync(
  pg_temp.mutation('update_visit',
    pg_temp.entry('ex079-p', '2026-11-07', 'loved', '[]', repeat('x', 300), '2026-11-09T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-p'))
);
select is(
  pg_temp.public_note('ex079-p') ->> 'content',
  'Public text, edited',
  'a note over 280 characters is not published'
);
select pg_temp.sync(pg_temp.mutation('unpublish_note', pg_temp.exhibition('ex079-p')));
select is(
  pg_temp.public_note('ex079-p'),
  null,
  'unpublish_note withdraws the 감상'
);
select is(
  pg_temp.diary('ex079-p') ->> 'note',
  repeat('x', 300),
  'unpublishing keeps the private note'
);

-- No entry, no note: a no-op that still records its receipt.
create temp table revision_before (revision bigint);
grant all on revision_before to authenticated;
insert into revision_before select (pg_temp.archive() ->> 'revision')::bigint;
select is(
  (pg_temp.sync(pg_temp.mutation(
     'publish_note', pg_temp.exhibition('ex079-none'), '79000000-0000-4000-8000-0000000000c1'
   )) ->> 'revision')::bigint - (select revision from revision_before),
  1::bigint,
  'publishing with no entry changes nothing but still counts as applied'
);
select is(
  pg_temp.public_note('ex079-none'),
  null,
  'no 감상 is made without an entry'
);
select pg_temp.sync(
  pg_temp.mutation('add_visit', pg_temp.visit_record('ex079-nn')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-nn'))
);
select is(
  pg_temp.public_note('ex079-nn'),
  null,
  'no 감상 is made from an entry without a note'
);

-- An update that leaves the entry without a note withdraws its 감상.
select pg_temp.sync(
  pg_temp.mutation('update_visit', pg_temp.entry('ex079-q', null, null, '[]', 'Q', '2026-11-10T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-q'))
);
select pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-q', null, 'fine', '[]', null, '2026-11-10T11:00:00Z')));
select is(
  pg_temp.public_note('ex079-q'),
  null,
  'update_visit with no note deletes the 감상'
);
select is(
  pg_temp.diary('ex079-q') ->> 'feeling',
  'fine',
  'the entry itself stays'
);

-- delete_entry removes both; remove_visit only the visit.
select pg_temp.sync(
  pg_temp.mutation('update_visit', pg_temp.entry('ex079-r', null, null, '[]', 'R', '2026-11-10T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-r')),
  pg_temp.mutation('delete_entry', pg_temp.exhibition('ex079-r'))
);
select is(
  (pg_temp.visit('ex079-r') is null)::text || '/' || (pg_temp.public_note('ex079-r') is null)::text,
  'true/true',
  'delete_entry removes the entry and its 감상'
);
select pg_temp.sync(
  pg_temp.mutation('update_visit', pg_temp.entry('ex079-s', null, null, '[]', 'S', '2026-11-10T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-s')),
  pg_temp.mutation('remove_visit', pg_temp.exhibition('ex079-s'))
);
select is(
  (pg_temp.visit('ex079-s') is null)::text || '/' || (pg_temp.public_note('ex079-s') ->> 'content'),
  'true/S',
  'remove_visit keeps the 감상, which the archive still lists without a visit'
);

-- A rejected 감상 can be asked again with the same text.
select pg_temp.sync(
  pg_temp.mutation('update_visit', pg_temp.entry('ex079-t', null, null, '[]', 'T', '2026-11-10T10:00:00Z')),
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-t'))
);
reset role;
select pg_temp.act_as_service();
update public.thoughts set moderation_status = 'rejected', rejection_reason = 'promotional', decided_at = now()
where user_id = '79000000-0000-4000-8000-000000000001' and exhibition_id = 'ex079-t';
set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000001');
select is(
  pg_temp.public_note('ex079-t') - 'thought_id' - 'requested_at' - 'decided_at',
  jsonb_build_object(
    'exhibition_id', 'ex079-t', 'content', 'T', 'status', 'rejected', 'rejection_reason', 'promotional',
    'approved_at', null, 'approval_seen', false
  ),
  'the archive tells the author a 감상 was rejected and why'
);
select pg_temp.sync(pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-t')));
select is(
  (pg_temp.public_note('ex079-t') ->> 'status') || '/' || coalesce(pg_temp.public_note('ex079-t') ->> 'rejection_reason', 'none'),
  'pending/none',
  'publishing a rejected 감상 again asks for review again'
);

-- A 감상 posted from 1.15 (table insert, no entry) is listed for its author.
insert into public.thoughts (user_id, exhibition_id, content)
values ('79000000-0000-4000-8000-000000000001', 'ex079-old', 'Posted from 1.15');
select is(
  (pg_temp.public_note('ex079-old') ->> 'content') || '/' || (pg_temp.visit('ex079-old') is null)::text,
  'Posted from 1.15/true',
  'a 감상 with no entry is in public_notes'
);

-- ── Semantic conflicts never fail the sync ───────────────────────────────────────────────────────────────────────

select lives_ok(
  $$select pg_temp.sync(
      pg_temp.mutation('acknowledge_note_approval', pg_temp.exhibition('ex079-nothing')),
      pg_temp.mutation('unpublish_note', pg_temp.exhibition('ex079-nothing')),
      pg_temp.mutation('delete_entry', pg_temp.exhibition('ex079-nothing')),
      pg_temp.mutation('acknowledge_note_approval', pg_temp.exhibition('ex079-t'))
    )$$,
  'acknowledging, withdrawing or deleting what is not there is a no-op'
);
select is(
  (pg_temp.public_note('ex079-t') ->> 'approval_seen')::boolean,
  false,
  'acknowledging a 감상 that is not approved records nothing'
);

-- ── Receipts make a replay harmless ──────────────────────────────────────────────────────────────────────────────

create temp table replay (revision bigint);
grant all on replay to authenticated;
insert into replay
select (pg_temp.sync(pg_temp.mutation('update_visit',
  pg_temp.entry('ex079-i', null, null, '[]', 'Once', '2026-11-11T10:00:00Z'),
  '79000000-0000-4000-8000-0000000000d1')) ->> 'revision')::bigint;
select is(
  (pg_temp.sync(pg_temp.mutation('update_visit',
     pg_temp.entry('ex079-i', null, null, '[]', 'Once', '2026-11-11T10:00:00Z'),
     '79000000-0000-4000-8000-0000000000d1')) ->> 'revision')::bigint,
  (select revision from replay),
  'replaying a mutation id does not apply it again'
);
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-i', null, null, '[]', 'Changed', '2026-11-11T11:00:00Z'),
      '79000000-0000-4000-8000-0000000000d1'))$$,
  '22023',
  'mutation_id_conflict',
  'the same mutation id with another payload is refused'
);
reset role;
select pg_temp.act_as_service();
select is(
  (
    select count(*)::integer from content_private.my_gallr_mutation_receipts
    where user_id = '79000000-0000-4000-8000-000000000001'
      and mutation_id in ('79000000-0000-4000-8000-0000000000c1', '79000000-0000-4000-8000-0000000000d1')
  ),
  2,
  'no-op and replayed mutations each hold one receipt'
);

-- ── Owner isolation ──────────────────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000002');
select is(
  jsonb_array_length(pg_temp.archive() -> 'visits') + jsonb_array_length(pg_temp.archive() -> 'public_notes'),
  0,
  'another account sees none of the entries or 감상'
);
select pg_temp.sync(
  pg_temp.mutation('publish_note', pg_temp.exhibition('ex079-m3')),
  pg_temp.mutation('delete_entry', pg_temp.exhibition('ex079-a')),
  pg_temp.mutation('unpublish_note', pg_temp.exhibition('ex079-s'))
);
reset role;
select pg_temp.act_as_service();
select is(
  (select count(*)::integer from public.thoughts where user_id = '79000000-0000-4000-8000-000000000002'),
  0,
  'another account cannot publish from someone else''s entry'
);
select is(
  (
    select count(*)::integer
    from content_private.my_gallr_visits
    where user_id = '79000000-0000-4000-8000-000000000001' and exhibition_id = 'ex079-a'
  ) + (
    select count(*)::integer
    from public.thoughts
    where user_id = '79000000-0000-4000-8000-000000000001' and exhibition_id = 'ex079-s'
  ),
  2,
  'another account cannot delete someone else''s entry or 감상'
);

-- ── Malformed records are refused ────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('79000000-0000-4000-8000-000000000001');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, 'great', '[]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'an unknown feeling is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '"the_work"', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'tags must be an array');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '["the_work","moving","heavy","playful"]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'four tags are refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('merge_visit',
      pg_temp.entry('ex079-x', null, null, '["the_work","great"]', null, null)))$$,
  '22023', 'visit_invalid', 'an unknown tag is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '["moving","moving"]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'a repeated tag is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '[]', repeat('n', 563), '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'a note over 562 characters is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', 'yesterday', null, '[]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'a visit date that is not YYYY-MM-DD is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', '2026-13-45', null, '[]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'mutation_record_invalid', 'a visit date that does not exist is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', '1999-12-31', null, '[]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'mutation_record_invalid', 'a visit date before 2000 is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '[]', null, 'soon')))$$,
  '22023', 'mutation_record_invalid', 'an update time that is not a time is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('update_visit',
      pg_temp.entry('ex079-x', null, null, '[]', null, '2026-11-11T10:00:00Z') - 'client_record_id'))$$,
  '22023', 'visit_invalid', 'an update without the visit record is refused');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('delete_entry', '{}'::jsonb))$$,
  '22023', 'visit_invalid', 'delete_entry needs an exhibition');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('publish_note', pg_temp.exhibition('')))$$,
  '22023', 'visit_invalid', 'publish_note needs an exhibition');
select throws_ok(
  $$select pg_temp.sync(pg_temp.mutation('rate_visit', pg_temp.exhibition('ex079-x')))$$,
  '22023', 'mutation_kind_invalid', 'an unknown kind fails the sync as it does today');
select throws_ok(
  $$select pg_temp.sync(
      pg_temp.mutation('update_visit', pg_temp.entry('ex079-z', null, null, '[]', 'Z', '2026-11-11T10:00:00Z')),
      pg_temp.mutation('update_visit', pg_temp.entry('ex079-z2', null, 'great', '[]', null, '2026-11-11T10:00:00Z')))$$,
  '22023', 'visit_invalid', 'a malformed mutation fails its whole batch');
select is(
  pg_temp.visit('ex079-z'),
  null,
  'nothing of a failed batch is kept'
);
reset role;

-- ── Account deletion ─────────────────────────────────────────────────────────────────────────────────────────────

select pg_temp.act_as_service();
delete from auth.users where id = '79000000-0000-4000-8000-000000000001';
select is(
  (select count(*)::integer from content_private.my_gallr_visits
   where user_id = '79000000-0000-4000-8000-000000000001')
  + (select count(*)::integer from public.thoughts where user_id = '79000000-0000-4000-8000-000000000001')
  + (select count(*)::integer from content_private.my_gallr_mutation_receipts
     where user_id = '79000000-0000-4000-8000-000000000001'),
  0,
  'deleting the account deletes its entries, 감상 and receipts'
);

select * from finish();
rollback;
