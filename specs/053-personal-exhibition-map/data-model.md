# Data Model: Personal Exhibition Map

## Geographic catalog identity

### Catalog country field

Add `country_code text not null default 'KR'` with a check equivalent to
`country_code ~ '^[A-Z]{2}$'` to:

- `content.venues`
- `content.exhibition_versions`
- `public.exhibition_catalog_v2`
- `public.exhibitions` (legacy mobile mirror)

Published catalog checksums and mirror payloads include `country_code`. Existing rows backfill to
`KR`. The canonical venue-to-version snapshot copies the value; later venue edits never mutate an
already-created exhibition version.

### Shared `Exhibition`

Add:

| Field | Type | Rule |
|---|---|---|
| `countryCode` | `String` | Uppercase two-letter code; DTO defaults to `KR` during rollout |

Every cache and DTO serialization includes the field. The temporary DTO default permits a mobile
binary to read a pre-migration response during deployment; server rows remain explicit after rollout.

## Map presentation entities

### `MapScope`

| Field | Type | Rule |
|---|---|---|
| `id` | `MapScopeId` | Stable value such as `country:KR` or `city:KR:seoul` |
| `kind` | `COUNTRY`, `CITY`, `DISTRICT` | Determines child aggregation level |
| `parentId` | `MapScopeId?` | Null only for a configured root |
| `countryCode` | `String` | Present on every scope |
| `cityKey` | `String?` | Normalized catalog city identity |
| `districtKey` | `String?` | Normalized catalog district identity |
| `labelKo`, `labelEn` | `String` | Bilingual visible identity |
| `geoBounds` | `GeoBounds` | Geographic projection and containment bounds |
| `geometryKey` | `String?` | Country/city geometry; district may use parent geometry |

Initial registry entries are `country:KR` and `city:KR:seoul`. Seoul districts derive from the
catalog's approved location taxonomy and use stable normalized keys rather than translated display
text as identity.

### `DotMapGeometry`

| Field | Type | Rule |
|---|---|---|
| `key` | `String` | Stable asset key |
| `version` | `Int` | Increments when generated cells change |
| `sourceName` | `String` | `Natural Earth` initially |
| `sourceUrl` | `String` | Provenance retained beside generated data |
| `bounds` | `GeoBounds` | Matches the source shape used for projection |
| `cells` | `List<DotCell>` | Normalized `x`, `y` in `0.0..1.0`; stable cell ID |

Background cells carry no exhibition semantics. One canvas draw pass renders them.

### `ProjectedMapMark`

| Field | Type | Rule |
|---|---|---|
| `id` | `String` | Child-scope, venue-group, or visit-snapshot identity |
| `sourceCoordinates` | `GeoPoint?` | Never replaced by display position |
| `cellId` | `String` | Presentation-only snapped cell |
| `state` | `VISITED`, `SAVED`, `UNEXPLORED`, `SELECTED` | Derived for current mode |
| `itemIds` | `List<String>` | All exhibitions/visits grouped into this mark |
| `aggregate` | `ScopeAggregate?` | Present for country/city child marks |

Collision resolution is deterministic: preferred projected cell, then nearest free cell by distance,
then stable cell ID. If capacity is exhausted, marks share a cell and disclose a count.

### `ScopeAggregate`

Counts are derived, not stored:

- `activeExhibitionCount`
- `visitedExhibitionCount` (distinct exhibition identities)
- `visitCount` (all diary visits)
- `savedUnvisitedCount`
- `unexploredCount`
- `coordinateUnavailableCount`

## Visit domain entities

### `ExhibitionVisit`

| Field | Local type / DB type | Rule |
|---|---|---|
| `id` | `String` / `uuid` | Generated on device; stable idempotency key |
| `userId` | not persisted anonymously / `uuid` | Cloud value equals `auth.uid()` |
| `exhibitionId` | `String` / `text` | Logical identity only; deliberately no live-catalog FK |
| `visitedAt` | `Instant` / `timestamptz` | User-confirmed visit time |
| `note` | `String?` / `text` | Trimmed, max 280 characters |
| `matchSource` | enum / `text` | `manual`, `photo_metadata`, `current_location` |
| `snapshot` | `VisitSnapshot` / scalar columns | Immutable at visit creation |
| `createdAt` | `Instant` / `timestamptz` | Server default in cloud; local clock offline |
| `updatedAt` | `Instant` / `timestamptz` | Changes on note/date edit |

Cloud table: `public.exhibition_visits`.

Constraints:

- Primary key: `id`.
- `user_id not null references auth.users(id) on delete cascade`.
- `note is null or char_length(note) <= 280`.
- Coordinate pair is both null or both present and within valid latitude/longitude ranges.
- `visited_at`, snapshot title, snapshot venue, snapshot country, and exhibition identity are required.
- Multiple rows with the same `(user_id, exhibition_id)` are valid.
- RLS permits SELECT/INSERT/UPDATE/DELETE only when `user_id = auth.uid()`; INSERT also checks it.
- Clients cannot change `user_id`, `id`, `exhibition_id`, or snapshot fields during an edit. Edits are
  limited to `visited_at`, `note`, and `updated_at` through a constrained repository/RPC path.

Indexes:

- `(user_id, visited_at desc, id)` for diary paging.
- `(user_id, exhibition_id)` for visited-state derivation.
- `(user_id, snapshot_country_code, snapshot_city_key, visited_at desc)` for scope history.

### `VisitSnapshot`

Copied at visit creation:

- Exhibition: Korean/English title, opening date, closing date, current cover image URL.
- Venue: Korean/English venue name.
- Geography: country code, Korean/English city, Korean/English region, latitude, longitude.

Snapshot image URLs are historical hints, not guaranteed private copies. The diary must remain usable
when the image no longer loads by showing the text and map position.

### `VisitPhoto`

Cloud table: `public.exhibition_visit_photos`.

| Field | Type | Rule |
|---|---|---|
| `id` | `uuid` | Client-generated |
| `visitId` | `uuid` | Unique FK to `exhibition_visits(id)` on delete cascade |
| `userId` | `uuid` | Must equal visit owner and `auth.uid()` |
| `bucketId` | `text` | Fixed to `visit-photos` |
| `objectPath` | `text` | Immutable owner/visit/photo path |
| `mimeType` | `text` | Initial release: `image/jpeg` |
| `byteSize` | `bigint` | Positive and within bucket limit |
| `pixelWidth`, `pixelHeight` | `integer` | Positive sanitized derivative dimensions |
| `createdAt` | `timestamptz` | Server default |

The private bucket allows JPEG only and enforces the implementation-established derivative limit.
RLS and Storage policies are owner-only. No public read policy or public URL exists.

## Ephemeral matching entities

### `PhotoMetadata`

- `capturedAt: Instant?`
- `coordinates: GeoPoint?`

This object is never serialized to DataStore, PostgreSQL, analytics, or logs. It is dropped after
candidate confirmation or flow cancellation.

### `VisitCandidate`

- Live exhibition and venue identity.
- Real `distanceMeters` when metadata has a coordinate.
- Schedule relationship: active on capture date, date unavailable, or outside date.
- Deterministic score/rank explanation.

Ranking order:

1. Exhibition schedule contains capture date.
2. Smaller real distance.
3. Stable exhibition ID.

Missing metadata removes that signal rather than inventing a value. No rank creates a visit without
explicit selection.

## Local persistence and synchronization

### Local visit store

- Dedicated DataStore key contains a versioned JSON envelope of `ExhibitionVisit` records.
- A local record carries `syncState`: `LOCAL_ONLY`, `ROW_SYNCED`, `PHOTO_PENDING`, or `SYNCED`.
- Sanitized photo derivatives are stored in app-private files keyed by visit/photo ID; DataStore holds
  only the relative private filename and metadata.
- Delete uses a tombstone while cloud row/object deletion is pending. A tombstone is removed only when
  the remote absence is confirmed.

### Merge invariant

For each stable visit ID:

1. Upsert the immutable row with the authenticated `user_id`.
2. If a private derivative exists and no cloud photo row exists, upload to the deterministic path and
   insert its metadata row.
3. Confirm cloud row/photo visibility through the owner session.
4. Mark the local copy synced; keep it as offline cache rather than immediately deleting it.

Repeated migration converges on the same row and object. A cloud row owned by another user can never be
claimed because RLS rejects it.
