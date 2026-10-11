-- Spec 090 curator POV notes: schema, row-level security, the public read view and the analytics kind
-- (contracts/database-functions.md). Function cases follow in the same suite.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(19);

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

select * from finish();
rollback;
