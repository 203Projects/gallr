begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select plan(14);

select has_column('content', 'venues', 'country_code',
  'canonical venues carry explicit country identity');
select has_column('content', 'exhibition_versions', 'country_code',
  'immutable exhibition versions snapshot country identity');
select has_column('public', 'exhibition_catalog_v2', 'country_code',
  'canonical mobile catalog exposes country identity');
select has_column('public', 'exhibitions', 'country_code',
  'legacy mobile catalog remains structurally compatible');
select col_default_is('content', 'venues', 'country_code', 'KR',
  'new venues retain the Korea-first default');
select col_not_null('content', 'venues', 'country_code',
  'venue country identity cannot be null');

select throws_ok(
  $$insert into content.venues (name_ko, country_code) values ('잘못된 국가', 'KOR')$$,
  '23514', null, 'country code must use an uppercase two-letter shape');

insert into content.venues (
  id, name_ko, name_en, country_code, city_ko, city_en, region_ko, region_en
) values (
  '26000000-0000-0000-0000-000000000001', '도쿄 장소', 'Tokyo Venue', 'JP',
  '도쿄', 'Tokyo', '시부야구', 'Shibuya'
);

insert into content.exhibitions (id) values ('country-code-projection');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, status, venue_id,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, published_at
) values (
  '26000000-0000-0000-0000-000000000002', 'country-code-projection', 1,
  'published', '26000000-0000-0000-0000-000000000001',
  '국가 코드 전시', 'Country Code Exhibition', '도쿄 장소', 'Tokyo Venue',
  '도쿄', 'Tokyo', '시부야구', 'Shibuya',
  '2026-08-01', '2026-08-31', now()
);

select is(
  (select country_code from content.exhibition_versions
   where id = '26000000-0000-0000-0000-000000000002'),
  'JP', 'new versions snapshot country from their canonical venue'
);

update content.exhibitions
set published_version_id = '26000000-0000-0000-0000-000000000002'
where id = 'country-code-projection';

select is(
  (select country_code from public.exhibition_catalog_v2
   where id = 'country-code-projection'),
  'JP', 'published catalog projection receives version country identity'
);
select is(
  (select payload ->> 'country_code'
   from content_private.exhibition_catalog_v2_source_payload('country-code-projection')),
  'JP', 'canonical reconciliation payload includes country identity'
);
select is(
  (select content_checksum_sha256 =
    content_private.exhibition_catalog_v2_checksum(catalog)
   from public.exhibition_catalog_v2 as catalog
   where id = 'country-code-projection'),
  true, 'catalog checksum covers the country field'
);

update content.venues
set country_code = 'KR'
where id = '26000000-0000-0000-0000-000000000001';

select is(
  (select country_code from content.exhibition_versions
   where id = '26000000-0000-0000-0000-000000000002'),
  'JP', 'later venue edits do not rewrite a published version snapshot'
);
select is(
  (select country_code from public.exhibition_catalog_v2
   where id = 'country-code-projection'),
  'JP', 'later venue edits do not rewrite the published catalog snapshot'
);

select ok(
  not has_table_privilege('authenticated', 'content.venues', 'UPDATE'),
  'country identity adds no direct authenticated canonical write path'
);

select * from finish();
rollback;
