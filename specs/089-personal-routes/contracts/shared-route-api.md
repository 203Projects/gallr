# Contract: shared route API (`shared/commonMain`)

Public interfaces the app layer depends on. Names are indicative; the contract is the behaviour. Each interface is documented at its definition (constitution Quality Standards).

## Package `com.gallr.shared.map` (internal timeline, E-D3)

`internal` route timeline extracted from `NeighborhoodRoutePlanner`: minute arithmetic, leg walk and departure shift for a visit date, returning per-stop timing with a fit flag. The planner keeps its public API and behaviour; `NeighborhoodRoutePlannerTest` passes unedited, and a new test asserts a planner route and the same stops evaluated in order produce equal schedules.

## Package `com.gallr.shared.route`

```kotlin
data class PersonalRoute(...)          // see data-model.md
data class PersonalRouteStop(...)
sealed interface RouteStopVerdict { Open; ClosedOnPlannedDay; ArrivesAfterClose; VisitCutShort(minutes); NotYetOpen(openingDate); Ended; Unavailable; HoursUnknown }

object RouteEvaluator {
    /** Evaluates stops in the author's order; never reorders or drops. */
    fun evaluate(
        stops: List<PersonalRouteStop>,
        exhibitionsById: Map<String, Exhibition>,
        origin: GeoPoint?,
        now: Instant,
        zone: TimeZone,          // Asia/Seoul
        legEstimator: RouteLegEstimator,
    ): PersonalRouteEvaluation
}
```

Fixed-clock tests on the published-catalogue fixture: before first opening (anchor at opening, departure later than now shown), mid-day (anchor now, no departure line), after close (rollover with 내일 label), closed today (named weekday), rollover bound 7 days and exhibition end, first stop unknown/ended/unavailable (no rollover), not-yet-open stops, 23:59 and 00:01 KST, each verdict.

## Repositories (`com.gallr.shared.repository`)

```kotlin
interface PersonalRouteDraftRepository {
    val draft: Flow<PersonalRouteDraft>
    suspend fun append(exhibition: Exhibition): AppendResult          // Added(count) | Full | Duplicate | MissingLocation
    suspend fun move(from: Int, to: Int)
    suspend fun remove(position: Int): UndoToken
    suspend fun undo(token: UndoToken): UndoResult                    // Restored | Full | Duplicate | Expired
    suspend fun rename(name: String)
    suspend fun replace(newDraft: PersonalRouteDraft)
    suspend fun detach()
    suspend fun setPending(kind: PendingKind)
    suspend fun clearPending()
    suspend fun acknowledgeSave(draftId: String, sentRevision: Long, saved: PersonalRoute, ownerAccountId: String)
}

interface PersonalRouteRepository {                                   // Result<T> boundary
    suspend fun save(route: PersonalRoute): Result<PersonalRoute>     // ids only are sent
    suspend fun publish(id: String): Result<PersonalRoute>
    suspend fun listMine(): Result<List<PersonalRouteSummary>>
    suspend fun loadMine(id: String): Result<PersonalRoute>
    suspend fun delete(id: String): Result<Unit>
}
```

`PersonalRouteRepository` returns `Result` failures as `PersonalRouteException`, read with `Throwable.routeFailure()`: `Unauthenticated`, `NotOwner`, `Revoked`, `InvalidName`, `InvalidStops`, `MissingLocation(ids)`, `UnavailableStops(ids)`, `NotFound`, `Network`, `Unexpected`. Implemented over `PersonalRouteRemoteSource`, whose Ktor implementation `PersonalRouteApiClient` sends a bearer token (pattern of `MyGallrAccountApiClient`) and maps the database's `personal_route_*` messages.

## Orchestration (`composeApp/commonMain`)

`RouteShareOrchestrator` (one small type used by both ViewModels, E-D9): given the current draft, save if unsaved, publish if unpublished, then return a share payload (card model from the save response, link `https://gallrmap.com/route/{id}?s=share&v={revision}`). Handles signed-out by setting a pending action and requesting sign-in; consumes a matching pending action on the next signed-in state within 30 minutes (RR1, RO2).

`PersonalRouteComposerViewModel` and `MyRoutesViewModel` expose `StateFlow` UI state per DESIGN.md states (DR-D7, DR-D10); every edit goes through the draft repository (RR3).

## Analytics

`MobileAnalyticsEventName` gains `route_draft_started`, `route_published`, `route_shared`; the `mobile-analytics` handler allow-list and tests follow, with the 10 existing cases unedited (E-D6, E-D11).

## Public routes additions (User Stories 7–11)

```kotlin
interface PersonalRouteDraftRepository {
    // ...089 operations above...
    /** Applies a public route copy only if the draft is still the one the reader confirmed (P8). */
    suspend fun copyIntoDraft(name: String, stops: List<PersonalRouteStop>, expectedDraftId: String, expectedRevision: Long): CopyIntoDraftResult
}

interface PersonalRouteRepository {
    // ...089 operations above; listMine rows carry listing fields...
    suspend fun requestListing(id: String): Result<PersonalRouteSummary>
    suspend fun withdrawListing(id: String): Result<PersonalRouteSummary>
    suspend fun listPublic(limit: Int = 10): Result<List<PublicRouteSummary>>
    suspend fun loadPublicStops(id: String): Result<PersonalRoute?>   // get_published_route; listing fields come from the list row
    suspend fun copyPublic(id: String): Result<PublicRoute>          // counts the copy; returns stops to seed
    suspend fun report(id: String, reason: RouteReportReason): Result<Unit>
}
```

New failures: `ListingRequiresPublished`, `ListingInvalidTransition`, `NotListed`, `ReportExists`, `ReportOwnRoute`, mapped from the messages in [public-routes-functions.md](public-routes-functions.md).

`PublicRoutesViewModel` (`composeApp/commonMain`) owns the 추천 동선 state (loading, rows, expanded, error), the preview (route, reference day, own-route flag, copy and report busy states) and the copy flow: replace confirm → `copyPublic` → `copyIntoDraft` → open composer, with the outcomes in spec User Story 10; a signed-out copy sets `PendingKind.COPY` and resumes after sign-in (P11). `MyRoutesViewModel` gains request and withdraw actions with busy and snackbar state; `PersonalRouteComposerViewModel` exposes the listed-route edit warning (DD15). Row and status wording lives in presentation functions with tests (D24, D25).

Analytics: `MobileAnalyticsEventName` gains `public_routes_viewed` with dimension `rows_shown` 1–10 (P12); the event constraint and `mobile-analytics` handler allow-list follow with existing cases unedited.

