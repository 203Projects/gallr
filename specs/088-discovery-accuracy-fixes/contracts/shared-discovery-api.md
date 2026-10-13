# Contract: shared discovery API changes

The public surface of `shared/commonMain` consumed by `composeApp`. KDoc at each definition must state
these contracts (constitution, Quality Standards).

## `com.gallr.shared.hours`

```kotlin
enum class OpeningHoursCompleteness { COMPLETE, PARTIAL, UNKNOWN }

data class DailyOpening(val opens: LocalTime, val closes: LocalTime)   // require(closes > opens)

data class WeeklyOpeningHours(
    val byDay: Map<DayOfWeek, DailyOpening>,
    val completeness: OpeningHoursCompleteness,
) {
    val isVerified: Boolean
    fun openingOn(date: LocalDate): DailyOpening?
    fun isKnownClosedOn(date: LocalDate): Boolean   // true only for a COMPLETE reading that lists no hours that weekday
}

/** Total, deterministic reading of free-text venue hours. Never throws. */
fun parseOpeningHours(text: String?): WeeklyOpeningHours
```

## `com.gallr.shared.recommendation`

```kotlin
sealed interface RecommendationEvidence {
    // existing entries unchanged
    data object Saved : RecommendationEvidence           // route candidates only
}

data class RouteRelevanceContext(
    val bookmarkedExhibitionIds: Set<String> = emptySet(),
    val visits: List<ExhibitionVisit> = emptyList(),
    val followedGalleries: List<FollowedGallery> = emptyList(),
    val origin: GeoPoint,
    val maxDistanceKm: Double,                            // require(> 0)
    val today: LocalDate,
)

data class RouteRelevance(
    val exhibition: Exhibition,
    val scoreBasisPoints: Int,                            // 0..10_000
    val evidence: List<RecommendationEvidence>,           // 0..2
    val hasPersonalEvidence: Boolean,
)

interface ExhibitionRecommendationIndex {                // was fun interface
    fun recommend(context: RecommendationContext): List<ExhibitionRecommendation>
    fun rankRouteCandidates(context: RouteRelevanceContext): List<RouteRelevance>
}
```

Guarantees of `rankRouteCandidates`:
1. Returns every catalogue-visible exhibition with valid coordinates within `maxDistanceKm` of `origin`,
   except visited ones.
2. Bookmarked exhibitions are included; their first evidence entry is `Saved`, and they carry no
   inferred taste evidence.
3. Never reads promotion state.
4. Deterministic: the same inputs give the same list, ordered by score descending then ID.

Guarantees kept for `recommend`: at most `limit` results, saved and visited excluded, one or two evidence
entries, diversity pass, no `Saved` evidence. Ordering: candidates with personal evidence first, then score
descending, then id.

## `com.gallr.shared.map`

```kotlin
data class RoutePlanningRequest(
    /* existing fields */
    val startTime: LocalTime? = null,
)

enum class RouteStopHoursStatus { VERIFIED, UNVERIFIED }

data class RouteStopSchedule(
    val exhibitionId: String,
    val arrival: LocalTime,
    val visitStart: LocalTime,
    val visitEnd: LocalTime,
    val closes: LocalTime?,
    val hoursStatus: RouteStopHoursStatus,
)

data class ExhibitionRouteEstimate(
    /* existing fields */
    val stopSchedules: List<RouteStopSchedule>,
    val estimatedWaitMinutes: Int,
)   // estimatedTotalMinutes = travel + visit + wait

sealed interface RoutePlanResult {
    data class Success(val route: ExhibitionRouteEstimate) : RoutePlanResult
    data class InsufficientCandidates(
        val requested: Int,
        val available: Int,
        val closedCount: Int = 0,
    ) : RoutePlanResult
}

class NeighborhoodRoutePlanner(/* unchanged */) {
    fun plan(
        exhibitions: List<Exhibition>,
        bookmarkedIds: Set<String>,
        request: RoutePlanningRequest,
        forYouRelevance: List<RouteRelevance> = emptyList(),
    ): RoutePlanResult
}
```

Planner guarantees:
1. No stop's venue is known-closed on `visitDate`, in any mode.
2. For every stop with known hours, `visitEnd <= closes` and `visitStart >= opens`.
3. When hours permit today's selection, the same stops and order are returned as before this feature.
4. `HOURS_UNVERIFIED` appears only if some stop is `UNVERIFIED`.
5. For You prefers `hasPersonalEvidence` candidates and fills from the rest, so it returns a route whenever
   `stopCount` distinct open venues exist in the radius.
6. Deterministic and order-independent of the input lists.
7. When at least one venue in scope has known hours and all of them are closed or closing too soon, the
   result is `InsufficientCandidates(available = 0, closedCount)` rather than a route of unknown-hours stops.

## Copy (composeApp presentation)

| Key | KO | EN |
|---|---|---|
| Saved evidence | `저장한 전시` | `SAVED` |
| Unverified stop hours, raw text present | `운영 시간 미확인 · {raw}` | `HOURS NOT VERIFIED · {raw}` |
| Unverified stop hours, no text | `운영 시간 미확인` (unchanged) | `HOURS NOT VERIFIED` (unchanged) |
| Verified stop hours | `운영 시간 · {raw}` (unchanged) | `HOURS · {raw}` (unchanged) |
| Shortage with `closedCount > 0`, appended sentence | `주변 {n}곳은 지금 문을 닫았거나 곧 닫습니다.` | `1 nearby venue is closed or closing soon.` / `{n} nearby venues are closed or closing soon.` |
| Shortage with `available == 0` and `closedCount > 0` (whole message) | `지금 열려 있는 전시가 없습니다. 주변 {n}곳은 문을 닫았거나 곧 닫습니다.` | `No exhibitions are open right now. {n} nearby venue(s) …` |
