begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(20);

select has_table(
  'content',
  'gallery_catalog_sources',
  'catalogue gallery identities retain private source provenance'
);
select has_function(
  'content_private',
  'normalize_gallery_catalog_name',
  array['text'],
  'catalogue gallery names have one deterministic normalizer'
);
select has_function(
  'content_private',
  'sync_gallery_from_catalog',
  array['text', 'text'],
  'catalogue gallery identities have one private idempotent sync entry point'
);
select has_trigger(
  'public',
  'exhibition_catalog_v2',
  'exhibition_catalog_v2_sync_gallery_directory',
  'canonical catalogue changes maintain the gallery directory'
);
select ok(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'gallery_catalog_sources'
  ),
  'catalogue source provenance has RLS enabled'
);
select ok(
  not has_table_privilege(
    'authenticated',
    'content.gallery_catalog_sources',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'service_role',
    'content.gallery_catalog_sources',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'API roles receive no generic catalogue-source privileges'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.sync_gallery_from_catalog(text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'content_private.sync_gallery_from_catalog(text,text)',
    'EXECUTE'
  ),
  'catalogue sync cannot be invoked directly by API roles'
);
select is(
  content_private.normalize_gallery_catalog_name(
    E'  Directory  Test\tGallery  '
  ),
  'directory test gallery',
  'normalization trims, case-folds, and collapses whitespace'
);

insert into public.exhibition_catalog_v2 (
  id,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  opening_date,
  closing_date,
  is_featured,
  latitude,
  longitude,
  description_ko,
  description_en,
  address_ko,
  address_en,
  is_homepage_featured,
  updated_at,
  is_editors_pick,
  content_checksum_sha256
)
values
  (
    'directory-test-one',
    '디렉터리 전시 하나',
    'Directory Exhibition One',
    '디렉터리 테스트 갤러리',
    'Directory Test Gallery',
    '서울',
    'Seoul',
    '서울',
    'Seoul',
    current_date,
    current_date + 10,
    false,
    37.5001,
    127.0001,
    '',
    '',
    '서울특별시 용산구 테스트로 1',
    '',
    false,
    now(),
    false,
    repeat('0', 64)
  ),
  (
    'directory-test-two',
    '디렉터리 전시 둘',
    'Directory Exhibition Two',
    E'  디렉터리   테스트\t갤러리 ',
    'Directory Test Gallery — Busan',
    '부산',
    'Busan',
    '부산',
    'Busan',
    current_date,
    current_date + 20,
    false,
    35.1001,
    129.0001,
    '',
    '',
    '부산광역시 해운대구 테스트로 2',
    '',
    false,
    now() + interval '1 second',
    false,
    repeat('0', 64)
  );

select is(
  (
    select count(*)::integer
    from content.gallery_catalog_sources as source
    where source.source = 'public.exhibition_catalog_v2'
      and source.source_key = '디렉터리 테스트 갤러리'
  ),
  1,
  'spacing variants share one catalogue source identity'
);
select is(
  (
    select count(*)::integer
    from content.galleries as gallery
    where content_private.normalize_gallery_catalog_name(gallery.name_ko) =
      '디렉터리 테스트 갤러리'
  ),
  1,
  'multiple catalogue rows create one gallery organization'
);
select is(
  (
    select gallery.status::text
    from content.galleries as gallery
    join content.gallery_catalog_sources as source
      on source.gallery_id = gallery.id
    where source.source = 'public.exhibition_catalog_v2'
      and source.source_key = '디렉터리 테스트 갤러리'
  ),
  'active',
  'catalogue-backed gallery organizations are searchable immediately'
);
select is(
  (
    select gallery.canonical_venue_id
    from content.galleries as gallery
    join content.gallery_catalog_sources as source
      on source.gallery_id = gallery.id
    where source.source = 'public.exhibition_catalog_v2'
      and source.source_key = '디렉터리 테스트 갤러리'
  ),
  null::uuid,
  'organization sync does not collapse multiple locations into one venue'
);

select content_private.sync_gallery_from_catalog(
  '디렉터리 테스트 갤러리',
  'Directory Test Gallery'
);
select is(
  (
    select count(*)::integer
    from content.galleries as gallery
    where content_private.normalize_gallery_catalog_name(gallery.name_ko) =
      '디렉터리 테스트 갤러리'
  ),
  1,
  'explicit reconciliation is idempotent'
);

insert into content.galleries (
  id,
  name_ko,
  name_en,
  status
)
values (
  '82100000-0000-0000-0000-000000000001'::uuid,
  '기존 등록 갤러리',
  'Existing Pending Gallery',
  'pending'::content.gallery_status
);

insert into public.exhibition_catalog_v2 (
  id,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  opening_date,
  closing_date,
  is_featured,
  description_ko,
  description_en,
  address_ko,
  address_en,
  is_homepage_featured,
  updated_at,
  is_editors_pick,
  content_checksum_sha256
)
values (
  'directory-test-existing',
  '기존 등록 전시',
  'Existing Directory Exhibition',
  '기존 등록 갤러리',
  'Existing Pending Gallery',
  '서울',
  'Seoul',
  '서울',
  'Seoul',
  current_date,
  current_date + 10,
  false,
  '',
  '',
  '서울특별시 종로구 테스트로 3',
  '',
  false,
  now(),
  false,
  repeat('0', 64)
);

select is(
  (
    select source.gallery_id
    from content.gallery_catalog_sources as source
    where source.source = 'public.exhibition_catalog_v2'
      and source.source_key = '기존 등록 갤러리'
  ),
  '82100000-0000-0000-0000-000000000001'::uuid,
  'catalogue reconciliation reuses an exact pending organization'
);
select is(
  (
    select gallery.status::text
    from content.galleries as gallery
    where gallery.id = '82100000-0000-0000-0000-000000000001'::uuid
  ),
  'active',
  'catalogue evidence activates the organization without approving its owner'
);

insert into auth.users (
  id,
  email,
  email_confirmed_at,
  raw_user_meta_data
)
values (
  '00000000-0000-0000-0000-000000000821'::uuid,
  'directory-owner@example.invalid',
  now(),
  '{}'::jsonb
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000821","role":"authenticated"}',
  true
);

select is(
  (
    select count(*)::integer
    from public.owner_search_galleries('디렉터리 테스트')
  ),
  1,
  'owners can find a catalogue-backed gallery by Korean prefix'
);
select is(
  (
    select count(*)::integer
    from public.owner_search_galleries('Directory Test')
  ),
  1,
  'owners can find a catalogue-backed gallery by English prefix'
);
select is(
  (
    select (search_result ->> 'is_claimed')::boolean
    from public.owner_search_galleries('디렉터리 테스트') as search_result
  ),
  false,
  'a synced gallery remains available to claim until an owner is approved'
);

reset role;

delete from public.exhibition_catalog_v2
where id in ('directory-test-one', 'directory-test-two');

select is(
  (
    select count(*)::integer
    from content.galleries as gallery
    join content.gallery_catalog_sources as source
      on source.gallery_id = gallery.id
    where source.source = 'public.exhibition_catalog_v2'
      and source.source_key = '디렉터리 테스트 갤러리'
      and gallery.status = 'active'::content.gallery_status
  ),
  1,
  'catalogue removal does not delete a durable customer organization'
);
select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'content'
      and indexname = 'gallery_catalog_sources_gallery_idx'
  ),
  1,
  'source-to-gallery relinking is indexed'
);

select * from finish();
rollback;
