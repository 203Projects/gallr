-- Migration 20261013120000: the published-notes view runs with the caller's rights over a security definer
-- function, so the security advisor no longer reports a SECURITY DEFINER view and readers still get approved
-- notes without any access to the private tables.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(8);

select ok(
  (select 'security_invoker=true' = any (reloptions) from pg_class where oid = 'public.curator_notes_published'::regclass),
  'the view runs with the caller''s rights'
);
select has_function('content_private', 'published_curator_notes', array[]::text[], 'the view body is a function');
select ok(has_function_privilege('anon', 'content_private.published_curator_notes()', 'EXECUTE'), 'anon may call the view body');
select ok(has_table_privilege('anon', 'public.curator_notes_published', 'select'), 'anon can read the view');
select ok(not has_table_privilege('anon', 'content.curator_note_versions', 'select'), 'anon still cannot read the versions');
select ok(not has_table_privilege('anon', 'content.curator_notes', 'select'), 'anon still cannot read the notes');

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  'cv-approved', 'cv-approved', 'cv-approved', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  seoul.today - 5, seoul.today + 30, false, 37.57, 126.98, repeat('설명 ', 60), 'An English description.',
  '', '', false, now(), false, repeat('0', 64)
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul;
insert into content.curator_notes (exhibition_id) values ('cv-approved'), ('cv-draft-only');
insert into content.curator_note_versions (
  exhibition_id, version_number, status, hook_ko, insights_ko, tip_ko,
  producer, model, prompt_version, source_fields, source_hash, request_kind, generated_at,
  reviewer_name_ko, reviewer_name_en, decided_at, approved_at
)
values
  ('cv-approved', 1, 'approved', '훅', '[{"text": "본문", "grounding": ["description_ko"]}, {"text": "둘", "grounding": ["description_ko"]}]'::jsonb, '팁',
   'claude', 'claude-test', 'test.1', array['description_ko'], repeat('a', 64), 'scheduled', now(), '김지원', 'Jiwon Kim', now(), now()),
  ('cv-draft-only', 1, 'draft', '훅', '[{"text": "본문", "grounding": ["description_ko"]}, {"text": "둘", "grounding": ["description_ko"]}]'::jsonb, '팁',
   'claude', 'claude-test', 'test.1', array['description_ko'], repeat('b', 64), 'scheduled', now(), null, null, null, null);
update content.curator_notes set approved_version_id = version.id
from content.curator_note_versions as version
where version.exhibition_id = curator_notes.exhibition_id and version.status = 'approved';

set local role anon;
select is(
  (select string_agg(exhibition_id || ':' || reviewer_name_ko, ' ') from public.curator_notes_published),
  'cv-approved:김지원', 'anon reads the approved note of a catalogue exhibition through the view'
);
select throws_ok($$select count(*) from content.curator_note_versions$$, '42501', null, 'anon cannot read the versions directly');
reset role;

select * from finish();
