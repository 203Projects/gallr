-- Spec 090 curator POV notes: schema, row-level security, the public read view and the analytics kind
-- (contracts/database-functions.md). Function cases follow in the same suite.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(63);

-- Schema
select has_type('content', 'curator_note_status', 'the status enum exists');
select has_table('content', 'curator_notes', 'one row per exhibition');
select has_table('content', 'curator_note_versions', 'one row per version');
select ok((select relrowsecurity from pg_class where oid = 'content.curator_notes'::regclass), 'curator_notes has RLS');
select ok((select relrowsecurity from pg_class where oid = 'content.curator_note_versions'::regclass), 'versions have RLS');
select ok(not has_table_privilege('anon', 'content.curator_notes', 'select'), 'anon cannot read curator_notes');
select ok(not has_table_privilege('authenticated', 'content.curator_notes', 'select'), 'authenticated cannot read curator_notes');
select ok(not has_table_privilege('service_role', 'content.curator_notes', 'select'), 'service role cannot read curator_notes directly');
select ok(not has_table_privilege('anon', 'content.curator_note_versions', 'select'), 'anon cannot read versions');
select ok(not has_table_privilege('authenticated', 'content.curator_note_versions', 'select'), 'authenticated cannot read versions');
select ok(not has_table_privilege('service_role', 'content.curator_note_versions', 'select'), 'service role cannot read versions directly');

-- Fixtures: three catalogue rows, one note per status, one note whose exhibition left the catalogue.
insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  row.id, row.id, row.id, '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  seoul.today - 5, seoul.today + 30, false, 37.57, 126.98, repeat('설명 ', 60), 'An English description.',
  '', '', false, now(), false, repeat('0', 64)
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('cn-approved'), ('cn-draft'), ('cn-declined')) as row(id);

insert into content.curator_notes (exhibition_id) values ('cn-approved'), ('cn-draft'), ('cn-declined'), ('cn-missing');
insert into content.curator_note_versions (
  exhibition_id, version_number, status, hook_ko, insights_ko, tip_ko,
  producer, model, prompt_version, source_fields, source_hash, request_kind, generated_at,
  reviewed_by, reviewer_name_ko, reviewer_name_en, decided_at, approved_at, decline_reason
)
select
  row.exhibition_id, 1, row.status::content.curator_note_status, '훅', '[{"text": "본문", "grounding": ["description_ko"]}, {"text": "둘", "grounding": ["description_ko"]}]'::jsonb, '팁',
  'claude', 'claude-test', 'test.1', array['description_ko'], repeat('a', 64), 'scheduled', now(),
  null, case when row.status = 'approved' then '김지원' end, case when row.status = 'approved' then 'Jiwon Kim' end,
  case when row.status in ('approved', 'declined') then now() end, case when row.status = 'approved' then now() end,
  case when row.status = 'declined' then 'tone' end
from (values ('cn-approved', 'approved'), ('cn-draft', 'draft'), ('cn-declined', 'declined'), ('cn-missing', 'approved')) as row(exhibition_id, status);
update content.curator_notes set approved_version_id = version.id
from content.curator_note_versions as version
where version.exhibition_id = curator_notes.exhibition_id and version.status = 'approved';

select throws_ok(
  $$insert into content.curator_note_versions (
      exhibition_id, version_number, status, hook_ko, insights_ko, tip_ko,
      producer, model, prompt_version, source_fields, source_hash, request_kind, generated_at)
    values ('cn-draft', 2, 'draft', '훅', '[{"text": "본문", "grounding": ["description_ko"]}, {"text": "둘", "grounding": ["description_ko"]}]', '팁',
      'claude', 'claude-test', 'test.1', array['description_ko'], repeat('b', 64), 'scheduled', now())$$,
  '23505', null, 'an exhibition holds at most one draft'
);

-- Public read view
select ok(has_table_privilege('anon', 'public.curator_notes_published', 'select'), 'anon can read the published view');
select ok(has_table_privilege('authenticated', 'public.curator_notes_published', 'select'), 'authenticated can read the published view');
select is(
  (select array_agg(exhibition_id order by exhibition_id) from public.curator_notes_published),
  array['cn-approved'],
  'only approved notes of catalogue exhibitions are published'
);
select is(
  (select reviewer_name_ko || '/' || reviewer_name_en from public.curator_notes_published where exhibition_id = 'cn-approved'),
  '김지원/Jiwon Kim',
  'the view carries the reviewer snapshot'
);
select ok(
  not exists (select 1 from public.curator_notes_published where exhibition_id = 'cn-missing'),
  'a note leaves the view with its exhibition'
);

-- Analytics: the curator_note discovery kind is accepted by the aggregate pipeline.
select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(jsonb_build_object(
      'event_id', 'c0900000-0000-4000-8000-000000000001',
      'occurred_on', current_date::text,
      'platform', 'android',
      'app_major', 1,
      'event_name', 'exhibition_impression',
      'surface', 'featured',
      'exhibition_id', 'cn-approved',
      'discovery_kind', 'curator_note',
      'position_bucket', 'top_three'
    )),
    repeat('f', 64)
  ) ->> 'accepted',
  '1',
  'curator_note is a valid discovery kind'
);
select is(
  (select discovery_kind from content.mobile_analytics_daily where exhibition_id = 'cn-approved' and event_name = 'exhibition_impression' limit 1),
  'curator_note',
  'the aggregate row keeps the kind'
);

-- Functions (contracts/database-functions.md)
create function pg_temp.version_id(p_exhibition text, p_number integer) returns uuid
language sql security definer as $$
  select id from content.curator_note_versions where exhibition_id = p_exhibition and version_number = p_number;
$$;
create function pg_temp.revision(p_exhibition text, p_number integer) returns timestamptz
language sql security definer as $$
  select updated_at from content.curator_note_versions where exhibition_id = p_exhibition and version_number = p_number;
$$;
create function pg_temp.status(p_exhibition text, p_number integer) returns text
language sql security definer as $$
  select status::text from content.curator_note_versions where exhibition_id = p_exhibition and version_number = p_number;
$$;
create function pg_temp.packet_hash(p_exhibition text) returns text
language sql security definer as $$
  select packet_hash from content_private.curator_note_packet(p_exhibition);
$$;
create function pg_temp.draft_payload(p_exhibition text, p_grounding text, p_hash text) returns jsonb
language sql security definer as $$
  select jsonb_build_object(
    'exhibition_id', p_exhibition, 'status', 'draft',
    'hook_ko', '곰 한 마리가 동화 속으로 들어간 이유', 'hook_en', 'Why a bear walked into a fairy tale',
    'insights_ko', jsonb_build_array(
      jsonb_build_object('text', '첫 번째 통찰.', 'grounding', jsonb_build_array(p_grounding)),
      jsonb_build_object('text', '두 번째 통찰.', 'grounding', jsonb_build_array('description_ko'))
    ),
    'insights_en', '[]'::jsonb,
    'tip_ko', '복도 끝의 방부터 보라.', 'tip_en', '',
    'producer', 'claude', 'model', 'claude-test-model', 'prompt_version', '2026-10-11.1',
    'source_fields', jsonb_build_array('name_ko', 'description_ko', 'venue_name_ko', 'opening_date', 'closing_date'),
    'source_hash', coalesce(p_hash, pg_temp.packet_hash(p_exhibition)),
    'request_kind', 'scheduled', 'requested_by', null, 'generated_at', now()::text
  );
$$;
grant execute on function pg_temp.version_id(text, integer), pg_temp.revision(text, integer), pg_temp.packet_hash(text),
  pg_temp.status(text, integer), pg_temp.draft_payload(text, text, text) to service_role, authenticated;

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000d901', 'notes-publisher@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000d902', 'notes-contributor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000d903', 'notes-editor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000d904', 'notes-visitor@example.invalid', now(), false, '{}');
update public.profiles set display_name = '하신' where id = '00000000-0000-4000-8000-00000000d901';
insert into content.staff_members (user_id, role, active) values
  ('00000000-0000-4000-8000-00000000d901', 'publisher', true),
  ('00000000-0000-4000-8000-00000000d902', 'contributor', true),
  ('00000000-0000-4000-8000-00000000d903', 'publisher', true);
insert into public.editors (id, name_ko, name_en, title_ko, bio_ko, is_active)
values ('notes-editor-jiwon', '김지원', 'Jiwon Kim', '큐레이터', '소개', true);
insert into content.editor_memberships (user_id, editor_id, active)
values ('00000000-0000-4000-8000-00000000d903', 'notes-editor-jiwon', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256,
  cover_image_url, artists
)
select
  row.id, row.id, row.id, '갤러리', 'Gallery', '서울', 'Seoul', '용산구', 'Yongsan-gu',
  seoul.today - row.opened_days_ago, seoul.today + row.closes_in_days, row.featured, 37.53, 127.00,
  row.description, 'An English description.', '', '', false, now(), false, repeat('0', 64),
  row.cover, row.artists
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values
    ('cq-feat', 10, 30, true, repeat('특징 있는 설명. ', 20), 'https://cdn.example.invalid/feat.jpg',
      '[{"id": "a1", "name_ko": "사이먼 후지와라", "name_en": "Simon Fujiwara"}]'::jsonb),
    ('cq-plain', 20, 30, false, repeat('평범한 설명. ', 20), 'https://cdn.example.invalid/plain.jpg', '[]'::jsonb),
    ('cq-short', 5, 30, true, '짧은 설명', 'https://cdn.example.invalid/short.jpg', '[]'::jsonb),
    ('cq-nocover', 5, 30, true, repeat('표지 없는 설명. ', 20), null, '[]'::jsonb),
    ('cq-ended', 40, -1, true, repeat('끝난 전시 설명. ', 20), 'https://cdn.example.invalid/ended.jpg', '[]'::jsonb)
  ) as row(id, opened_days_ago, closes_in_days, featured, description, cover, artists);

select ok(not has_function_privilege('anon', 'public.service_list_curator_note_candidates(integer)', 'EXECUTE'), 'anon cannot list candidates');
select ok(not has_function_privilege('authenticated', 'public.service_store_curator_note_draft(jsonb)', 'EXECUTE'), 'authenticated cannot store drafts');
select ok(has_function_privilege('service_role', 'public.service_store_curator_note_draft(jsonb)', 'EXECUTE'), 'the service role stores drafts');
select ok(not has_function_privilege('anon', 'public.list_curator_note_queue()', 'EXECUTE'), 'anon cannot read the queue');

set local role service_role;
select is(
  (select array_agg(candidate ->> 'exhibition_id') from jsonb_array_elements(public.service_list_curator_note_candidates(50)) as candidate),
  array['cq-feat', 'cq-plain'],
  'only qualifying exhibitions are candidates, featured first'
);
select is(
  (select array_agg(candidate ->> 'exhibition_id') from jsonb_array_elements(public.service_list_curator_note_candidates(1)) as candidate),
  array['cq-feat'],
  'the limit bounds a run'
);
select ok(
  (select (candidate -> 'packet' ? 'artists') and (candidate -> 'packet' ? 'description_ko') and not (candidate -> 'packet' ? 'hours')
   from jsonb_array_elements(public.service_list_curator_note_candidates(1)) as candidate),
  'the packet carries present fields and artists, never nulls'
);
select matches(
  (select candidate ->> 'packet_hash' from jsonb_array_elements(public.service_list_curator_note_candidates(1)) as candidate),
  '^[0-9a-f]{64}$',
  'the packet hash is sha-256 hex'
);
select is(
  (select (stored ->> 'status') || '/' || (stored ->> 'version_number')
   from public.service_store_curator_note_draft(pg_temp.draft_payload('cq-feat', 'name_ko', null)) as stored),
  'draft/1',
  'a draft is stored as version 1'
);
select throws_ok(
  $$select public.service_store_curator_note_draft(pg_temp.draft_payload('cq-feat', 'name_ko', null))$$,
  'PT409', 'curator_note_invalid_transition', 'a second open draft is refused'
);
select throws_ok(
  $$select public.service_store_curator_note_draft(pg_temp.draft_payload('cq-plain', 'wikipedia', null))$$,
  '22023', 'curator_note_invalid_fields', 'a grounding key outside the packet is refused'
);
select throws_ok(
  $$select public.service_store_curator_note_draft(pg_temp.draft_payload('cq-plain', 'name_ko', repeat('c', 64)))$$,
  'PT409', 'curator_note_source_changed', 'a draft from a changed packet is refused'
);
select is(
  (select stored ->> 'status' from public.service_store_curator_note_draft(
     pg_temp.draft_payload('cq-plain', 'name_ko', null) || jsonb_build_object('status', 'unusable', 'rejection_code', 'parse_failed')
   ) as stored),
  'unusable',
  'a failed draft is stored as unusable'
);
select is(
  (select stored ->> 'version_number' from public.service_store_curator_note_draft(pg_temp.draft_payload('cq-plain', 'name_ko', null)) as stored),
  '2',
  'the next draft takes the next version number'
);
select is(public.service_list_curator_note_candidates(50), '[]'::jsonb, 'exhibitions with an open draft are not candidates');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d904', true);
select throws_ok($$select public.list_curator_note_queue()$$, '42501', 'curator_note_not_staff', 'a visitor cannot read the queue');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d902', true);
select throws_ok($$select public.list_curator_note_queue()$$, '42501', 'curator_note_not_staff', 'a contributor cannot read the queue');

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d901', true);
select is(
  (select array_agg(item.value ->> 'exhibition_id' order by item.ordinality) from jsonb_array_elements(public.list_curator_note_queue()) with ordinality as item(value, ordinality)),
  array['cq-feat', 'cn-draft', 'cq-plain'],
  'the queue lists open drafts, featured first then oldest first'
);
select ok(
  (select jsonb_array_length(note -> 'versions') = 1 and (note -> 'packet') is not null and (note ->> 'qualifies')::boolean
   from public.get_curator_note('cq-feat') as note),
  'get returns the versions and the current packet'
);
select throws_ok(
  $$select public.save_curator_note_edit(pg_temp.version_id('cq-feat', 1), '{"hook_ko": "바뀐 훅"}', now() - interval '1 day')$$,
  'PT409', 'curator_note_stale', 'an edit against an old revision is refused'
);
select is(
  (select (saved ->> 'hook_ko') || '/' || (saved ->> 'edited')
   from public.save_curator_note_edit(pg_temp.version_id('cq-feat', 1), '{"hook_ko": "바뀐 훅"}', pg_temp.revision('cq-feat', 1)) as saved),
  '바뀐 훅/true',
  'an edit changes the text and marks the version edited'
);
select throws_ok(
  $$select public.save_curator_note_edit(pg_temp.version_id('cq-feat', 1), '{"status": "approved"}', pg_temp.revision('cq-feat', 1))$$,
  '22023', 'curator_note_invalid_fields', 'only text fields can be edited'
);
select is(
  (select marked -> 'unsupported_refs'
   from public.mark_curator_note_sentence(pg_temp.version_id('cq-feat', 1), 'ko:insight:0', true, pg_temp.revision('cq-feat', 1)) as marked),
  '["ko:insight:0"]'::jsonb,
  'a sentence can be marked unsupported'
);
select throws_ok(
  $$select public.approve_curator_note(pg_temp.version_id('cq-feat', 1), pg_temp.revision('cq-feat', 1))$$,
  'PT409', 'curator_note_unsupported', 'approval is refused while a sentence is unsupported'
);
select is(
  (select marked -> 'unsupported_refs'
   from public.mark_curator_note_sentence(pg_temp.version_id('cq-feat', 1), 'ko:insight:0', false, pg_temp.revision('cq-feat', 1)) as marked),
  '[]'::jsonb,
  'the marking can be cleared'
);
select is(
  (select (approved ->> 'status') || '/' || (approved ->> 'reviewer_name_ko') || '/' || (approved ->> 'reviewer_name_en')
   from public.approve_curator_note(pg_temp.version_id('cq-feat', 1), pg_temp.revision('cq-feat', 1)) as approved),
  'approved/하신/하신',
  'approval publishes under the account display name when no editor profile exists'
);
select is(
  (select version_number from public.curator_notes_published where exhibition_id = 'cq-feat'),
  1,
  'the approved version is readable through the view'
);
select throws_ok(
  $$select public.approve_curator_note(pg_temp.version_id('cq-feat', 1), pg_temp.revision('cq-feat', 1))$$,
  'PT409', 'curator_note_invalid_transition', 'an approved version cannot be approved again'
);

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d903', true);
select is(
  (select (approved ->> 'reviewer_name_ko') || '/' || (approved ->> 'reviewer_name_en')
   from public.approve_curator_note(pg_temp.version_id('cq-plain', 2), pg_temp.revision('cq-plain', 2)) as approved),
  '김지원/Jiwon Kim',
  'approval uses the editor profile name when the reviewer has one'
);

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d901', true);
select matches(
  (select requested ->> 'packet_hash' from public.request_curator_note_draft('cq-feat') as requested),
  '^[0-9a-f]{64}$',
  'a staff request returns the packet for the function'
);
reset role;
set local role service_role;
select is(
  (select stored ->> 'version_number' from public.service_store_curator_note_draft(
     pg_temp.draft_payload('cq-feat', 'name_ko', null) || '{"request_kind": "regenerate"}'::jsonb) as stored),
  '2',
  'a requested draft becomes the next version'
);
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d901', true);
select is(
  (select approved ->> 'status' from public.approve_curator_note(pg_temp.version_id('cq-feat', 2), pg_temp.revision('cq-feat', 2)) as approved),
  'approved',
  'a newer version can be approved'
);
select is(
  (select pg_temp.status('cq-feat', 1) || '/' || (select version_number from public.curator_notes_published where exhibition_id = 'cq-feat')::text),
  'superseded/2',
  'the earlier approval is superseded and the view shows the newest'
);
select ok(
  (select (requested ->> 'exhibition_id') = 'cq-plain' from public.request_curator_note_draft('cq-plain') as requested),
  'a request for a published exhibition clears the way for a fresh draft'
);
reset role;
set local role service_role;
select is(
  (select stored ->> 'version_number' from public.service_store_curator_note_draft(
     pg_temp.draft_payload('cq-plain', 'name_ko', null) || '{"request_kind": "regenerate"}'::jsonb) as stored),
  '3',
  'the fresh draft is version 3'
);
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d901', true);
select throws_ok(
  $$select public.decline_curator_note(pg_temp.version_id('cq-plain', 3), 'bogus', null, pg_temp.revision('cq-plain', 3))$$,
  '22023', 'curator_note_invalid_reason', 'a decline needs a known reason'
);
select is(
  (select declined ->> 'status' from public.decline_curator_note(pg_temp.version_id('cq-plain', 3), 'tone', '너무 홍보 같아요', pg_temp.revision('cq-plain', 3)) as declined),
  'declined',
  'a draft can be declined with a reason'
);
select ok(
  (select (note ->> 'auto_draft_blocked')::boolean from public.get_curator_note('cq-plain') as note),
  'a decline blocks automatic drafts'
);
select ok((public.request_curator_note_draft('cq-plain') ->> 'exhibition_id') = 'cq-plain', 'a staff request is accepted after a decline');
select ok(not (public.get_curator_note('cq-plain') ->> 'auto_draft_blocked')::boolean, 'a staff request lifts the block');
select is(
  (select exported ->> 'status' from public.record_curator_note_export(pg_temp.version_id('cq-feat', 2), 'ko', 5) as exported),
  'approved',
  'an export of an approved version is recorded'
);
select throws_ok(
  $$select public.record_curator_note_export(pg_temp.version_id('cq-plain', 3), 'ko', 5)$$,
  'PT409', 'curator_note_invalid_transition', 'only approved versions can be exported'
);
reset role;
select is(
  (select array_agg(action || ':' || total order by action)
   from (select action, count(*) as total from content.audit_log where entity_type = 'curator_note'
         and action in ('curator_note_approved', 'curator_note_declined', 'curator_note_exported') group by action) as tally),
  array['curator_note_approved:3', 'curator_note_declined:1', 'curator_note_exported:1'],
  'review actions write audit rows'
);
select ok(exists (select 1 from cron.job where jobname = 'curator_notes_daily'), 'the daily drafting job is scheduled');

select * from finish();
rollback;
