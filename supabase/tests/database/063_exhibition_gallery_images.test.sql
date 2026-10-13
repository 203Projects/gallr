-- Spec 090 pictures in the curator's note: the public read of an exhibition's gallery images.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(11);

-- Two published exhibitions (one later hidden from the catalogue by archiving) and one draft-only exhibition.
insert into content.exhibitions (id, created_at, updated_at) values
  ('gi-open', now(), now()), ('gi-archived', now(), now()), ('gi-draft', now(), now());
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, event_id, editor_id,
  name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en, address_ko, address_en,
  opening_date, closing_date, latitude, longitude, description_ko, description_en,
  hours, contact, ticket_url, legacy_cover_image_url, is_featured, is_homepage_featured, published_at, created_at, updated_at
)
select
  row.version_id::uuid, row.exhibition_id, 1, 1, row.status::content.exhibition_version_status, null, null,
  row.exhibition_id || ' 전시', row.exhibition_id || ' show', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
  (now() at time zone 'Asia/Seoul')::date - 10, (now() at time zone 'Asia/Seoul')::date + 10, 37.57, 126.98,
  repeat('설명 ', 60), 'An English description.', null, null, null, null, false, false,
  case when row.status = 'published' then now() end, now(), now()
from (values
  ('00000000-0000-4000-8000-00000000f801', 'gi-open', 'published'),
  ('00000000-0000-4000-8000-00000000f802', 'gi-archived', 'published'),
  ('00000000-0000-4000-8000-00000000f803', 'gi-draft', 'draft')
) as row(version_id, exhibition_id, status);
update content.exhibitions set published_version_id = '00000000-0000-4000-8000-00000000f801'::uuid where id = 'gi-open';
update content.exhibitions set published_version_id = '00000000-0000-4000-8000-00000000f802'::uuid where id = 'gi-archived';

insert into content.media_assets (
  id, status, bucket_id, object_path, delivery_bucket_id, delivery_object_path, public_url,
  mime_type, byte_size, width, height, checksum_sha256, published_at, purged_at, credit, alt_ko, alt_en
)
select
  row.id::uuid, 'published', 'exhibition-media', 'drafts/gi/' || row.id || '/original.jpg',
  'exhibition-images', 'cms/' || row.id || '/original.jpg', 'https://cdn.example.invalid/' || row.name || '.jpg',
  'image/jpeg', 128, row.width, row.height, repeat(row.fill, 64), now(), case when row.name = 'purged' then now() end,
  case when row.name = 'view-one' then '사진 김지원' else '' end,
  case when row.name = 'view-one' then '전시 전경' else '' end,
  case when row.name = 'view-one' then 'Installation view' else '' end
from (values
  ('00000000-0000-4000-8000-00000000f811', 'cover', 'a', 1200, 800),
  ('00000000-0000-4000-8000-00000000f812', 'view-one', 'b', 1600, 1000),
  ('00000000-0000-4000-8000-00000000f813', 'view-two', 'c', 900, 1200),
  ('00000000-0000-4000-8000-00000000f814', 'purged', 'd', 1200, 800),
  ('00000000-0000-4000-8000-00000000f815', 'archived-view', 'e', 1200, 800),
  ('00000000-0000-4000-8000-00000000f816', 'draft-view', 'f', 1200, 800)
) as row(id, name, fill, width, height);
insert into content.exhibition_version_media (version_id, media_id, role, sort_order) values
  ('00000000-0000-4000-8000-00000000f801'::uuid, '00000000-0000-4000-8000-00000000f811'::uuid, 'cover', 0),
  ('00000000-0000-4000-8000-00000000f801'::uuid, '00000000-0000-4000-8000-00000000f813'::uuid, 'gallery', 2),
  ('00000000-0000-4000-8000-00000000f801'::uuid, '00000000-0000-4000-8000-00000000f812'::uuid, 'gallery', 1),
  ('00000000-0000-4000-8000-00000000f801'::uuid, '00000000-0000-4000-8000-00000000f814'::uuid, 'gallery', 3),
  ('00000000-0000-4000-8000-00000000f802'::uuid, '00000000-0000-4000-8000-00000000f815'::uuid, 'gallery', 1),
  ('00000000-0000-4000-8000-00000000f803'::uuid, '00000000-0000-4000-8000-00000000f816'::uuid, 'gallery', 1);
update content.exhibitions set archived_at = now() where id = 'gi-archived';

select ok(has_table_privilege('anon', 'public.exhibition_gallery_images', 'select'), 'anon can read the gallery images');
select ok(has_table_privilege('authenticated', 'public.exhibition_gallery_images', 'select'), 'signed-in readers can read them');
select ok(not has_table_privilege('anon', 'public.exhibition_gallery_images', 'insert'), 'nobody writes through the view');
select ok(not has_table_privilege('anon', 'content.exhibition_version_media', 'select'), 'the attachments themselves stay private');
select ok(
  (select 'security_invoker=true' = any (reloptions) from pg_class where oid = 'public.exhibition_gallery_images'::regclass),
  'the view runs with the caller''s rights'
);
select ok(
  exists (select 1 from public.exhibition_catalog_v2 where id = 'gi-open')
    and not exists (select 1 from public.exhibition_catalog_v2 where id = 'gi-archived'),
  'the fixture has one exhibition in the catalogue and one archived out of it'
);

set local role anon;
select is(
  (select jsonb_agg(to_jsonb(image) - 'exhibition_id' order by image."position") from public.exhibition_gallery_images as image where exhibition_id = 'gi-open'),
  '[
    {"position": 1, "url": "https://cdn.example.invalid/view-one.jpg", "alt_ko": "전시 전경", "alt_en": "Installation view", "credit": "사진 김지원", "width": 1600, "height": 1000},
    {"position": 2, "url": "https://cdn.example.invalid/view-two.jpg", "alt_ko": "", "alt_en": "", "credit": "", "width": 900, "height": 1200}
  ]'::jsonb,
  'a reader gets the gallery in staff order with alt text, credit and size, without the cover or a purged image'
);
select is(
  (select count(*) from public.exhibition_gallery_images where exhibition_id = 'gi-archived'),
  0::bigint,
  'an exhibition that left the catalogue shows no images'
);
select is(
  (select count(*) from public.exhibition_gallery_images where exhibition_id = 'gi-draft'),
  0::bigint,
  'a draft''s images are not public'
);
select is(
  (select count(*) from public.exhibition_gallery_images where url like '%cover%' or url like '%purged%' or url like '%draft%'),
  0::bigint,
  'no cover, purged or draft image appears anywhere in the view'
);
reset role;
select is(
  (select count(distinct exhibition_id) from public.exhibition_gallery_images where exhibition_id like 'gi-%'),
  1::bigint,
  'only the published catalogue exhibition is listed'
);

select * from finish();
rollback;
