-- Repair the malformed location snapshots that split one city into several
-- client-side chips. Match stable address snapshots rather than generated IDs
-- so the correction also reaches draft copies and cannot regress on republish.
with corrections (
  address_ko,
  city_ko,
  city_en,
  region_ko,
  region_en
) as (
  values
    ('부산시 해운대구 달맞이길 65번길 154, 2층', '부산', 'Busan', '해운대구', 'Haeundae-gu'),
    ('서울시 서초구 반포대로 18, B1', '서울', 'Seoul', '서초구', 'Seocho-gu'),
    ('서울특별시 종로구 백석동1가길 45', '서울', 'Seoul', '종로구', 'Jongno-gu'),
    ('서울특별시 종로구 삼청로 48-4 학고재', '서울', 'Seoul', '종로구', 'Jongno-gu'),
    ('서울특별시 영등포구 양평로100 3F', '서울', 'Seoul', '영등포구', 'Yeongdeungpo-gu'),
    ('서울시 용산구 서빙고로71길 25 1층', '서울', 'Seoul', '용산구', 'Yongsan-gu'),
    ('서울특별시 종로구 삼청로5길 20', '서울', 'Seoul', '종로구', 'Jongno-gu'),
    ('경기도 안산시 단원구 중앙대로 895, 302호', '안산시', 'Ansan-si', '단원구', 'Danwon-gu'),
    ('경기도 안산시 단원구 중앙대로 895 유창빌딩, 302호', '안산시', 'Ansan-si', '단원구', 'Danwon-gu')
)
update content.exhibition_versions as version
set
  city_ko = correction.city_ko,
  city_en = correction.city_en,
  region_ko = correction.region_ko,
  region_en = correction.region_en
from corrections as correction
where version.address_ko = correction.address_ko
  and btrim(version.city_en) = ''
  and btrim(version.region_en) = '';

do $migration$
begin
  if exists (
    select 1
    from public.exhibitions as exhibition
    where btrim(exhibition.city_en) = ''
       or btrim(exhibition.region_en) = ''
       or lower(btrim(exhibition.city_ko)) = lower(btrim(exhibition.region_ko))
       or lower(btrim(exhibition.city_en)) = lower(btrim(exhibition.region_en))
  ) then
    raise exception 'malformed_catalog_locations_remain';
  end if;
end;
$migration$;
