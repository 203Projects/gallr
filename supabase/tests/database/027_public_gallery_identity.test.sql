begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(8);

select has_column(
  'public',
  'exhibition_catalog_v2',
  'gallery_id',
  'the public catalogue exposes stable gallery identity'
);
select col_not_null(
  'public',
  'exhibition_catalog_v2',
  'gallery_id',
  'every public exhibition has a stable gallery identity'
);
select col_is_fk(
  'public',
  'exhibition_catalog_v2',
  'gallery_id',
  'public gallery identity references the canonical organization'
);
select has_trigger(
  'public',
  'exhibition_catalog_v2',
  'exhibition_catalog_v2_assign_gallery_identity',
  'catalogue writes assign gallery identity before checksum derivation'
);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured,
  latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured,
  updated_at, is_editors_pick, content_checksum_sha256
)
values
  (
    'gallery-identity-one', '전시 하나', 'One',
    '공개 아이디 갤러리', 'Public Identity Gallery',
    '서울', 'Seoul', '종로구', 'Jongno-gu',
    current_date, current_date + 10, false,
    null, null, '', '', '', '', false, now(), false, repeat('0', 64)
  ),
  (
    'gallery-identity-two', '전시 둘', 'Two',
    E'  공개  아이디\t갤러리 ', 'Public Identity Gallery — Branch',
    '서울', 'Seoul', '강남구', 'Gangnam-gu',
    current_date, current_date + 20, false,
    null, null, '', '', '', '', false, now(), false, repeat('0', 64)
  );

select is(
  (
    select count(distinct gallery_id)::integer
    from public.exhibition_catalog_v2
    where id in ('gallery-identity-one', 'gallery-identity-two')
  ),
  1,
  'normalized gallery-name variants resolve to one stable identity'
);
select ok(
  (
    select bool_and(catalog.gallery_id = source.gallery_id)
    from public.exhibition_catalog_v2 as catalog
    join content.gallery_catalog_sources as source
      on source.source = 'public.exhibition_catalog_v2'
     and source.source_key =
       content_private.normalize_gallery_catalog_name(catalog.venue_name_ko)
    where catalog.id in ('gallery-identity-one', 'gallery-identity-two')
  ),
  'public identity matches the private source mapping'
);
select ok(
  (
    select bool_and(
      catalog.content_checksum_sha256 =
        content_private.exhibition_catalog_v2_checksum(catalog)
    )
    from public.exhibition_catalog_v2 as catalog
    where catalog.id in ('gallery-identity-one', 'gallery-identity-two')
  ),
  'the public checksum covers stable gallery identity'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.assign_exhibition_catalog_gallery_identity()',
    'EXECUTE'
  ),
  'API users cannot invoke the identity assignment trigger directly'
);

select * from finish();
rollback;
