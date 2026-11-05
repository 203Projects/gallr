-- Every venue name readers see in the public catalogue, with how many exhibitions it has.
-- One read-only SELECT; `venue-registry.mjs diff` compares its JSON output with venues.json.
select
  btrim(venue_name_ko) as venue_name_ko,
  max(venue_name_en) as venue_name_en,
  max(city_ko) as city_ko,
  max(region_ko) as region_ko,
  count(*)::integer as exhibitions,
  max(closing_date)::text as last_closing_date
from public.exhibition_catalog_v2
group by btrim(venue_name_ko)
order by 1;
