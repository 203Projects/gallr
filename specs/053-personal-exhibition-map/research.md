# Research: Personal Exhibition Map

## Decision 1: Use a shared abstract renderer, not an embedded street map

**Decision**: Replace the Map tab's Android and iOS Naver map views with one Compose Multiplatform
`Canvas` renderer in `composeApp/commonMain`. Keep exact venue coordinates in the domain model and
open an installed external map for directions.

**Rationale**: The approved experience is an abstract personal atlas. A native street-map view would
fight that visual model, duplicate platform behavior, retain an unnecessary iOS cinterop boundary,
and still be inaccessible without an equivalent list. Exact coordinates already provide everything
needed for nearby distance and navigation.

**Alternatives considered**:

- Restyle Naver Map: rejected because the base tiles, gestures, labels, and platform wrappers remain
  conventional map behavior.
- Place the abstract layer over Naver Map: rejected because it adds two renderers, ambiguous hit
  testing, provider branding obligations, and no product value for the approved scope.
- Add MapKit for iOS and Naver for Android: rejected because it creates divergent map behavior and
  violates shared-first architecture.

**Consequence**: Remove Naver SDK, Compose wrapper, iOS SPM package/cinterop, and credentials only
after the abstract renderer and external-navigation path are verified to cover all remaining uses.

## Decision 2: Treat display geometry as presentation data

**Decision**: Check in normalized dot masks for Korea and Seoul. Each mask contains background cells,
a geographic bounding box, and a stable geometry version. Real exhibition coordinates project into
the bounding box and snap to a free display cell; original coordinates never change.

**Rationale**: This produces the approved dot-map form while keeping geographic calculations correct.
Stable cell identifiers also make selection, hit testing, and recomposition deterministic.

The Korea silhouette will be derived from Natural Earth 1:10m cultural boundary data. Natural Earth
publishes its vector and raster data in the public domain and permits modification and commercial use:
<https://www.naturalearthdata.com/about/terms-of-use/>. Seoul can use the Seoul first-order
administrative shape in the same public-domain admin-1 dataset; district outlines are not needed for
the initial renderer because districts are semantic aggregates, not separate background shapes.

**Alternatives considered**:

- Draw one Compose node per dot: rejected because it creates unnecessary composition and
  accessibility nodes.
- Calculate a full GIS projection at runtime: rejected because normalized scope-local projection is
  sufficient for these intentionally abstract maps.
- Copy a screenshot or third-party map outline: rejected because provenance and reuse rights would be
  unclear.

## Decision 3: Make scope navigation data-driven but ship only Korea and Seoul

**Decision**: Model `MapScope` as an ID, kind (`COUNTRY`, `CITY`, `DISTRICT`), optional parent,
ISO country code, localized labels, bounds, and geometry key. The registry initially contains Korea
and Seoul; districts are generated from catalog location values under Seoul.

**Rationale**: One generic contract satisfies the present country-to-city requirement without
building an unused world-map system. Adding another country later means adding catalog country data,
scope registry entries, and geometry—not replacing UI or distance logic.

**Alternatives considered**:

- Hardcode `isSeoul` branches: rejected because the user explicitly expects future city and country
  scopes.
- Build a remote global scope service now: rejected under YAGNI because no non-Korean scope ships.

## Decision 4: Add explicit country identity to the catalog pipeline

**Decision**: Add `country_code` with default `KR` and an uppercase ISO-3166-1 alpha-2 shape check to
canonical venues, immutable exhibition versions, canonical mobile catalog rows, and the legacy mobile
mirror. Add `countryCode` to shared DTO/domain/cache models. Update catalog checksums, mirror/refresh
functions, snapshot tooling, and GAS `KNOWN_COLUMNS` atomically.

**Rationale**: City names are not globally unique and silently inferring Korea from current data would
become a migration trap. The default preserves all current rows and owner flows while making future
scope resolution explicit. The owner UI does not need a country picker in this feature; new records
continue to default to Korea.

**Alternatives considered**:

- Keep `countryCode = "KR"` only in mobile code: rejected because persisted catalog identity would
  remain ambiguous and historical visit snapshots could not be trusted later.
- Add a complete country taxonomy and owner workflow now: rejected because only Korea is in scope.

## Decision 5: Calculate nearby results in shared code for the initial catalog

**Decision**: Use a pure commonMain Haversine implementation against the already loaded, verified
catalog. Filter rows without complete coordinates, calculate meters from the one foreground/cached
location fix, and sort by `(distanceMeters, exhibitionId)` for deterministic cross-platform results.

**Rationale**: The app already downloads the active catalog. Client-side distance avoids a new
backend query, PostGIS dependency, and transmitting a private live location. It also works offline.

**Alternatives considered**:

- PostGIS radius query: deferred until measured catalog size or latency makes SC-002 fail. At that
  point the repository contract can gain a server-backed implementation without changing UI.
- Distance from snapped dots: rejected because the dot map is intentionally distorted.
- Continuous location updates: rejected because one foreground fix is enough and the spec forbids
  background tracking.

## Decision 6: Store visits as a private appendable diary

**Decision**: Allow multiple `ExhibitionVisit` rows per exhibition. A visited map state means at least
one non-deleted visit exists. Every visit has a client-generated UUID, visit time, optional note,
match source, and immutable exhibition/venue snapshot.

**Rationale**: A person may revisit an exhibition. Multiple rows preserve the diary truth while a
derived distinct exhibition-ID set keeps map state simple. A client-generated ID makes retries and
anonymous-to-authenticated migration idempotent.

**Alternatives considered**:

- One boolean or one row per user/exhibition: rejected because it loses repeat visits and dates.
- Reuse bookmarks or thoughts: rejected because their visibility, lifecycle, and meaning differ.
- Foreign-key the visit to the live catalog: rejected because a visit must survive unpublishing or
  catalog removal.

## Decision 7: Use a local repository plus owner-only cloud repository

**Decision**: Persist anonymous visits as serialized records in a dedicated DataStore, with private
photo derivatives in app-private files. When authenticated, merge local and cloud visits by client ID,
upload missing private photos, and remove a local item only after its row and photo are confirmed.

**Rationale**: This matches existing local repository conventions and avoids forcing sign-in. Stable
IDs make the migration safe to retry after interruption.

**Alternatives considered**:

- Require authentication: rejected by FR-015.
- Store image bytes in Preferences DataStore: rejected because Preferences is inappropriate for
  binary payloads and would cause large rewrites.
- Delete local rows as soon as upload begins: rejected because failures could lose a diary entry.

## Decision 8: Keep visit photos private and separate from visit rows

**Decision**: Create a private `visit-photos` bucket and a one-to-zero-or-one
`exhibition_visit_photos` row referencing a visit. The immutable object path is
`<user_id>/<visit_id>/<photo_id>.jpg`. RLS and Storage policies require the first path segment and
visit owner to equal `auth.uid()`.

**Rationale**: A separate row provides upload lifecycle state without bloating visit data. Private
Storage avoids signed public URLs and makes ownership testable. A database deletion removes metadata;
the client removes the object first and retains a retryable cleanup state if either step fails.

**Alternatives considered**:

- Put private images in `avatars` or `exhibition-images`: rejected because both are public delivery
  buckets with unrelated ownership contracts.
- Store a public URL on the visit: rejected because private-by-default would be impossible.
- Generalize the editorial media command system: rejected because personal single-image uploads do
  not need its publication workflow.

## Decision 9: Make photo metadata an ephemeral platform result

**Decision**: Add a visit-specific injected media gateway that returns bytes plus optional capture
instant and coordinates. Android reads original metadata only after the system photo selection and,
where needed, `ACCESS_MEDIA_LOCATION`; Android documents that `MediaStore.setRequireOriginal()` needs
this permission to avoid sensitive EXIF redaction:
<https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal(android.net.Uri)>.
iOS reads supported image metadata through Image I/O; Apple documents that `CGImageSource` can access
image metadata: <https://developer.apple.com/documentation/imageio/cgimagesource>.

Camera capture remains a separate platform implementation of the same gateway. The shared matcher
receives only decoded metadata, ranks candidates, and requires confirmation. After confirmation, the
source coordinate is discarded. The platform image sanitizer re-orients, resizes, and encodes a fresh
JPEG without copying metadata; tests re-read the derivative and prove GPS keys are absent.

**Alternatives considered**:

- Extend the avatar `rememberImagePicker(ByteArray?)`: rejected because it would couple private visit
  metadata and camera behavior to an unrelated profile API.
- Scan the photo library: rejected by scope and platform privacy principles.
- Trust that resizing automatically removes EXIF: rejected because sanitization needs an explicit,
  testable contract.

## Decision 10: External navigation is provider-neutral

**Decision**: Define an injected `ExternalMapLauncher` receiving venue label and exact coordinates.
Platform implementations prefer a supported installed map deep link and fall back to a universal web
URL or system map. Do not require Naver Map SDK merely to open Naver Map.

**Rationale**: Korea users can still use Naver navigation, while the contract also works outside Korea
and avoids coupling the renderer to one map provider.

## Decision 11: Privacy-safe observability

**Decision**: Emit operation name, outcome, scope ID, candidate/result counts, permission state, HTTP
class, and stable visit ID when safe. Never log photo bytes or paths containing user IDs, notes, raw
coordinates, candidate coordinates, signed URLs, or source metadata.

**Rationale**: These fields diagnose failures without turning logs into a second private-location or
photo store.
