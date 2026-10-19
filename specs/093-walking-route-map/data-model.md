# Data model: Walking routes and a full-screen route map (093)

## Server

See [contracts/walking-route.md](contracts/walking-route.md) for `content_private.walking_legs` (venue-to-venue cache,
undirected, position-keyed) and `content_private.walking_route_quota`. No change to routes, stops, listings or the
catalogue. Nothing stores a visitor position.

## Shared domain (`shared/src/commonMain/kotlin/com/gallr/shared/map/`)

Existing types reused unchanged: `RouteWaypoint(exhibitionId, point)`, `EstimatedLeg(distanceMeters, travelMinutes,
geometry, quality)`, `RouteLegQuality { APPROXIMATE, ROUTED }`, `RouteLegEstimator`, `EstimatedRouteLeg`,
`ExhibitionRouteEstimate`, `RouteWarning.APPROXIMATE_DISTANCE`.

### `WalkingLegKey` (new)

| Field | Rule |
|---|---|
| `from`, `to` | `GeoPoint`s compared at 5 decimal places (about 1 m), so a request and its answer match exactly |

### `WalkingLegBook` (new, in-memory, per app session)

Holds the routed legs the app has received, keyed by `WalkingLegKey` (both directions; the reverse reverses the
geometry). It survives navigation between route screens, so reopening a route in the same session needs no request.
It is cleared when the process ends; the server cache is what makes routes "computed once" across sessions.

### `RoutedLegEstimator(book, fallback = LocalApproximateRouteLegEstimator())` (new, implements `RouteLegEstimator`)

`estimate(from, to)` returns the routed leg from the book (`quality = ROUTED`, geometry = path, distance = path
distance, minutes = `walkingMinutes(distance)`), else the fallback's estimate (`quality = APPROXIMATE`, two-point
geometry). Pure and synchronous, so the planner, `RouteEvaluator`, `RouteTimeline` and the share card use it
unchanged.

### `walkingMinutes(distanceMeters)` (new, pure)

`ceil(distance / 4.5 km/h)`: the same walking pace as today's estimate, applied to the path distance instead of
straight line × 1.25 (research R4).

### `WalkingLegRequest` planning (new, pure)

`legsToRequest(stops: List<RouteWaypoint>, book): List<List<RouteWaypoint>>`. The ordered venue
waypoint batches (at most 6 points each, consecutive and overlapping by one point) covering every leg the book does
not already hold, so editing one stop asks only for the legs that changed (US1 scenario 4).

### `decodePolyline(encoded): List<GeoPoint>` (new, pure)

Precision-5 encoded polyline decoder; malformed input decodes to an empty list (the leg then falls back).

### `NeighborhoodRoutePlanner.retime(route, request, legEstimator): ExhibitionRouteEstimate` (new)

Keeps `route.stops` and their order. Recomputes legs, totals, departure and `stopSchedules` with `legEstimator`.
A stop whose visit no longer fits its hours gets `RouteStopSchedule.fitsHours = false` (new field, default true),
and `warnings` gains `RouteWarning.APPROXIMATE_DISTANCE` only while some leg is still approximate.

## Data layer

- `WalkingRouteApiClient` (Ktor, `functions/v1/walking-route`, `Origin: app://gallr`, publishable key only):
  request/response DTOs per the contract; one malformed leg becomes `unavailable`.
- `WalkingRouteRepository` (interface) / `WalkingRouteRepositoryImpl`:
  `suspend fun resolve(stops: List<RouteWaypoint>): Result<Unit>` (venues only; the leg from the visitor's
  position always uses the on-device estimate, research R3). Plans the batches,
  calls the client, decodes polylines and fills the shared `WalkingLegBook`; exposes `book: StateFlow<Long>` (a
  version that ticks when legs arrive) so ViewModels re-evaluate. Failures are logged as
  `walking_route_resolve_failed` with the exception type only.

## App state

- Route planner (`LocalDiscoveryViewModel`): after `plan()` succeeds, call `resolve(stops)`; on each book
  tick, `retime` the shown route with `RoutedLegEstimator`. Stop selection is never re-run for it.
- Composer (`PersonalRouteComposerViewModel`) and public preview (`PublicRoutesViewModel`): on stop/order change or
  preview open, `resolve`; the existing evaluation is recomputed with `RoutedLegEstimator` on each book tick.
- Share card: distance from the same `RoutedLegEstimator`.
- Map: `RouteMapStop` unchanged; legs passed as `RouteMapLeg(geometry, routed: Boolean)`; the panel and the
  full-screen map draw routed legs solid and approximate legs dashed.
- Full-screen map: a screen-local overlay state `routeMapExpanded: Boolean` held by each route screen (not a
  navigation destination), so closing it leaves the screen beneath untouched (FR-015).
