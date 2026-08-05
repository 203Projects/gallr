begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(4);

select is(
  (
    select count(*)::integer
    from pg_trigger as trigger
    join pg_class as relation on relation.oid = trigger.tgrelid
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'exhibition_versions'
      and trigger.tgname in (
        'exhibition_versions_require_approved_location_on_insert',
        'exhibition_versions_require_approved_location_on_update'
      )
      and not trigger.tgisinternal
  ),
  0,
  'static taxonomy triggers do not globally block publication'
);

select ok(
  to_regclass('content.location_cities') is not null
    and to_regclass('content.location_regions') is not null,
  'normalized manual location choices remain available to Admin'
);

insert into content.exhibitions (id)
values ('test-naver-geocoded-location-approval');

select lives_ok(
  $$insert into content.exhibition_versions (
      id,
      exhibition_id,
      version_number,
      status,
      name_ko,
      name_en,
      venue_name_ko,
      venue_name_en,
      city_ko,
      city_en,
      region_ko,
      region_en,
      address_ko,
      address_en,
      opening_date,
      closing_date,
      latitude,
      longitude,
      published_at
    ) values (
      '23000000-0000-0000-0000-000000000001'::uuid,
      'test-naver-geocoded-location-approval',
      1,
      'published'::content.exhibition_version_status,
      'NAVER 승인 위치',
      'NAVER-approved location',
      '테스트 전시장',
      'Test Venue',
      '경기',
      'Gyeonggi',
      '성남시',
      'Seongnam-si',
      '경기도 성남시 분당구 판교역로 166',
      '166 Pangyoyeok-ro, Bundang-gu, Seongnam-si, Gyeonggi-do',
      '2026-08-05'::date,
      '2026-08-31'::date,
      37.3947,
      127.1109,
      now()
    )$$,
  'a complete NAVER-confirmed location can be published outside the static region list'
);

select is(
  (
    select concat_ws('|', city_ko, city_en, region_ko, region_en)
    from content.exhibition_versions
    where id = '23000000-0000-0000-0000-000000000001'::uuid
  ),
  '경기|Gyeonggi|성남시|Seongnam-si',
  'the canonical NAVER-derived location labels remain stored'
);

select * from finish();
rollback;
