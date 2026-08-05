# Data Model: Gallery Info

## Aggregate

Gallery Info is one aggregate rooted at `content.galleries.id`.

- Organization fields: `galleries.name_ko`, `name_en`.
- Aggregate concurrency: `galleries.revision`.
- Venue link: `galleries.canonical_venue_id`.
- Venue defaults: `venues.name_ko`, `name_en`, bilingual city/region/address,
  `latitude`, `longitude`, `default_hours`, and `default_contact`.

A save creates the canonical venue if the link is null; otherwise it updates only that
linked row. The gallery revision increments once after a successful venue/identity save.

## Authorization invariants

- Active path: caller owns an active membership for the gallery.
- Pending-new path: membership is pending, gallery is pending, and both gallery and
  membership were created by the caller.
- All other paths fail before reading or writing the aggregate.
- Gallery, membership, and venue IDs are derived server-side and never accepted from the
  save patch.

## Patch fields

Allowed string keys:

`name_ko`, `name_en`, `venue_name_ko`, `venue_name_en`, `city_ko`, `city_en`,
`region_ko`, `region_en`, `address_ko`, `address_en`, `hours`, `contact`.

Allowed numeric keys:

`latitude`, `longitude`.

Names and a complete geocoded location are required. Coordinates must be a valid pair.
Unknown keys, arrays, nested objects, and invalid values fail atomically.

## Snapshot invariant

Every owner exhibition version stores copied scalar venue values plus the optional
canonical `venue_id`. No trigger or save function propagates subsequent venue edits into
`content.exhibition_versions` or submission payloads.

## Quota storage

`content_private.geocode_rate_limit_windows` retains fixed one-minute counters:

- `project/project`: maximum 30 accepted requests.
- `staff/<user uuid>`: maximum 10 accepted staff requests.
- `owner/<user uuid>`: maximum 10 accepted owner requests.

Project lock is always acquired before caller lock. Rejected calls do not increment either
counter.
