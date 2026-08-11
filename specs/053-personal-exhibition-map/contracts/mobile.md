# Mobile Contracts: Personal Exhibition Map

These are architectural contracts, not final Kotlin syntax. Public interfaces must be documented in
source when implemented.

## Scope and geometry

`MapScopeRegistry` provides the supported root and child scopes without network or platform logic.

- `rootScopes(): List<MapScope>`
- `scope(id: MapScopeId): MapScope?`
- `children(parentId: MapScopeId, exhibitions: List<Exhibition>): List<MapScope>`
- `resolve(coordinates: GeoPoint): MapScopeResolution`

`DotMapProjector` is pure shared logic.

- Input: scope, geometry, semantic items with optional source coordinates, selected ID.
- Output: deterministic projected marks plus coordinate-unavailable items.
- Invariant: output display cells are never accepted by distance, navigation, persistence, or matching
  APIs.

## Nearby discovery

`NearbyExhibitionFinder.find(origin, exhibitions, limit)`:

- Omits exhibitions with incomplete coordinates.
- Calculates geodesic meters from source coordinates.
- Sorts by distance, then stable exhibition ID.
- Returns the distance alongside the unchanged exhibition.
- Performs no network call and retains no origin.

`UserLocationProvider.currentOrCached()` is invoked only from a location action. It returns one of:

- `Available(point, freshness)`
- `PermissionRequired`
- `PermissionDenied`
- `Unavailable`

`ExternalMapLauncher.open(label, coordinates)` is a platform dependency. It returns success or a
typed unsupported/failure result; it never receives a snapped display coordinate.

## Visit repository

`VisitRepository` is shared and exposes:

- A flow of locally available visits, including cloud-cached visits.
- Create with a client ID, immutable snapshot, visit time, optional note, and match source.
- Update visit time/note only.
- Delete with retryable local tombstone behavior.
- Synchronize/migrate for the current authenticated user.

Create/update/delete update local state before network completion. Failures keep user data and expose a
retryable sync state.

`CloudVisitDataSource` owns typed Supabase row operations. `LocalVisitDataSource` owns DataStore JSON
and private file references. `VisitSyncService` merges them by stable visit ID; platform entry points
wire dependencies but contain no migration decisions.

## Visit photo intake

`VisitMediaGateway` is a platform implementation injected into common UI:

- `choosePhoto()`
- `takePhoto()`
- `sanitize(sourceBytes)`
- `deleteLocalDerivative(relativePath)`

The choice/capture result contains source bytes and optional ephemeral metadata. Sanitization returns
a fresh JPEG plus dimensions and contains no source EXIF. The original is never written to Gallr's
private storage. Platform code decodes metadata and image formats only; shared code decides candidate
ranking, confirmation, and persistence.

## Candidate matcher

`VisitCandidateMatcher.match(metadata, catalog, maximumDistanceMeters)`:

- Treats missing capture date or coordinate as absent evidence.
- Prioritizes exhibitions active on capture date, then real distance, then stable ID.
- Does not include raw metadata in its output.
- Never creates or selects a visit.
- Returns an empty/manual result when no responsible candidate can be suggested.

## Map UI state

`PersonalMapViewModel` exposes one immutable `StateFlow<PersonalMapUiState>` containing:

- Mode: To Visit, Visited, All.
- Active scope and breadcrumb.
- Geometry/marks and coordinate-unavailable results.
- Counts and selected aggregate/item.
- Nearby state and ordered results.
- Visit mutation/sync state.
- Loading, offline, and recoverable error state.

The ViewModel depends on shared repositories and pure services. `DotMapCanvas` is a stateless renderer;
it emits selected mark IDs and has no repository or location access.
