-- NAVER geocoding is the approval path for new locations. The static location
-- tables remain Admin normalization choices, but they must not reject valid
-- NAVER-confirmed locations, legacy imports, or gallery-owner publications.
drop trigger if exists exhibition_versions_require_approved_location_on_insert
  on content.exhibition_versions;

drop trigger if exists exhibition_versions_require_approved_location_on_update
  on content.exhibition_versions;

comment on function content_private.require_approved_location_on_publish() is
  'Legacy static-taxonomy validator retained for migration compatibility. NAVER-confirmed Admin locations and the required map-location trigger define the active publication boundary.';
