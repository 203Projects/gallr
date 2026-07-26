begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(10);

select ok(
  exists (
    select 1
    from pg_trigger as trigger
    join pg_class as relation on relation.oid = trigger.tgrelid
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'exhibition_versions'
      and trigger.tgname = 'exhibition_versions_invalidate_stale_map_coordinates'
      and not trigger.tgisinternal
  ),
  'canonical versions invalidate coordinates when their address changes'
);

select ok(
  exists (
    select 1
    from pg_trigger as trigger
    join pg_class as relation on relation.oid = trigger.tgrelid
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'exhibition_versions'
      and trigger.tgname = 'exhibition_versions_require_map_location_on_publish'
      and not trigger.tgisinternal
  ),
  'canonical versions have a map-location publication trigger'
);

insert into content.exhibitions (id)
values ('test-required-map-location-20260722');

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date
)
values (
  '90000000-0000-0000-0000-000000000901'::uuid,
  'test-required-map-location-20260722',
  1,
  'draft'::content.exhibition_version_status,
  '지도 위치 필수 전시',
  '테스트 전시장',
  '서울',
  '용산구',
  '2026-07-22'::date,
  '2026-08-22'::date
);

select lives_ok(
  $$update content.exhibition_versions
    set name_en = 'Incomplete drafts remain editable'
    where id = '90000000-0000-0000-0000-000000000901'::uuid$$,
  'an incomplete draft remains editable'
);

select throws_ok(
  $$update content.exhibition_versions
    set status = 'published'::content.exhibition_version_status,
        published_at = now()
    where id = '90000000-0000-0000-0000-000000000901'::uuid$$,
  '23514',
  'address_ko_is_required_for_publication',
  'a draft without a Korean address cannot become published'
);

update content.exhibition_versions
set address_ko = '서울 용산구 한남대로 28'
where id = '90000000-0000-0000-0000-000000000901'::uuid;

select throws_ok(
  $$update content.exhibition_versions
    set status = 'published'::content.exhibition_version_status,
        published_at = now()
    where id = '90000000-0000-0000-0000-000000000901'::uuid$$,
  '23514',
  'map_coordinates_are_required_for_publication',
  'a draft without coordinates cannot become published'
);

update content.exhibition_versions
set latitude = 37.5344,
    longitude = 127.0005
where id = '90000000-0000-0000-0000-000000000901'::uuid;

select lives_ok(
  $$update content.exhibition_versions
    set status = 'published'::content.exhibition_version_status,
        published_at = now()
    where id = '90000000-0000-0000-0000-000000000901'::uuid$$,
  'a complete map location can become published'
);

select is(
  (
    select status::text
    from content.exhibition_versions
    where id = '90000000-0000-0000-0000-000000000901'::uuid
  ),
  'published',
  'the valid version remains published'
);

select is(
  (
    select concat_ws(',', latitude::text, longitude::text)
    from content.exhibition_versions
    where id = '90000000-0000-0000-0000-000000000901'::uuid
  ),
  '37.5344,127.0005',
  'the selected WGS-84 latitude and longitude remain stored without axis swapping'
);

select throws_ok(
  $$update content.exhibition_versions
    set address_ko = '서울 용산구 다른로 29'
    where id = '90000000-0000-0000-0000-000000000901'::uuid$$,
  '23514',
  'map_coordinates_are_required_for_publication',
  'a published address cannot change while retaining its previous coordinates'
);

select is(
  (
    select concat_ws('|', address_ko, latitude::text, longitude::text)
    from content.exhibition_versions
    where id = '90000000-0000-0000-0000-000000000901'::uuid
  ),
  '서울 용산구 한남대로 28|37.5344|127.0005',
  'the rejected published edit leaves its address and coordinate pair unchanged'
);

select * from finish();
rollback;
