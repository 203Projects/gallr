-- Spec 103 M2: reader reports on 감상, the 1.16 reader list, and the staff review functions behind Admin's 감상 section
-- (US4, US5; FR-023, FR-026; owner decisions 6 and 9). The staff payloads must carry exactly the keys
-- admin/src/repositories/SupabaseAdminThoughtRepository.ts reads, and the error messages it maps.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(100);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

create function pg_temp.act_as_anonymous_account(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated', 'is_anonymous', true)::text,
    true
  );
$$;
grant execute on function pg_temp.act_as_anonymous_account(uuid) to authenticated;

create function pg_temp.act_as_anon() returns void language sql as $$
  select set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
grant execute on function pg_temp.act_as_anon() to anon;

create function pg_temp.act_as_service() returns void language sql as $$
  select set_config('request.jwt.claims', '', true);
$$;

create function pg_temp.keys(p_item jsonb) returns text language sql immutable as $$
  select string_agg(key, ',' order by key) from jsonb_object_keys(p_item) as key;
$$;
grant execute on function pg_temp.keys(jsonb) to anon, authenticated;

-- The ids a reader sees on an exhibition page, in order.
create function pg_temp.reader_ids(p_exhibition_id text) returns text language sql as $$
  select coalesce(string_agg(item ->> 'id', ',' order by ordinal), '')
  from jsonb_array_elements(public.list_exhibition_thoughts(p_exhibition_id)) with ordinality as list (item, ordinal);
$$;
grant execute on function pg_temp.reader_ids(text) to anon, authenticated;

create function pg_temp.reader_item(p_exhibition_id text, p_id uuid) returns jsonb language sql as $$
  select item
  from jsonb_array_elements(public.list_exhibition_thoughts(p_exhibition_id)) as item
  where item ->> 'id' = p_id::text;
$$;
grant execute on function pg_temp.reader_item(text, uuid) to anon, authenticated;

create function pg_temp.queue_item(p_id uuid) returns jsonb language sql as $$
  select item from jsonb_array_elements(public.list_thought_review_queue()) as item where item ->> 'id' = p_id::text;
$$;
grant execute on function pg_temp.queue_item(uuid) to authenticated;

create function pg_temp.reported_item(p_id uuid) returns jsonb language sql as $$
  select item from jsonb_array_elements(public.list_reported_thoughts()) as item where item ->> 'id' = p_id::text;
$$;
grant execute on function pg_temp.reported_item(uuid) to authenticated;

-- The staleness token Admin holds: the timestamp exactly as the list serialised it.
create function pg_temp.requested_token(p_id uuid) returns timestamptz language sql as $$
  select (pg_temp.queue_item(p_id) ->> 'requested_at')::timestamptz;
$$;
grant execute on function pg_temp.requested_token(uuid) to authenticated;

create function pg_temp.approved_token(p_id uuid) returns timestamptz language sql as $$
  select (pg_temp.reported_item(p_id) ->> 'approved_at')::timestamptz;
$$;
grant execute on function pg_temp.approved_token(uuid) to authenticated;

-- ── Shape, RLS and grants ────────────────────────────────────────────────────────────────────────────────────────

select has_table('public', 'thought_reports', 'public.thought_reports exists');
select ok(
  (select relrowsecurity from pg_class where oid = 'public.thought_reports'::regclass)
    and not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'thought_reports'),
  'thought_reports has RLS on and no policies'
);
select ok(
  not has_table_privilege('anon', 'public.thought_reports', 'SELECT')
    and not has_table_privilege('anon', 'public.thought_reports', 'INSERT')
    and not has_table_privilege('authenticated', 'public.thought_reports', 'SELECT')
    and not has_table_privilege('authenticated', 'public.thought_reports', 'INSERT')
    and not has_table_privilege('authenticated', 'public.thought_reports', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.thought_reports', 'DELETE'),
  'clients have no table access to thought_reports'
);
select ok(
  exists (
    select 1 from pg_indexes
    where schemaname = 'public' and indexname = 'thought_reports_one_per_account_text'
      and indexdef like 'CREATE UNIQUE INDEX%(thought_id, account_id, approved_at)%'
  ),
  'one report per account per approved text'
);
select is(
  (
    select count(*)::integer
    from pg_proc as proc
    join pg_namespace as namespace on namespace.oid = proc.pronamespace
    where namespace.nspname = 'content_private'
      and proc.proname in (
        'list_exhibition_thoughts_impl', 'report_thought_impl', 'list_thought_review_queue_impl',
        'decide_thought_impl', 'list_reported_thoughts_impl', 'resolve_thought_reports_impl'
      )
      and proc.prosecdef
      and proc.proconfig @> array['search_path=""']
  ),
  6,
  'the six implementations are security definer with an empty search_path'
);
select ok(
  has_function_privilege('anon', 'public.list_exhibition_thoughts(text,integer)', 'EXECUTE')
    and has_function_privilege('authenticated', 'public.list_exhibition_thoughts(text,integer)', 'EXECUTE'),
  'anyone can read an exhibition''s 감상'
);
select ok(
  not has_function_privilege('anon', 'public.report_thought(uuid,text)', 'EXECUTE')
    and not has_function_privilege('anon', 'public.list_thought_review_queue()', 'EXECUTE')
    and not has_function_privilege('anon', 'public.decide_thought(uuid,text,text,timestamptz)', 'EXECUTE')
    and not has_function_privilege('anon', 'public.list_reported_thoughts()', 'EXECUTE')
    and not has_function_privilege('anon', 'public.resolve_thought_reports(uuid,text,text,timestamptz)', 'EXECUTE'),
  'anon can neither report nor reach the staff functions'
);
select ok(
  has_function_privilege('authenticated', 'public.report_thought(uuid,text)', 'EXECUTE')
    and has_function_privilege('authenticated', 'public.list_thought_review_queue()', 'EXECUTE')
    and has_function_privilege('authenticated', 'public.decide_thought(uuid,text,text,timestamptz)', 'EXECUTE')
    and has_function_privilege('authenticated', 'public.list_reported_thoughts()', 'EXECUTE')
    and has_function_privilege('authenticated', 'public.resolve_thought_reports(uuid,text,text,timestamptz)', 'EXECUTE'),
  'signed-in callers reach the functions, which check the role themselves'
);
select ok(
  not has_function_privilege('anon', 'content_private.list_exhibition_thoughts_impl(text,integer)', 'EXECUTE')
    and not has_function_privilege('anon', 'content_private.report_thought_impl(uuid,text)', 'EXECUTE')
    and not has_function_privilege('anon', 'content_private.decide_thought_impl(uuid,text,text,timestamptz)', 'EXECUTE'),
  'anon cannot call the implementations directly'
);
select has_column('public', 'thoughts', 'last_approved_at', 'thoughts remember the last approval');

-- ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('77000000-0000-4000-8000-000000000001', 'note-author-a@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000002', 'note-author-b@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000003', 'note-reader-1@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000004', 'note-reader-2@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000005', 'note-reader-3@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000006', 'note-reader-4@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000007', 'note-publisher@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000008', 'note-admin@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-000000000009', 'note-contributor@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-00000000000a', 'note-guest@example.invalid', now(), '{}'),
  ('77000000-0000-4000-8000-00000000000b', 'note-author-e@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('77000000-0000-4000-8000-000000000007', 'publisher', true),
  ('77000000-0000-4000-8000-000000000008', 'admin', true),
  ('77000000-0000-4000-8000-000000000009', 'contributor', true);
update public.profiles set display_name = 'Author A', avatar_url = 'https://example.invalid/a.jpg'
where id = '77000000-0000-4000-8000-000000000001';
update public.profiles set display_name = 'Author B' where id = '77000000-0000-4000-8000-000000000002';
update public.profiles set display_name = '   ' where id = '77000000-0000-4000-8000-00000000000b';

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en, opening_date,
  closing_date, is_featured, latitude, longitude, description_ko, description_en, address_ko, address_en,
  is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
) values (
  'ex-077-a', '감상 전시', 'Note Exhibition', '갤러리', 'Gallery', '서울', 'Seoul', '서울', 'Seoul', current_date,
  current_date + 10, false, 37.5, 127.0, '', '', '서울특별시 테스트로 1', '', false, now(), false, repeat('0', 64)
);

-- Rows written by the service role keep their status as written (M1).
select pg_temp.act_as_service();
insert into public.thoughts (
  id, user_id, exhibition_id, content, moderation_status, approved_at, created_at, updated_at, requested_at
) values
  ('77000000-0000-4000-8000-0000000000a1', '77000000-0000-4000-8000-000000000001', 'ex-077-a', 'A approved note',
   'approved', '2026-11-01 10:00+00', '2026-11-01 09:00+00', '2026-11-01 09:00+00', '2026-11-01 09:00+00'),
  ('77000000-0000-4000-8000-0000000000b1', '77000000-0000-4000-8000-000000000002', 'ex-077-a', 'B approved note',
   'approved', '2026-11-02 10:00+00', '2026-11-02 09:00+00', '2026-11-02 09:00+00', '2026-11-02 09:00+00'),
  ('77000000-0000-4000-8000-0000000000e1', '77000000-0000-4000-8000-00000000000b', 'ex-077-a', 'E pending note',
   'pending', null, '2026-11-03 09:00+00', '2026-11-03 09:00+00', '2026-11-03 09:00+00'),
  ('77000000-0000-4000-8000-0000000000e2', '77000000-0000-4000-8000-00000000000b', 'ex-077-gone', 'E older pending',
   'pending', null, null, null, '2026-11-02 08:00+00'),
  ('77000000-0000-4000-8000-0000000000f1', '77000000-0000-4000-8000-000000000007', 'ex-077-a', 'Publisher own note',
   'pending', null, '2026-11-04 09:00+00', '2026-11-04 09:00+00', '2026-11-04 09:00+00'),
  ('77000000-0000-4000-8000-0000000000d1', '77000000-0000-4000-8000-000000000001', 'ex-077-b', 'A note, approved once',
   'approved', '2026-11-01 11:00+00', '2026-11-01 08:00+00', '2026-11-01 08:00+00', '2026-11-01 08:00+00');

select ok(
  (select last_approved_at = approved_at from public.thoughts where id = '77000000-0000-4000-8000-0000000000a1'),
  'an approved row records its last approval'
);

-- The author edits d1 after approval: it returns to review (M1) and remembers it was approved.
set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000001');
update public.thoughts set content = 'A note, edited' where id = '77000000-0000-4000-8000-0000000000d1';
update public.thoughts set last_approved_at = null where id = '77000000-0000-4000-8000-0000000000d1';
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status || '/' || (last_approved_at = timestamptz '2026-11-01 11:00+00')::text
   from public.thoughts where id = '77000000-0000-4000-8000-0000000000d1'),
  'pending/true',
  'an edited 감상 is pending again, keeps its last approval, and the author cannot clear it'
);

-- ── The reader list ──────────────────────────────────────────────────────────────────────────────────────────────

set local role anon;
select pg_temp.act_as_anon();
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000b1,77000000-0000-4000-8000-0000000000a1',
  'anon reads the approved 감상 of an exhibition, newest first, and no pending one'
);
select is(
  pg_temp.keys(pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000a1')),
  'author_avatar_url,author_display_name,content,created_at,id,reported_by_me,updated_at,user_id',
  'a reader item carries no moderation, decision or diary field'
);
select is(
  pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000a1')
    - 'created_at' - 'updated_at',
  jsonb_build_object(
    'id', '77000000-0000-4000-8000-0000000000a1', 'user_id', '77000000-0000-4000-8000-000000000001',
    'content', 'A approved note', 'author_display_name', 'Author A',
    'author_avatar_url', 'https://example.invalid/a.jpg', 'reported_by_me', false
  ),
  'a reader item names its author'
);
select is(
  jsonb_array_length(public.list_exhibition_thoughts('ex-077-a', 1)),
  1,
  'the reader list honours its limit'
);
select throws_ok(
  $$select public.list_exhibition_thoughts('  ')$$,
  '22023',
  'thought_list_invalid',
  'a blank exhibition id is refused'
);
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'promotional')$$,
  '42501',
  null,
  'anon cannot report'
);
reset role;

-- ── Reports ──────────────────────────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as_anonymous_account('77000000-0000-4000-8000-00000000000a');
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'promotional')$$,
  '42501',
  'thought_report_unauthenticated',
  'an anonymous session cannot report'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'spam')$$,
  '22023',
  'thought_report_invalid_reason',
  'a reason outside the list is refused'
);
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000ff', 'promotional')$$,
  'PT404',
  'thought_not_found',
  'an unknown 감상 cannot be reported'
);
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000e1', 'promotional')$$,
  'PT409',
  'thought_not_public',
  'a pending 감상 cannot be reported'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000001');
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'promotional')$$,
  '42501',
  'thought_report_own',
  'an author cannot report their own 감상'
);

select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select lives_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'promotional')$$,
  'a signed-in reader reports a 감상'
);
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'other')$$,
  'PT409',
  'thought_report_exists',
  'a reader reports the same approved text once'
);
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000b1',
  'the reporter no longer sees the 감상 they reported'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000004');
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000b1,77000000-0000-4000-8000-0000000000a1',
  'another reader still sees it (SC-004)'
);
select lives_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'inappropriate')$$,
  'a second reader reports it'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000005');
select lives_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'other')$$,
  'a third reader reports it'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000006');
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000b1,77000000-0000-4000-8000-0000000000a1',
  'three reports never hide a 감상 from a fourth reader'
);
reset role;
set local role anon;
select pg_temp.act_as_anon();
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000b1,77000000-0000-4000-8000-0000000000a1',
  'reports never hide a 감상 from anon'
);
reset role;

-- An older report on b1, so the reported list puts b1 first (oldest open report first, owner decision 9).
set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select public.report_thought('77000000-0000-4000-8000-0000000000b1', 'not_about_exhibition');
reset role;
select pg_temp.act_as_service();
update public.thought_reports
set created_at = '2026-11-05 09:00+00'
where thought_id = '77000000-0000-4000-8000-0000000000b1';

-- ── Who may moderate ─────────────────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000009');
select throws_ok($$select public.list_thought_review_queue()$$, '42501', 'thought_not_staff',
  'a contributor cannot read the review queue');
select throws_ok($$select public.list_reported_thoughts()$$, '42501', 'thought_not_staff',
  'a contributor cannot read the reported list');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e1', 'approve', null, now())$$,
  '42501', 'thought_not_staff', 'a contributor cannot decide');
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'dismissed', null, now())$$,
  '42501', 'thought_not_staff', 'a contributor cannot resolve reports');
select pg_temp.act_as('77000000-0000-4000-8000-000000000006');
select throws_ok($$select public.list_thought_review_queue()$$, '42501', 'thought_not_staff',
  'a plain account cannot read the review queue');
select throws_ok($$select public.list_reported_thoughts()$$, '42501', 'thought_not_staff',
  'a plain account cannot read the reported list');

-- ── The review queue ─────────────────────────────────────────────────────────────────────────────────────────────

select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select is(
  (
    select string_agg(item ->> 'id', ',' order by ordinal)
    from jsonb_array_elements(public.list_thought_review_queue()) with ordinality as list (item, ordinal)
    where item ->> 'id' like '77000000-%'
  ),
  '77000000-0000-4000-8000-0000000000e2,77000000-0000-4000-8000-0000000000e1,'
    || '77000000-0000-4000-8000-0000000000f1,77000000-0000-4000-8000-0000000000d1',
  'a publisher reads the pending 감상, oldest request first'
);
select is(
  pg_temp.keys(pg_temp.queue_item('77000000-0000-4000-8000-0000000000e1')),
  'author_display_name,content,created_at,exhibition_id,exhibition_name_en,exhibition_name_ko,id,requested_at,'
    || 'was_approved_before',
  'a queue item has exactly the keys Admin reads'
);
select is(
  pg_temp.queue_item('77000000-0000-4000-8000-0000000000e1') - 'requested_at' - 'created_at',
  jsonb_build_object(
    'id', '77000000-0000-4000-8000-0000000000e1', 'content', 'E pending note', 'exhibition_id', 'ex-077-a',
    'exhibition_name_ko', '감상 전시', 'exhibition_name_en', 'Note Exhibition', 'author_display_name', null,
    'was_approved_before', false
  ),
  'a queue item names the exhibition from the catalogue; a blank author name is null'
);
select is(
  pg_temp.queue_item('77000000-0000-4000-8000-0000000000e2') - 'id' - 'content' - 'author_display_name'
    - 'was_approved_before' - 'exhibition_id',
  jsonb_build_object(
    'exhibition_name_ko', null, 'exhibition_name_en', null,
    'requested_at', to_jsonb(timestamptz '2026-11-02 08:00+00'),
    'created_at', to_jsonb(timestamptz '2026-11-02 08:00+00')
  ),
  'an exhibition gone from the catalogue reads null; a missing created_at falls back to requested_at'
);
select is(
  (pg_temp.queue_item('77000000-0000-4000-8000-0000000000d1') ->> 'was_approved_before')::boolean,
  true,
  'an edited 감상 that was approved before says so'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000008');
select is(
  jsonb_typeof(public.list_thought_review_queue()),
  'array',
  'an admin reads the queue too'
);

-- ── Decisions ────────────────────────────────────────────────────────────────────────────────────────────────────

select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'publish', null,
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000e2'))$$,
  '22023', 'thought_decision_invalid', 'an unknown decision is refused');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'reject', null,
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000e2'))$$,
  '22023', 'thought_rejection_reason_required', 'a rejection needs a reason');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'reject', 'spam',
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000e2'))$$,
  '22023', 'thought_rejection_reason_required', 'a rejection reason must be from the list');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000ff', 'approve', null, now())$$,
  'PT404', 'thought_not_found', 'an unknown 감상 cannot be decided');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'approve', null,
    timestamptz '2026-11-02 08:00:01+00')$$,
  'PT409', 'thought_review_stale', 'a decision on text staff did not see is refused');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'approve', null, null)$$,
  'PT409', 'thought_review_stale', 'a decision without the request time is refused');
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000f1', 'approve', null,
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000f1'))$$,
  'PT409', 'thought_review_invalid_transition', 'a publisher cannot decide their own 감상');

select lives_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'approve', null,
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000e2'))$$,
  'a publisher approves with the request time from the queue'
);
select is(
  coalesce(current_setting('gallr.thought_moderation', true), ''),
  '',
  'the moderation flag is cleared right after the write'
);
select throws_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e2', 'reject', 'other',
    timestamptz '2026-11-02 08:00+00')$$,
  'PT409', 'thought_review_invalid_transition', 'a decided 감상 cannot be decided again');
select pg_temp.act_as('77000000-0000-4000-8000-000000000008');
select lives_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000e1', 'reject', 'promotional',
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000e1'))$$,
  'an admin rejects with a reason'
);
reset role;
select pg_temp.act_as_service();

select is(
  (select moderation_status || '/' || is_approved::text || '/' || (decided_by::text) || '/'
     || (approved_at = now())::text || '/' || (approval_seen_at is null)::text || '/'
     || (last_approved_at = now())::text
   from public.thoughts where id = '77000000-0000-4000-8000-0000000000e2'),
  'approved/true/77000000-0000-4000-8000-000000000007/true/true/true',
  'an approval is public, attributed, and dated'
);
select is(
  (select moderation_status || '/' || rejection_reason || '/' || decided_by::text
   from public.thoughts where id = '77000000-0000-4000-8000-0000000000e1'),
  'rejected/promotional/77000000-0000-4000-8000-000000000008',
  'a rejection keeps its reason and who decided'
);
select is(
  (select moderation_status from public.thoughts where id = '77000000-0000-4000-8000-0000000000f1'),
  'pending',
  'the publisher''s own 감상 is still pending'
);
select is(
  (
    select string_agg(action, ',' order by action)
    from content.audit_log
    where entity_type = 'thought'
      and entity_id in ('77000000-0000-4000-8000-0000000000e1', '77000000-0000-4000-8000-0000000000e2')
  ),
  'thought.approved,thought.rejected',
  'each decision writes an audit row'
);
select is(
  (
    select count(*)::integer
    from content.audit_log
    where entity_type = 'thought'
      and entity_id in ('77000000-0000-4000-8000-0000000000e1', '77000000-0000-4000-8000-0000000000e2')
      and (metadata ? 'content' or metadata::text like '%pending%note%' or metadata::text like '%older pending%')
  ),
  0,
  'audit rows carry no 감상 text'
);
select is(
  (
    select metadata
    from content.audit_log
    where entity_type = 'thought' and entity_id = '77000000-0000-4000-8000-0000000000e1'
  ),
  jsonb_build_object('status_before', 'pending', 'status_after', 'rejected', 'reason', 'promotional'),
  'a rejection audit records the status change and reason'
);

set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-00000000000b');
select is(
  (select moderation_status || '/' || rejection_reason from public.thoughts
   where id = '77000000-0000-4000-8000-0000000000e1'),
  'rejected/promotional',
  'the author reads their 감상 as rejected with the reason'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select is(
  pg_temp.queue_item('77000000-0000-4000-8000-0000000000e1'),
  null,
  'a decided 감상 leaves the queue'
);

-- ── The reported list ────────────────────────────────────────────────────────────────────────────────────────────

select is(
  (
    select string_agg(item ->> 'id', ',' order by ordinal)
    from jsonb_array_elements(public.list_reported_thoughts()) with ordinality as list (item, ordinal)
    where item ->> 'id' like '77000000-%'
  ),
  '77000000-0000-4000-8000-0000000000b1,77000000-0000-4000-8000-0000000000a1',
  'the reported list puts the oldest open report first'
);
select is(
  pg_temp.keys(pg_temp.reported_item('77000000-0000-4000-8000-0000000000a1')),
  'approved_at,author_display_name,content,exhibition_id,exhibition_name_en,exhibition_name_ko,first_reported_at,'
    || 'id,open_count,reasons',
  'a reported item has exactly the keys Admin reads'
);
select is(
  pg_temp.reported_item('77000000-0000-4000-8000-0000000000a1') - 'first_reported_at',
  jsonb_build_object(
    'id', '77000000-0000-4000-8000-0000000000a1', 'content', 'A approved note', 'exhibition_id', 'ex-077-a',
    'exhibition_name_ko', '감상 전시', 'exhibition_name_en', 'Note Exhibition', 'author_display_name', 'Author A',
    'approved_at', to_jsonb(timestamptz '2026-11-01 10:00+00'), 'open_count', 3,
    'reasons', jsonb_build_object('inappropriate', 1, 'other', 1, 'promotional', 1)
  ),
  'a reported item counts its open reports by reason'
);
select is(
  (pg_temp.reported_item('77000000-0000-4000-8000-0000000000b1') ->> 'first_reported_at')::timestamptz,
  timestamptz '2026-11-05 09:00+00',
  'a reported item shows when its oldest open report was sent'
);

-- ── Resolving reports ────────────────────────────────────────────────────────────────────────────────────────────

select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'ignore', null,
    pg_temp.approved_token('77000000-0000-4000-8000-0000000000a1'))$$,
  '22023', 'thought_report_invalid_resolution', 'an unknown resolution is refused');
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'upheld', null,
    pg_temp.approved_token('77000000-0000-4000-8000-0000000000a1'))$$,
  '22023', 'thought_rejection_reason_required', 'a take-down needs a reason');
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'dismissed', null,
    timestamptz '2026-11-01 10:00:01+00')$$,
  'PT409', 'thought_reports_stale', 'a decision on reports about another text is refused');
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000ff', 'dismissed', null, now())$$,
  'PT404', 'thought_not_found', 'reports on an unknown 감상 cannot be resolved');

select lives_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'dismissed', null,
    pg_temp.approved_token('77000000-0000-4000-8000-0000000000a1'))$$,
  'a publisher keeps a reported 감상'
);
select is(
  coalesce(current_setting('gallr.thought_moderation', true), ''),
  '',
  'the moderation flag is not left on by a resolution'
);
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000a1', 'dismissed', null,
    timestamptz '2026-11-01 10:00+00')$$,
  'PT409', 'thought_no_open_reports', 'a kept 감상 has no open reports left');
reset role;
select pg_temp.act_as_service();
select is(
  (
    select string_agg(distinct resolution || '/' || resolved_by::text || '/' || (resolved_at is not null)::text, ',')
    from public.thought_reports where thought_id = '77000000-0000-4000-8000-0000000000a1'
  ),
  'dismissed/77000000-0000-4000-8000-000000000007/true',
  'keeping a 감상 closes all its reports as dismissed, recording who'
);
select is(
  (select moderation_status from public.thoughts where id = '77000000-0000-4000-8000-0000000000a1'),
  'approved',
  'a kept 감상 stays public'
);

set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select is(
  (pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000a1') ->> 'reported_by_me')::boolean,
  true,
  'after a dismissal the reporter sees the 감상 again, marked as reported by them'
);
select throws_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'other')$$,
  'PT409',
  'thought_report_exists',
  'a dismissed report is not sent again for the same text'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000006');
select is(
  (pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000a1') ->> 'reported_by_me')::boolean,
  false,
  'a reader who did not report it sees reported_by_me false'
);

-- A report on text that the author then replaced does not describe what is public any more.
select lives_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'promotional')$$,
  'a fourth reader reports the kept 감상'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000001');
update public.thoughts set content = 'A rewrote the note' where id = '77000000-0000-4000-8000-0000000000a1';
select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select is(
  pg_temp.reported_item('77000000-0000-4000-8000-0000000000a1'),
  null,
  'a 감상 that went back to review leaves the reported list'
);
select lives_ok(
  $$select public.decide_thought('77000000-0000-4000-8000-0000000000a1', 'approve', null,
    pg_temp.requested_token('77000000-0000-4000-8000-0000000000a1'))$$,
  'the new text is approved'
);
select is(
  pg_temp.reported_item('77000000-0000-4000-8000-0000000000a1'),
  null,
  'a report on the replaced text does not put the new text on the reported list'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000006');
select is(
  (pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000a1') ->> 'reported_by_me')::boolean,
  false,
  'a reader who reported the replaced text sees the new text, not marked as reported'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select lives_ok(
  $$select public.report_thought('77000000-0000-4000-8000-0000000000a1', 'other')$$,
  'a reader can report re-approved text'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select is(
  (pg_temp.reported_item('77000000-0000-4000-8000-0000000000a1') ->> 'open_count')::integer,
  1,
  'the reported list counts only reports on the current text'
);

-- Take-down.
select lives_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000b1', 'upheld', 'inappropriate_language',
    pg_temp.approved_token('77000000-0000-4000-8000-0000000000b1'))$$,
  'a publisher takes down a reported 감상 with a reason'
);
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status || '/' || rejection_reason || '/' || decided_by::text
   from public.thoughts where id = '77000000-0000-4000-8000-0000000000b1'),
  'rejected/inappropriate_language/77000000-0000-4000-8000-000000000007',
  'a take-down rejects the 감상 with the reason'
);
select is(
  (select resolution || '/' || resolved_by::text from public.thought_reports
   where thought_id = '77000000-0000-4000-8000-0000000000b1'),
  'upheld/77000000-0000-4000-8000-000000000007',
  'a take-down closes the reports as upheld'
);
select is(
  (
    select metadata
    from content.audit_log
    where entity_type = 'thought' and entity_id = '77000000-0000-4000-8000-0000000000b1'
  ),
  jsonb_build_object(
    'status_before', 'approved', 'status_after', 'rejected', 'reason', 'inappropriate_language',
    'resolved_count', 1
  ),
  'a take-down audit records the change, reason and closed reports, never the text'
);
select is(
  (
    select metadata
    from content.audit_log
    where entity_type = 'thought' and entity_id = '77000000-0000-4000-8000-0000000000a1'
      and action = 'thought.reports_dismissed'
  ),
  jsonb_build_object('status_before', 'approved', 'status_after', 'approved', 'resolved_count', 3),
  'a dismissal audit records how many reports it closed'
);

set local role anon;
select pg_temp.act_as_anon();
select is(
  pg_temp.reader_ids('ex-077-a'),
  '77000000-0000-4000-8000-0000000000a1',
  'a taken-down 감상 is shown to no one'
);
reset role;
set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select is(
  pg_temp.reader_item('ex-077-a', '77000000-0000-4000-8000-0000000000b1'),
  null,
  'a taken-down 감상 is hidden from its reporter too'
);
select pg_temp.act_as('77000000-0000-4000-8000-000000000002');
select is(
  (select moderation_status || '/' || rejection_reason from public.thoughts
   where id = '77000000-0000-4000-8000-0000000000b1'),
  'rejected/inappropriate_language',
  'the author reads the take-down reason'
);

-- Staff cannot settle reports on their own 감상.
reset role;
select pg_temp.act_as_service();
update public.thoughts
set moderation_status = 'approved', approved_at = '2026-11-06 10:00+00'
where id = '77000000-0000-4000-8000-0000000000f1';
set local role authenticated;
select pg_temp.act_as('77000000-0000-4000-8000-000000000003');
select public.report_thought('77000000-0000-4000-8000-0000000000f1', 'promotional');
select pg_temp.act_as('77000000-0000-4000-8000-000000000007');
select throws_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000f1', 'dismissed', null,
    timestamptz '2026-11-06 10:00+00')$$,
  'PT409', 'thought_review_invalid_transition', 'a publisher cannot keep reports on their own 감상');
select pg_temp.act_as('77000000-0000-4000-8000-000000000008');
select lives_ok(
  $$select public.resolve_thought_reports('77000000-0000-4000-8000-0000000000f1', 'dismissed', null,
    timestamptz '2026-11-06 10:00+00')$$,
  'another staff member can'
);

-- A staff list never carries a diary field.
select ok(
  not exists (
    select 1
    from jsonb_array_elements(public.list_thought_review_queue() || public.list_reported_thoughts()) as item
    where item ?| array['feeling', 'tags', 'visit_date', 'note', 'decided_by', 'user_id']
  ),
  'no staff list carries a feeling, tags, visit date, private note or account id'
);
reset role;

-- ── Account and 감상 deletion ────────────────────────────────────────────────────────────────────────────────────

select pg_temp.act_as_service();
delete from auth.users where id = '77000000-0000-4000-8000-000000000004';
select is(
  (select count(*)::integer from public.thought_reports where account_id = '77000000-0000-4000-8000-000000000004'),
  0,
  'deleting a reporter deletes their reports'
);
delete from public.thoughts where id = '77000000-0000-4000-8000-0000000000a1';
select is(
  (select count(*)::integer from public.thought_reports where thought_id = '77000000-0000-4000-8000-0000000000a1'),
  0,
  'deleting a 감상 deletes its reports'
);
delete from auth.users where id = '77000000-0000-4000-8000-000000000003';
select is(
  (select count(*)::integer from public.thought_reports where account_id = '77000000-0000-4000-8000-000000000003'),
  0,
  'a reporter''s account deletion leaves none of their reports'
);
select ok(
  exists (
    select 1 from pg_constraint
    where conrelid = 'public.thought_reports'::regclass and contype = 'f' and confdeltype = 'n'
      and conkey = array[(select attnum from pg_attribute
                          where attrelid = 'public.thought_reports'::regclass and attname = 'resolved_by')]
  ),
  'resolved_by is cleared when the staff account goes'
);

select * from finish();
rollback;
