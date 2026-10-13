-- Public catalogue snapshot for the 088 commonTest fixture.
-- Same columns the canonical-v2 mobile reader selects (ExhibitionCatalogSource.CANONICAL_V2),
-- minus the integrity checksum and the venue `contact` field (phone numbers and emails are not
-- needed by any test and are kept out of the repository). Published, public fields only; no user data.
-- Reference date: 2026-10-02.
select id, name_ko, name_en, venue_name_ko, venue_name_en, country_code, city_ko, city_en,
       region_ko, region_en, opening_date, closing_date, is_featured, latitude, longitude,
       description_ko, description_en, credits_ko, credits_en, address_ko, address_en,
       cover_image_url, hours, reception_date, opening_time, event_id, editor_id,
       ticket_url, gallery_id, artists, art_terms
from public.exhibition_catalog_v2
where closing_date >= date '2026-10-02'
order by id;
