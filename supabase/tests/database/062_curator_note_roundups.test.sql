-- Spec 090 roundup carousels: staff list the approved notes of running exhibitions with the facts a roundup
-- prints, and record a roundup export against every note in it.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(28);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, cover_image_url, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  row.id, row.id || ' 전시', row.id || ' show', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  seoul.today - 40, seoul.today + row.closes_in, false, 37.57, 126.98, repeat('설명 ', 60), 'An English description.',
  '', '', 'https://example.invalid/' || row.id || '.jpg', false, now(), false, repeat('0', 64)
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('ru-later', 20), ('ru-soon', 3), ('ru-ended', -1), ('ru-draft', 9), ('ru-today', 0)) as row(id, closes_in);

insert into content.curator_notes (exhibition_id) values ('ru-later'), ('ru-soon'), ('ru-ended'), ('ru-draft'), ('ru-today');
insert into content.curator_note_versions (
  exhibition_id, version_number, status, hook_ko, hook_en, insights_ko, tip_ko,
  producer, model, prompt_version, source_fields, source_hash, request_kind, generated_at,
  reviewer_name_ko, reviewer_name_en, decided_at, approved_at
)
select
  row.exhibition_id, 1, row.status::content.curator_note_status, row.exhibition_id || ' 훅', row.exhibition_id || ' hook',
  '[{"text": "본문", "grounding": ["description_ko"]}, {"text": "둘", "grounding": ["description_ko"]}]'::jsonb, '팁',
  'claude', 'claude-test', 'test.1', array['description_ko'], repeat('a', 64), 'scheduled', now() - interval '1 day',
  case when row.status = 'approved' then '김지원' end, case when row.status = 'approved' then 'Jiwon Kim' end,
  case when row.status = 'approved' then now() end, case when row.status = 'approved' then now() end
from (values ('ru-later', 'approved'), ('ru-soon', 'approved'), ('ru-ended', 'approved'), ('ru-draft', 'draft'), ('ru-today', 'approved'))
  as row(exhibition_id, status);
update content.curator_notes set approved_version_id = version.id
from content.curator_note_versions as version
where version.exhibition_id = curator_notes.exhibition_id and version.status = 'approved';

-- One exhibition published through the content tables, with a cover and two gallery images (one purged).
insert into content.exhibitions (id, created_at, updated_at) values ('ru-images', now(), now());
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, event_id, editor_id,
  name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en, address_ko, address_en,
  opening_date, closing_date, latitude, longitude, description_ko, description_en,
  hours, contact, ticket_url, legacy_cover_image_url, is_featured, is_homepage_featured, published_at, created_at, updated_at
) values (
  '00000000-0000-4000-8000-00000000e801'::uuid, 'ru-images', 1, 1, 'published', null, null,
  '이미지 전시', 'Image show', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
  (now() at time zone 'Asia/Seoul')::date - 10, (now() at time zone 'Asia/Seoul')::date + 10, 37.57, 126.98,
  repeat('설명 ', 60), 'An English description.', null, null, null, null, false, false, now(), now(), now()
);
update content.exhibitions set published_version_id = '00000000-0000-4000-8000-00000000e801'::uuid where id = 'ru-images';
insert into content.media_assets (
  id, status, bucket_id, object_path, delivery_bucket_id, delivery_object_path, public_url,
  mime_type, byte_size, width, height, checksum_sha256, published_at, purged_at, credit, alt_ko, alt_en
)
select
  row.id::uuid, 'published', 'exhibition-media', 'drafts/ru-images/' || row.id || '/original.jpg',
  'exhibition-images', 'cms/' || row.id || '/original.jpg', 'https://cdn.example.invalid/' || row.name || '.jpg',
  'image/jpeg', 128, 16, 8, repeat(row.fill, 64), now(), case when row.name = 'purged' then now() end,
  case when row.name = 'view-one' then '사진 김지원' else '' end,
  case when row.name = 'view-one' then '전시 전경' else '' end,
  case when row.name = 'view-one' then 'Installation view' else '' end
from (values
  ('00000000-0000-4000-8000-00000000e811', 'cover', 'a'),
  ('00000000-0000-4000-8000-00000000e812', 'view-one', 'b'),
  ('00000000-0000-4000-8000-00000000e813', 'view-two', 'c'),
  ('00000000-0000-4000-8000-00000000e814', 'purged', 'd')
) as row(id, name, fill);
-- An attachment takes its alt text and credit from the asset when it is created.
insert into content.exhibition_version_media (version_id, media_id, role, sort_order) values
  ('00000000-0000-4000-8000-00000000e801'::uuid, '00000000-0000-4000-8000-00000000e811'::uuid, 'cover', 0),
  ('00000000-0000-4000-8000-00000000e801'::uuid, '00000000-0000-4000-8000-00000000e813'::uuid, 'gallery', 2),
  ('00000000-0000-4000-8000-00000000e801'::uuid, '00000000-0000-4000-8000-00000000e812'::uuid, 'gallery', 1),
  ('00000000-0000-4000-8000-00000000e801'::uuid, '00000000-0000-4000-8000-00000000e814'::uuid, 'gallery', 3);
insert into content.curator_notes (exhibition_id) values ('ru-images');

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000e901', 'roundup-publisher@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000e902', 'roundup-contributor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000e904', 'roundup-visitor@example.invalid', now(), false, '{}');
insert into content.staff_members (user_id, role, active) values
  ('00000000-0000-4000-8000-00000000e901', 'publisher', true),
  ('00000000-0000-4000-8000-00000000e902', 'contributor', true);

create function pg_temp.version_id(p_exhibition text) returns uuid
language sql stable security definer set search_path = ''
as $$ select id from content.curator_note_versions where exhibition_id = p_exhibition and version_number = 1 $$;
create function pg_temp.roundup_audit_count() returns bigint
language sql stable security definer set search_path = ''
as $$ select count(*) from content.audit_log where action = 'curator_note_roundup_exported' $$;
create function pg_temp.roundup_audit(p_exhibition text) returns jsonb
language sql stable security definer set search_path = ''
as $$ select metadata from content.audit_log where action = 'curator_note_roundup_exported' and entity_id = p_exhibition $$;
grant execute on function pg_temp.version_id(text), pg_temp.roundup_audit_count(), pg_temp.roundup_audit(text) to authenticated;

-- Privileges
select ok(not has_function_privilege('anon', 'public.list_approved_curator_notes()', 'execute'), 'anon cannot list approved notes');
select ok(has_function_privilege('authenticated', 'public.list_approved_curator_notes()', 'execute'), 'signed-in callers reach the staff check');
select ok(
  not has_function_privilege('anon', 'public.record_curator_note_roundup_export(uuid[], text, integer)', 'execute'),
  'anon cannot record a roundup export'
);
select ok(
  not has_function_privilege('service_role', 'public.record_curator_note_roundup_export(uuid[], text, integer)', 'execute'),
  'the service role does not record roundup exports'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000e904', true);
select throws_ok($$select public.list_approved_curator_notes()$$, '42501', 'curator_note_not_staff', 'a visitor cannot list approved notes');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000e902', true);
select throws_ok($$select public.list_approved_curator_notes()$$, '42501', 'curator_note_not_staff', 'a contributor cannot list approved notes');
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-later')], 'ko', 5)$$,
  '42501', 'curator_note_not_staff', 'a contributor cannot record a roundup export'
);

-- The list
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000e901', true);
select is(
  (select array_agg(item ->> 'exhibition_id' order by position)
   from jsonb_array_elements(public.list_approved_curator_notes()) with ordinality as listed(item, position)),
  array['ru-today', 'ru-soon', 'ru-later'],
  'approved notes of exhibitions still running, soonest closing first; ended exhibitions and drafts are left out'
);
select is(
  (select item - 'approved_at' - 'opening_date' - 'closing_date'
   from jsonb_array_elements(public.list_approved_curator_notes()) as listed(item) where item ->> 'exhibition_id' = 'ru-soon'),
  jsonb_build_object(
    'version_id', pg_temp.version_id('ru-soon'), 'exhibition_id', 'ru-soon', 'version_number', 1,
    'hook_ko', 'ru-soon 훅', 'hook_en', 'ru-soon hook', 'name_ko', 'ru-soon 전시', 'name_en', 'ru-soon show',
    'venue_name_ko', '갤러리', 'venue_name_en', 'Gallery', 'region_ko', '종로구', 'region_en', 'Jongno-gu',
    'cover_image_url', 'https://example.invalid/ru-soon.jpg'
  ),
  'a row carries the hook and the facts a roundup slide prints'
);
select is(
  (select (item ->> 'closing_date')::date - (item ->> 'opening_date')::date
   from jsonb_array_elements(public.list_approved_curator_notes()) as listed(item) where item ->> 'exhibition_id' = 'ru-soon'),
  43,
  'a row carries the opening and closing dates'
);

-- The single-note export's picture
select is(
  public.get_curator_note('ru-soon') ->> 'cover_image_url',
  'https://example.invalid/ru-soon.jpg',
  'the staff read of one note carries the cover the carousel draws'
);
select ok(
  not (public.get_curator_note('ru-soon') -> 'packet') ? 'cover_image_url',
  'the cover stays out of the facts packet, so the packet hash is unchanged'
);
select is(
  public.get_curator_note('ru-images') -> 'images',
  '[
    {"url": "https://cdn.example.invalid/cover.jpg", "role": "cover", "credit": "", "alt_ko": "", "alt_en": ""},
    {"url": "https://cdn.example.invalid/view-one.jpg", "role": "gallery", "credit": "사진 김지원", "alt_ko": "전시 전경", "alt_en": "Installation view"},
    {"url": "https://cdn.example.invalid/view-two.jpg", "role": "gallery", "credit": "", "alt_ko": "", "alt_en": ""}
  ]'::jsonb,
  'the staff read carries the published version''s images, cover first then the gallery in order, without purged ones'
);
select is(
  public.get_curator_note('ru-images') ->> 'cover_image_url',
  'https://cdn.example.invalid/cover.jpg',
  'the cover address matches the catalogue''s'
);
select is(public.get_curator_note('ru-soon') -> 'images', '[]'::jsonb, 'an exhibition with only a catalogue cover has no image list');
select ok(
  not has_function_privilege('anon', 'content_private.curator_note_images(text)', 'execute'),
  'anon cannot read an exhibition''s image list'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000e904', true);
select throws_ok($$select public.get_curator_note('ru-soon')$$, '42501', 'curator_note_not_staff', 'a visitor still cannot read a note''s facts');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000e901', true);

-- Recording a roundup export
select is(
  public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-later')], 'ko', 5),
  2,
  'a publisher records a roundup of two approved notes'
);
select is(pg_temp.roundup_audit_count(), 2::bigint, 'one audit row per note in the roundup');
select is(
  pg_temp.roundup_audit('ru-later') - 'version_id',
  '{"language": "ko", "slide_count": 5, "roundup_size": 2, "position": 2}'::jsonb,
  'the audit row says where the note sat in the roundup'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon')], 'ko', 4)$$,
  '22023', 'curator_note_invalid_fields', 'a roundup needs at least two notes'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-soon')], 'ko', 5)$$,
  '22023', 'curator_note_invalid_fields', 'a note appears once in a roundup'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-later')], 'fr', 5)$$,
  '22023', 'curator_note_invalid_fields', 'the language is ko or en'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-later')], 'ko', 3)$$,
  '22023', 'curator_note_invalid_fields', 'the slide count covers a cover, one slide per note and an index'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(
      (select array_agg(gen_random_uuid()) from generate_series(1, 11)), 'ko', 13)$$,
  '22023', 'curator_note_invalid_fields', 'a roundup holds at most ten notes'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), gen_random_uuid()], 'ko', 5)$$,
  'P0002', 'curator_note_not_found', 'an unknown version is refused'
);
select throws_ok(
  $$select public.record_curator_note_roundup_export(array[pg_temp.version_id('ru-soon'), pg_temp.version_id('ru-draft')], 'ko', 5)$$,
  'PT409', 'curator_note_invalid_transition', 'a note that is not approved cannot go in a roundup'
);
select is(pg_temp.roundup_audit_count(), 2::bigint, 'a refused roundup records nothing');

select * from finish();
rollback;
