# Contract: Gallery Info RPCs

## `owner_get_gallery_info()`

Authenticated only. Returns one JSON object with:

`gallery_id`, `revision`, `name_ko`, `name_en`, `venue_name_ko`, `venue_name_en`,
`city_ko`, `city_en`, `region_ko`, `region_en`, `address_ko`, `address_en`,
`latitude`, `longitude`, `hours`, `contact`, `updated_at`.

No membership evidence, user identifiers, venue identifier, gallery lifecycle mutation
fields, or staff data are returned.

## `owner_save_gallery_info(p_expected_revision integer, p_patch jsonb)`

Authenticated only. The server derives caller, gallery, membership, and canonical venue.
The patch accepts only the documented Gallery Info fields. A successful save returns the
same shape as `owner_get_gallery_info()` with revision incremented exactly once.

Errors are stable machine messages:

- `gallery_info_access_denied`
- `gallery_info_patch_invalid`
- `gallery_info_field_not_allowed`
- `gallery_info_field_invalid`
- `gallery_info_required`
- `gallery_info_location_invalid`
- `revision_conflict`

Neither RPC grants table-level read or write privileges.
