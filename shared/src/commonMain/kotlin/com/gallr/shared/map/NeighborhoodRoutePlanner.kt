package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.hours.DailyOpening
import com.gallr.shared.hours.WeeklyOpeningHours
import com.gallr.shared.hours.parseOpeningHours
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RouteRelevance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Explicit product objective used to select route stops. */
enum class RouteCurationMode { NEIGHBORHOOD, FOR_YOU, CLOSING_SOON, SAVED }

/** Disclosure attached to route estimates whose data is not authoritative. */
enum class RouteWarning { APPROXIMATE_DISTANCE, HOURS_UNVERIFIED }

/** Whether a route leg is a local estimate or authoritative routed geometry. */
enum class RouteLegQuality { APPROXIMATE, ROUTED }

/** Whether a stop's opening hours were read completely from the venue listing. */
enum class RouteStopHoursStatus { VERIFIED, UNVERIFIED }

/**
 * Validated inputs for a two-to-five-stop neighborhood itinerary.
 *
 * [startTime] is the visitor's local (venue time zone) departure from [origin]. When null the route
 * starts at the earliest opening time among the eligible candidates.
 */
data class RoutePlanningRequest(
    val origin: GeoPoint,
    val visitDate: LocalDate,
    val mode: RouteCurationMode,
    val stopCount: Int,
    val maxRadiusKm: Double,
    val visitMinutesPerStop: Int = 45,
    val startTime: LocalTime? = null,
) {
    init {
        require(stopCount in 2..5) { "stopCount must be between 2 and 5" }
        require(maxRadiusKm > 0.0) { "maxRadiusKm must be positive" }
        require(visitMinutesPerStop >= 0) { "visitMinutesPerStop must not be negative" }
    }
}

/** One estimated leg from the origin or previous exhibition to the next stop. */
data class EstimatedRouteLeg(
    val fromExhibitionId: String?,
    val toExhibitionId: String,
    val distanceMeters: Int,
    val estimatedTravelMinutes: Int,
    val geometry: List<GeoPoint>,
    val quality: RouteLegQuality,
)

/**
 * Timing of one stop. For a stop with known hours `visitStart >= opens` and `visitEnd <= closes` on the
 * visit date; a stop with partial or unknown hours is [RouteStopHoursStatus.UNVERIFIED] and carries no
 * closing time.
 */
data class RouteStopSchedule(
    val exhibitionId: String,
    val arrival: LocalTime,
    val visitStart: LocalTime,
    val visitEnd: LocalTime,
    val closes: LocalTime?,
    val hoursStatus: RouteStopHoursStatus,
)

/** Ordered itinerary and honest local distance/time estimates. */
data class ExhibitionRouteEstimate(
    val mode: RouteCurationMode,
    val stops: List<Exhibition>,
    val legs: List<EstimatedRouteLeg>,
    val totalDistanceMeters: Int,
    val estimatedTravelMinutes: Int,
    val estimatedVisitMinutes: Int,
    val estimatedWaitMinutes: Int,
    val stopSchedules: List<RouteStopSchedule>,
    val warnings: Set<RouteWarning>,
    val recommendationEvidenceByExhibitionId: Map<String, List<RecommendationEvidence>> = emptyMap(),
) {
    init {
        require(stopSchedules.size == stops.size) { "every stop needs a schedule" }
    }

    val totalDistanceKm: Double get() = totalDistanceMeters / 1_000.0
    val estimatedTotalMinutes: Int get() = estimatedTravelMinutes + estimatedVisitMinutes + estimatedWaitMinutes
}

/** Complete route result or an explicit shortage of eligible stops. */
sealed interface RoutePlanResult {
    data class Success(
        val route: ExhibitionRouteEstimate,
    ) : RoutePlanResult

    /**
     * [closedCount] venues were otherwise eligible but closed on the visit date or closing too soon.
     * [available] is zero when every venue with known hours is closed, even if unknown-hours venues remain.
     */
    data class InsufficientCandidates(
        val requested: Int,
        val available: Int,
        val closedCount: Int = 0,
    ) : RoutePlanResult
}

/** Replaceable boundary between route curation and distance/geometry providers. */
fun interface RouteLegEstimator {
    fun estimate(
        from: GeoPoint,
        to: GeoPoint,
    ): EstimatedLeg
}

/** Suspendable whole-route boundary for a future real directions service. */
fun interface DirectionsRouteProvider {
    suspend fun route(
        origin: RouteWaypoint,
        orderedStops: List<RouteWaypoint>,
    ): Result<DirectionsRoute>
}

/** Coordinate plus optional exhibition identity supplied to a directions adapter. */
data class RouteWaypoint(
    val exhibitionId: String?,
    val point: GeoPoint,
)

/** Authoritative routed legs and geometry returned by a directions service. */
data class DirectionsRoute(
    val legs: List<EstimatedRouteLeg>,
    val totalDistanceMeters: Int,
    val totalTravelMinutes: Int,
)

/** Provider-neutral distance and duration for one pair of points. */
data class EstimatedLeg(
    val distanceMeters: Int,
    val travelMinutes: Int,
    val geometry: List<GeoPoint> = emptyList(),
    val quality: RouteLegQuality = RouteLegQuality.APPROXIMATE,
)

/** Offline great-circle estimate adjusted by a disclosed walking circuity factor. */
class LocalApproximateRouteLegEstimator : RouteLegEstimator {
    override fun estimate(
        from: GeoPoint,
        to: GeoPoint,
    ): EstimatedLeg {
        val distanceKm = geographicDistanceKm(from, to) * WALKING_CIRCUITY_MULTIPLIER
        return EstimatedLeg(
            distanceMeters = (distanceKm * 1_000).roundToInt(),
            travelMinutes = ceil(distanceKm / WALKING_SPEED_KMH * 60.0).toInt(),
            geometry = listOf(from, to),
        )
    }
}

/**
 * Selects and distance-orders a small route from organic exhibitions.
 *
 * Guarantees: no stop's venue is known-closed on the visit date in any mode; every stop with known
 * hours is visited inside its opening interval from the start time; when hours permit the unconstrained
 * selection, the same stops and order are returned; [RouteWarning.HOURS_UNVERIFIED] appears only when
 * some stop's hours could not be read completely; when every venue with known hours is closed the
 * result is a shortage rather than a route of unverified stops; results are deterministic and
 * independent of input order.
 */
class NeighborhoodRoutePlanner(
    private val legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
) {
    /**
     * Builds a route without network access or returns the available candidate count.
     *
     * In [RouteCurationMode.FOR_YOU] only exhibitions present in [forYouRelevance] are eligible; the
     * other modes ignore it. Visited exhibitions are excluded upstream by the relevance ranking.
     */
    fun plan(
        exhibitions: List<Exhibition>,
        bookmarkedIds: Set<String>,
        request: RoutePlanningRequest,
        forYouRelevance: List<RouteRelevance> = emptyList(),
    ): RoutePlanResult {
        val relevanceById = forYouRelevance.associateBy { it.exhibition.id }
        val cachedLegEstimator = CachingRouteLegEstimator(legEstimator)
        val inScope =
            exhibitions
                .asSequence()
                .filter { request.visitDate in it.openingDate..it.closingDate }
                .mapNotNull { exhibition ->
                    val point = exhibition.geoPointOrNull() ?: return@mapNotNull null
                    val distance = geographicDistanceKm(request.origin, point)
                    if (distance > request.maxRadiusKm) return@mapNotNull null
                    RouteCandidate(
                        exhibition = exhibition,
                        point = point,
                        distanceFromOriginKm = distance,
                        relevance = relevanceById[exhibition.id],
                        hours = parseOpeningHours(exhibition.hours),
                    )
                }.filter { candidate ->
                    when (request.mode) {
                        RouteCurationMode.NEIGHBORHOOD, RouteCurationMode.CLOSING_SOON -> true
                        RouteCurationMode.FOR_YOU -> candidate.relevance != null
                        RouteCurationMode.SAVED -> candidate.exhibition.id in bookmarkedIds
                    }
                }.sortedWith(candidateComparator(request.mode))
                .distinctBy { it.exhibition.venueIdentity() }
                .toList()
        val startMinutes =
            request.startTime?.minutesOfDay()
                ?: inScope.mapNotNull { it.openingOn(request.visitDate)?.opens?.minutesOfDay() }.minOrNull()
                ?: DEFAULT_START_MINUTES
        val schedule = RouteSchedule(request.visitDate, startMinutes, request.visitMinutesPerStop, cachedLegEstimator)
        val (candidates, closed) =
            inScope.partition { candidate ->
                !candidate.hours.isKnownClosedOn(request.visitDate) &&
                    schedule.canVisitAtAll(request.origin, candidate)
            }
        val closedCount = closed.size
        // When every venue with known hours is closed, the remaining unknown-hours venues are far more
        // likely closed than open: an honest shortage beats a route made only of unverified stops.
        val everyKnownVenueClosed =
            closed.isNotEmpty() && candidates.none { it.openingOn(request.visitDate) != null }
        if (everyKnownVenueClosed) {
            return RoutePlanResult.InsufficientCandidates(request.stopCount, available = 0, closedCount = closedCount)
        }
        if (candidates.size < request.stopCount) {
            return RoutePlanResult.InsufficientCandidates(request.stopCount, candidates.size, closedCount)
        }

        val ordered =
            if (request.mode == RouteCurationMode.FOR_YOU) {
                bestForYouOrdering(
                    origin = request.origin,
                    candidates = candidates,
                    stopCount = request.stopCount,
                    schedule = schedule,
                )
            } else {
                bestOrdering(request.origin, candidates.take(request.stopCount), schedule)
                    ?: firstFitOrdering(request.origin, candidates, request.stopCount, schedule)
            }
        if (ordered == null || ordered.size < request.stopCount) {
            return RoutePlanResult.InsufficientCandidates(request.stopCount, ordered?.size ?: 0, closedCount)
        }
        val timings = schedule.simulate(request.origin, ordered) ?: error("selected ordering must be feasible")

        val legs = mutableListOf<EstimatedRouteLeg>()
        var current = request.origin
        var previousId: String? = null
        ordered.forEach { candidate ->
            val estimate = cachedLegEstimator.estimate(current, candidate.point)
            legs +=
                EstimatedRouteLeg(
                    fromExhibitionId = previousId,
                    toExhibitionId = candidate.exhibition.id,
                    distanceMeters = estimate.distanceMeters,
                    estimatedTravelMinutes = estimate.travelMinutes,
                    geometry = estimate.geometry,
                    quality = estimate.quality,
                )
            current = candidate.point
            previousId = candidate.exhibition.id
        }
        val stopSchedules = timings.map(StopTiming::toSchedule)
        return RoutePlanResult.Success(
            ExhibitionRouteEstimate(
                mode = request.mode,
                stops = ordered.map(RouteCandidate::exhibition),
                legs = legs,
                totalDistanceMeters = legs.sumOf(EstimatedRouteLeg::distanceMeters),
                estimatedTravelMinutes = legs.sumOf(EstimatedRouteLeg::estimatedTravelMinutes),
                estimatedVisitMinutes = request.visitMinutesPerStop * ordered.size,
                estimatedWaitMinutes = timings.sumOf(StopTiming::waitMinutes),
                stopSchedules = stopSchedules,
                warnings =
                    buildSet {
                        if (legs.any { it.quality == RouteLegQuality.APPROXIMATE }) {
                            add(RouteWarning.APPROXIMATE_DISTANCE)
                        }
                        if (stopSchedules.any { it.hoursStatus == RouteStopHoursStatus.UNVERIFIED }) {
                            add(RouteWarning.HOURS_UNVERIFIED)
                        }
                    },
                recommendationEvidenceByExhibitionId =
                    if (request.mode == RouteCurationMode.FOR_YOU) {
                        ordered.associate { candidate ->
                            candidate.exhibition.id to
                                relevanceById.getValue(candidate.exhibition.id).evidence.toList()
                        }
                    } else {
                        emptyMap()
                    },
            ),
        )
    }

    /** Cheapest feasible ordering of exactly these candidates, or null when no ordering fits the hours. */
    private fun bestOrdering(
        origin: GeoPoint,
        candidates: List<RouteCandidate>,
        schedule: RouteSchedule,
    ): List<RouteCandidate>? =
        permutations(candidates)
            .filter { schedule.simulate(origin, it) != null }
            .minWithOrNull(
                compareBy<List<RouteCandidate>> { orderingDistanceMeters(origin, it, schedule.legEstimator) }
                    .thenBy { ordering -> ordering.joinToString("\u001F") { it.exhibition.id } },
            )

    /** Adds candidates in mode order while some ordering of the enlarged set still fits the hours. */
    private fun firstFitOrdering(
        origin: GeoPoint,
        candidates: List<RouteCandidate>,
        stopCount: Int,
        schedule: RouteSchedule,
    ): List<RouteCandidate>? {
        var selected: List<RouteCandidate> = emptyList()
        for (candidate in candidates) {
            val attempt = bestOrdering(origin, selected + candidate, schedule) ?: continue
            selected = attempt
            if (selected.size == stopCount) return selected
        }
        return selected.takeIf { it.isNotEmpty() }
    }

    private fun bestForYouOrdering(
        origin: GeoPoint,
        candidates: List<RouteCandidate>,
        stopCount: Int,
        schedule: RouteSchedule,
    ): List<RouteCandidate>? {
        var beam = listOf(RouteSearchState(emptyList(), 0, 0.0, schedule.startMinutes))
        var deepest = beam
        repeat(stopCount) {
            beam =
                beam
                    .asSequence()
                    .flatMap { state ->
                        val selectedIds = state.ordering.mapTo(mutableSetOf()) { it.exhibition.id }
                        val from = state.ordering.lastOrNull()?.point ?: origin
                        candidates
                            .asSequence()
                            .filterNot { it.exhibition.id in selectedIds }
                            .mapNotNull { candidate ->
                                val leg = schedule.legEstimator.estimate(from, candidate.point)
                                val arrivalMinutes = state.departureMinutes + leg.travelMinutes
                                val timing = schedule.timing(candidate, arrivalMinutes) ?: return@mapNotNull null
                                RouteSearchState(
                                    ordering = state.ordering + candidate,
                                    distanceMeters = state.distanceMeters + leg.distanceMeters,
                                    relevanceCredit = state.relevanceCredit + candidate.relevanceCreditMeters(),
                                    departureMinutes = timing.visitEndMinutes,
                                )
                            }
                    }.sortedWith(routeSearchComparator)
                    .take(ROUTE_SEARCH_BEAM_WIDTH)
                    .toList()
            if (beam.isNotEmpty()) deepest = beam
        }
        return deepest.minWithOrNull(routeSearchComparator)?.ordering?.takeIf { it.isNotEmpty() }
    }

    private fun orderingDistanceMeters(
        origin: GeoPoint,
        ordering: List<RouteCandidate>,
        estimator: RouteLegEstimator,
    ): Int {
        var current = origin
        var total = 0
        ordering.forEach { candidate ->
            total += estimator.estimate(current, candidate.point).distanceMeters
            current = candidate.point
        }
        return total
    }
}

private data class RouteCandidate(
    val exhibition: Exhibition,
    val point: GeoPoint,
    val distanceFromOriginKm: Double,
    val relevance: RouteRelevance?,
    val hours: WeeklyOpeningHours,
) {
    val recommendationScore: Int? get() = relevance?.scoreBasisPoints

    fun openingOn(date: LocalDate): DailyOpening? = hours.openingOn(date)

    /** Distance a candidate is "worth": score credit plus a fixed credit when the match is personal. */
    fun relevanceCreditMeters(): Double {
        val scoreCredit = (recommendationScore ?: 0) * RELEVANCE_CREDIT_METERS_PER_BASIS_POINT
        val personalCredit = if (relevance?.hasPersonalEvidence == true) PERSONAL_RELEVANCE_CREDIT_METERS else 0.0
        return scoreCredit + personalCredit
    }
}

/** Minute-of-day arithmetic for one visit date; `LocalTime` has no duration arithmetic of its own. */
private class RouteSchedule(
    private val visitDate: LocalDate,
    val startMinutes: Int,
    private val visitMinutes: Int,
    val legEstimator: RouteLegEstimator,
) {
    /** Timing for arriving at [candidate] at [arrivalMinutes], or null when its known hours cannot fit the visit. */
    fun timing(
        candidate: RouteCandidate,
        arrivalMinutes: Int,
    ): StopTiming? {
        val opening = candidate.openingOn(visitDate)
        val visitStart = maxOf(arrivalMinutes, opening?.opens?.minutesOfDay() ?: arrivalMinutes)
        val visitEnd = visitStart + visitMinutes
        if (opening != null && visitEnd > opening.closes.minutesOfDay()) return null
        if (visitEnd >= MINUTES_PER_DAY) return null
        return StopTiming(
            candidate = candidate,
            arrivalMinutes = arrivalMinutes,
            visitStartMinutes = visitStart,
            visitEndMinutes = visitEnd,
            closesMinutes = opening?.closes?.minutesOfDay(),
        )
    }

    /** True when at least one visit fits before closing after walking straight from the origin. */
    fun canVisitAtAll(
        origin: GeoPoint,
        candidate: RouteCandidate,
    ): Boolean = timing(candidate, startMinutes + legEstimator.estimate(origin, candidate.point).travelMinutes) != null

    fun simulate(
        origin: GeoPoint,
        ordering: List<RouteCandidate>,
    ): List<StopTiming>? {
        val timings = mutableListOf<StopTiming>()
        var position = origin
        var clock = startMinutes
        for (candidate in ordering) {
            val leg = legEstimator.estimate(position, candidate.point)
            val timing = timing(candidate, clock + leg.travelMinutes) ?: return null
            timings += timing
            position = candidate.point
            clock = timing.visitEndMinutes
        }
        return timings
    }
}

private class StopTiming(
    val candidate: RouteCandidate,
    val arrivalMinutes: Int,
    val visitStartMinutes: Int,
    val visitEndMinutes: Int,
    val closesMinutes: Int?,
) {
    val waitMinutes: Int get() = visitStartMinutes - arrivalMinutes

    fun toSchedule(): RouteStopSchedule =
        RouteStopSchedule(
            exhibitionId = candidate.exhibition.id,
            arrival = arrivalMinutes.toLocalTime(),
            visitStart = visitStartMinutes.toLocalTime(),
            visitEnd = visitEndMinutes.toLocalTime(),
            closes = closesMinutes?.toLocalTime(),
            hoursStatus =
                if (candidate.hours.isVerified) RouteStopHoursStatus.VERIFIED else RouteStopHoursStatus.UNVERIFIED,
        )
}

private data class RouteSearchState(
    val ordering: List<RouteCandidate>,
    val distanceMeters: Int,
    val relevanceCredit: Double,
    val departureMinutes: Int,
) {
    val objective: Double get() = distanceMeters - relevanceCredit
    val stableKey: String get() = ordering.joinToString("\u001F") { it.exhibition.id }
}

private val routeSearchComparator =
    compareBy<RouteSearchState>({ it.objective }, { it.stableKey })

private fun candidateComparator(mode: RouteCurationMode): Comparator<RouteCandidate> =
    when (mode) {
        RouteCurationMode.NEIGHBORHOOD, RouteCurationMode.SAVED -> {
            compareBy<RouteCandidate>(
                { it.distanceFromOriginKm },
                { it.exhibition.closingDate },
                { it.exhibition.id },
            )
        }

        RouteCurationMode.CLOSING_SOON -> {
            compareBy<RouteCandidate>(
                { it.exhibition.closingDate },
                { it.distanceFromOriginKm },
                { it.exhibition.id },
            )
        }

        RouteCurationMode.FOR_YOU -> {
            compareByDescending<RouteCandidate> { it.recommendationScore ?: Int.MIN_VALUE }
                .thenBy { it.distanceFromOriginKm }
                .thenBy { it.exhibition.id }
        }
    }

private fun Exhibition.geoPointOrNull(): GeoPoint? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
}

private fun Exhibition.venueIdentity(): String =
    galleryId?.takeIf(String::isNotBlank) ?: listOf(
        venueNameEn.ifBlank { venueNameKo }.trim().lowercase(),
        cityEn.ifBlank { cityKo }.trim().lowercase(),
        regionEn.ifBlank { regionKo }.trim().lowercase(),
        addressEn.ifBlank { addressKo }.trim().lowercase(),
    ).joinToString(":")

private fun LocalTime.minutesOfDay(): Int = toSecondOfDay() / SECONDS_PER_MINUTE

private fun Int.toLocalTime(): LocalTime =
    LocalTime.fromSecondOfDay(coerceIn(0, MINUTES_PER_DAY - 1) * SECONDS_PER_MINUTE)

private class CachingRouteLegEstimator(
    private val delegate: RouteLegEstimator,
) : RouteLegEstimator {
    private val cache = mutableMapOf<Pair<GeoPoint, GeoPoint>, EstimatedLeg>()

    override fun estimate(
        from: GeoPoint,
        to: GeoPoint,
    ): EstimatedLeg = cache.getOrPut(from to to) { delegate.estimate(from, to) }
}

private fun <T> permutations(values: List<T>): Sequence<List<T>> =
    sequence {
        if (values.isEmpty()) {
            yield(emptyList())
        } else {
            values.forEachIndexed { index, value ->
                val remaining = values.toMutableList().also { it.removeAt(index) }
                for (suffix in permutations(remaining)) yield(listOf(value) + suffix)
            }
        }
    }

private const val WALKING_CIRCUITY_MULTIPLIER = 1.25
private const val WALKING_SPEED_KMH = 4.5
private const val ROUTE_SEARCH_BEAM_WIDTH = 64
private const val RELEVANCE_CREDIT_METERS_PER_BASIS_POINT = 0.5

/** A personal match beats a filler unless it costs more than this much extra walking. */
private const val PERSONAL_RELEVANCE_CREDIT_METERS = 1_500.0

/** Nominal start when neither the request nor any candidate supplies a time. */
private const val DEFAULT_START_MINUTES = 10 * 60
private const val MINUTES_PER_DAY = 24 * 60
private const val SECONDS_PER_MINUTE = 60
