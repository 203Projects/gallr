-- Canonical two-level Korean location taxonomy used by Admin publication.
-- City is one of the 17 first-level administrative divisions; region is the
-- next municipal layer shown by the app. More approved regions can be added
-- independently without changing client code.

create table content.location_cities (
  code text primary key,
  city_ko text not null unique,
  city_en text not null unique,
  sort_order smallint not null unique,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint location_cities_code_format check (
    code = btrim(code) and code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint location_cities_labels_not_blank check (
    length(btrim(city_ko)) > 0 and length(btrim(city_en)) > 0
  )
);

create table content.location_regions (
  city_code text not null
    references content.location_cities(code) on update cascade on delete restrict,
  code text not null,
  region_ko text not null,
  region_en text not null,
  sort_order smallint not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (city_code, code),
  unique (city_code, region_ko),
  unique (city_code, region_en),
  unique (city_code, sort_order),
  constraint location_regions_code_format check (
    code = btrim(code) and code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint location_regions_labels_not_blank check (
    length(btrim(region_ko)) > 0 and length(btrim(region_en)) > 0
  )
);

alter table content.location_cities enable row level security;
alter table content.location_regions enable row level security;

revoke all on content.location_cities
  from public, anon, authenticated, service_role;
revoke all on content.location_regions
  from public, anon, authenticated, service_role;

create trigger location_cities_set_updated_at
  before update on content.location_cities
  for each row execute function content_private.set_updated_at();
create trigger location_regions_set_updated_at
  before update on content.location_regions
  for each row execute function content_private.set_updated_at();

insert into content.location_cities (code, city_ko, city_en, sort_order)
values
  ('seoul', '서울', 'Seoul', 1),
  ('busan', '부산', 'Busan', 2),
  ('daegu', '대구', 'Daegu', 3),
  ('incheon', '인천', 'Incheon', 4),
  ('gwangju', '광주', 'Gwangju', 5),
  ('daejeon', '대전', 'Daejeon', 6),
  ('ulsan', '울산', 'Ulsan', 7),
  ('sejong', '세종', 'Sejong', 8),
  ('gyeonggi', '경기', 'Gyeonggi', 9),
  ('gangwon', '강원', 'Gangwon', 10),
  ('chungbuk', '충북', 'Chungbuk', 11),
  ('chungnam', '충남', 'Chungnam', 12),
  ('jeonbuk', '전북', 'Jeonbuk', 13),
  ('jeonnam', '전남', 'Jeonnam', 14),
  ('gyeongbuk', '경북', 'Gyeongbuk', 15),
  ('gyeongnam', '경남', 'Gyeongnam', 16),
  ('jeju', '제주', 'Jeju', 17);

insert into content.location_regions (
  city_code,
  code,
  region_ko,
  region_en,
  sort_order
)
values
  ('seoul', 'gangnam-gu', '강남구', 'Gangnam-gu', 1),
  ('seoul', 'gangdong-gu', '강동구', 'Gangdong-gu', 2),
  ('seoul', 'gangbuk-gu', '강북구', 'Gangbuk-gu', 3),
  ('seoul', 'gangseo-gu', '강서구', 'Gangseo-gu', 4),
  ('seoul', 'gwanak-gu', '관악구', 'Gwanak-gu', 5),
  ('seoul', 'gwangjin-gu', '광진구', 'Gwangjin-gu', 6),
  ('seoul', 'guro-gu', '구로구', 'Guro-gu', 7),
  ('seoul', 'geumcheon-gu', '금천구', 'Geumcheon-gu', 8),
  ('seoul', 'nowon-gu', '노원구', 'Nowon-gu', 9),
  ('seoul', 'dobong-gu', '도봉구', 'Dobong-gu', 10),
  ('seoul', 'dongdaemun-gu', '동대문구', 'Dongdaemun-gu', 11),
  ('seoul', 'dongjak-gu', '동작구', 'Dongjak-gu', 12),
  ('seoul', 'mapo-gu', '마포구', 'Mapo-gu', 13),
  ('seoul', 'seodaemun-gu', '서대문구', 'Seodaemun-gu', 14),
  ('seoul', 'seocho-gu', '서초구', 'Seocho-gu', 15),
  ('seoul', 'seongdong-gu', '성동구', 'Seongdong-gu', 16),
  ('seoul', 'seongbuk-gu', '성북구', 'Seongbuk-gu', 17),
  ('seoul', 'songpa-gu', '송파구', 'Songpa-gu', 18),
  ('seoul', 'yangcheon-gu', '양천구', 'Yangcheon-gu', 19),
  ('seoul', 'yeongdeungpo-gu', '영등포구', 'Yeongdeungpo-gu', 20),
  ('seoul', 'yongsan-gu', '용산구', 'Yongsan-gu', 21),
  ('seoul', 'eunpyeong-gu', '은평구', 'Eunpyeong-gu', 22),
  ('seoul', 'jongno-gu', '종로구', 'Jongno-gu', 23),
  ('seoul', 'jung-gu', '중구', 'Jung-gu', 24),
  ('seoul', 'jungnang-gu', '중랑구', 'Jungnang-gu', 25),
  ('busan', 'gangseo-gu', '강서구', 'Gangseo-gu', 1),
  ('busan', 'geumjeong-gu', '금정구', 'Geumjeong-gu', 2),
  ('busan', 'gijang-gun', '기장군', 'Gijang-gun', 3),
  ('busan', 'nam-gu', '남구', 'Nam-gu', 4),
  ('busan', 'dong-gu', '동구', 'Dong-gu', 5),
  ('busan', 'dongnae-gu', '동래구', 'Dongnae-gu', 6),
  ('busan', 'busanjin-gu', '부산진구', 'Busanjin-gu', 7),
  ('busan', 'buk-gu', '북구', 'Buk-gu', 8),
  ('busan', 'sasang-gu', '사상구', 'Sasang-gu', 9),
  ('busan', 'saha-gu', '사하구', 'Saha-gu', 10),
  ('busan', 'seo-gu', '서구', 'Seo-gu', 11),
  ('busan', 'suyeong-gu', '수영구', 'Suyeong-gu', 12),
  ('busan', 'yeonje-gu', '연제구', 'Yeonje-gu', 13),
  ('busan', 'yeongdo-gu', '영도구', 'Yeongdo-gu', 14),
  ('busan', 'jung-gu', '중구', 'Jung-gu', 15),
  ('busan', 'haeundae-gu', '해운대구', 'Haeundae-gu', 16),
  ('daegu', 'suseong-gu', '수성구', 'Suseong-gu', 1),
  ('daegu', 'jung-gu', '중구', 'Jung-gu', 2),
  ('incheon', 'jung-gu', '중구', 'Jung-gu', 1),
  ('gyeonggi', 'gwacheon-si', '과천시', 'Gwacheon-si', 1),
  ('gyeonggi', 'suwon-si', '수원시', 'Suwon-si', 2),
  ('gyeonggi', 'ansan-si', '안산시', 'Ansan-si', 3),
  ('gyeonggi', 'yongin-si', '용인시', 'Yongin-si', 4),
  ('gyeonggi', 'paju-si', '파주시', 'Paju-si', 5),
  ('gangwon', 'wonju-si', '원주시', 'Wonju-si', 1),
  ('jeonbuk', 'wanju-gun', '완주군', 'Wanju-gun', 1);

-- Normalize the two incorrectly nested Ansan exhibitions, including historic
-- versions, so old clients and future republishes use the same hierarchy.
update content.exhibition_versions
set
  city_ko = '경기',
  city_en = 'Gyeonggi',
  region_ko = '안산시',
  region_en = 'Ansan-si'
where btrim(city_ko) = '안산시'
  and (
    btrim(region_ko) in ('단원구', 'Ansan-si')
    or address_ko like '%안산시%'
  );

update content.venues
set
  city_ko = '경기',
  city_en = 'Gyeonggi',
  region_ko = '안산시',
  region_en = 'Ansan-si'
where btrim(city_ko) = '안산시'
  and (
    btrim(region_ko) in ('단원구', 'Ansan-si')
    or address_ko like '%안산시%'
  );

create or replace function content_private.require_approved_location_on_publish()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status = 'published'::content.exhibition_version_status
     and not exists (
       select 1
       from content.location_cities as city
       join content.location_regions as region
         on region.city_code = city.code
       where city.is_active
         and region.is_active
         and city.city_ko = btrim(new.city_ko)
         and city.city_en = btrim(new.city_en)
         and region.region_ko = btrim(new.region_ko)
         and region.region_en = btrim(new.region_en)
     ) then
    raise exception using
      errcode = '23514',
      message = 'approved_location_is_required_for_publication';
  end if;

  return new;
end;
$$;

revoke all on function content_private.require_approved_location_on_publish()
  from public, anon, authenticated, service_role;

create trigger exhibition_versions_require_approved_location_on_insert
  before insert on content.exhibition_versions
  for each row
  execute function content_private.require_approved_location_on_publish();

create trigger exhibition_versions_require_approved_location_on_update
  before update of status, city_ko, city_en, region_ko, region_en
  on content.exhibition_versions
  for each row
  execute function content_private.require_approved_location_on_publish();

-- Preserve the established one-RPC Admin lookup contract while adding the
-- approved taxonomy and excluding historical venues with invalid locations.
alter function content_private.admin_get_exhibition_lookups_impl()
  rename to admin_get_exhibition_lookups_without_locations_impl;

revoke all on function
  content_private.admin_get_exhibition_lookups_without_locations_impl()
  from public, anon, authenticated, service_role;

create function content_private.admin_get_exhibition_lookups_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_base jsonb;
  v_locations jsonb;
  v_venues jsonb;
begin
  v_base := content_private.admin_get_exhibition_lookups_without_locations_impl();

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'city_ko', city.city_ko,
        'city_en', city.city_en,
        'region_ko', region.region_ko,
        'region_en', region.region_en
      )
      order by city.sort_order, region.sort_order
    ),
    '[]'::jsonb
  )
  into v_locations
  from content.location_cities as city
  join content.location_regions as region on region.city_code = city.code
  where city.is_active and region.is_active;

  select coalesce(jsonb_agg(candidate.venue), '[]'::jsonb)
  into v_venues
  from jsonb_array_elements(coalesce(v_base -> 'venues', '[]'::jsonb))
    as candidate(venue)
  where exists (
    select 1
    from content.location_cities as city
    join content.location_regions as region on region.city_code = city.code
    where city.is_active
      and region.is_active
      and city.city_ko = candidate.venue ->> 'city_ko'
      and city.city_en = candidate.venue ->> 'city_en'
      and region.region_ko = candidate.venue ->> 'region_ko'
      and region.region_en = candidate.venue ->> 'region_en'
  );

  return jsonb_set(
    jsonb_set(v_base, '{venues}', v_venues, true),
    '{locations}',
    v_locations,
    true
  );
end;
$$;

revoke all on function content_private.admin_get_exhibition_lookups_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_get_exhibition_lookups_impl()
  to authenticated;

do $migration$
begin
  if exists (
    select 1
    from public.exhibitions as exhibition
    where not exists (
      select 1
      from content.location_cities as city
      join content.location_regions as region on region.city_code = city.code
      where city.is_active
        and region.is_active
        and city.city_ko = exhibition.city_ko
        and city.city_en = exhibition.city_en
        and region.region_ko = exhibition.region_ko
        and region.region_en = exhibition.region_en
    )
  ) then
    raise exception 'public_catalog_contains_unapproved_locations';
  end if;
end;
$migration$;
