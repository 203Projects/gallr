package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import kotlinx.datetime.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Explicit product objective used to select route stops. */
enum class RouteCurationMode { NEIGHBORHOOD, FOR_YOU, CLOSING_SOON, SAVED }

/** Disclosure attached to route estimates whose data is not authoritative. */
enum class RouteWarning { APPROXIMATE_DISTANCE, HOURS_UNVERIFIED }

/** Whether a route leg is a local estimate or authoritative routed geometry. */
enum class RouteLegQuality { APPROXIMATE, ROUTED }

/** Validated inputs for a two-to-five-stop neighborhood itinerary. */
data class RoutePlanningRequest(
    val origin: GeoPoint,
    val visitDate: LocalDate,
    val mode: RouteCurationMode,
    val stopCount: Int,
    val maxRadiusKm: Double,
    val visitMinutesPerStop: Int = 45,
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

/** Ordered itinerary and honest local distance/time estimates. */
data class ExhibitionRouteEstimate(
    val mode: RouteCurationMode,
    val stops: List<Exhibition>,
    val legs: List<EstimatedRouteLeg>,
    val totalDistanceMeters: Int,
    val estimatedTravelMinutes: Int,
    val estimatedVisitMinutes: Int,
    val warnings: Set<RouteWarning>,
    val recommendationEvidenceByExhibitionId: Map<String, List<RecommendationEvidence>> = emptyMap(),
) {
    val totalDistanceKm: Double get() = totalDistanceMeters / 1_000.0
    val estimatedTotalMinutes: Int get() = estimatedTravelMinutes + estimatedVisitMinutes
}

/** Complete route result or an explicit shortage of eligible stops. */
sealed interface RoutePlanResult {
    data class Success(
        val route: ExhibitionRouteEstimate,
    ) : RoutePlanResult

    data class InsufficientCandidates(
        val requested: Int,
        val available: Int,
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

/** Selects and distance-orders a small route from organic exhibitions. */
class NeighborhoodRoutePlanner(
    private val legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
) {
    /** Builds a route without network access or returns the available candidate count. */
    fun plan(
        exhibitions: List<Exhibition>,
        recommendations: List<ExhibitionRecommendation>,
        bookmarkedIds: Set<String>,
        request: RoutePlanningRequest,
    ): RoutePlanResult {
        val recommendationsById = recommendations.associateBy { it.exhibition.id }
        val recommendationScores =
            recommendationsById.mapValues { (_, recommendation) ->
                recommendation.scoreBasisPoints
            }
        val cachedLegEstimator = CachingRouteLegEstimator(legEstimator)
        val candidates =
            exhibitions
                .asSequence()
                .filter { request.visitDate in it.openingDate..it.closingDate }
                .mapNotNull { exhibition ->
                    val point = exhibition.geoPointOrNull() ?: return@mapNotNull null
                    val distance = geographicDistanceKm(request.origin, point)
                    if (distance > request.maxRadiusKm) return@mapNotNull null
                    RouteCandidate(exhibition, point, distance, recommendationScores[exhibition.id])
                }.filter { candidate ->
                    when (request.mode) {
                        RouteCurationMode.NEIGHBORHOOD, RouteCurationMode.CLOSING_SOON -> true
                        RouteCurationMode.FOR_YOU -> candidate.recommendationScore != null
                        RouteCurationMode.SAVED -> candidate.exhibition.id in bookmarkedIds
                    }
                }.sortedWith(candidateComparator(request.mode))
                .distinctBy { it.exhibition.venueIdentity() }
                .toList()
        if (candidates.size < request.stopCount) {
            return RoutePlanResult.InsufficientCandidates(request.stopCount, candidates.size)
        }

        val ordered =
            if (request.mode == RouteCurationMode.FOR_YOU) {
                bestForYouOrdering(
                    origin = request.origin,
                    candidates = candidates,
                    stopCount = request.stopCount,
                    estimator = cachedLegEstimator,
                )
            } else {
                bestOrdering(
                    origin = request.origin,
                    candidates = candidates.take(request.stopCount),
                    estimator = cachedLegEstimator,
                )
            }
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
        return RoutePlanResult.Success(
            ExhibitionRouteEstimate(
                mode = request.mode,
                stops = ordered.map(RouteCandidate::exhibition),
                legs = legs,
                totalDistanceMeters = legs.sumOf(EstimatedRouteLeg::distanceMeters),
                estimatedTravelMinutes = legs.sumOf(EstimatedRouteLeg::estimatedTravelMinutes),
                estimatedVisitMinutes = request.visitMinutesPerStop * ordered.size,
                warnings =
                    buildSet {
                        if (legs.any { it.quality == RouteLegQuality.APPROXIMATE }) {
                            add(RouteWarning.APPROXIMATE_DISTANCE)
                        }
                        add(RouteWarning.HOURS_UNVERIFIED)
                    },
                recommendationEvidenceByExhibitionId =
                    if (request.mode == RouteCurationMode.FOR_YOU) {
                        ordered.associate { candidate ->
                            candidate.exhibition.id to
                                recommendationsById.getValue(candidate.exhibition.id).evidence.toList()
                        }
                    } else {
                        emptyMap()
                    },
            ),
        )
    }

    private fun bestOrdering(
        origin: GeoPoint,
        candidates: List<RouteCandidate>,
        estimator: RouteLegEstimator,
    ): List<RouteCandidate> =
        permutations(candidates)
            .minWithOrNull(
                compareBy<List<RouteCandidate>> { orderingDistanceMeters(origin, it, estimator) }
                    .thenBy { ordering -> ordering.joinToString("\u001F") { it.exhibition.id } },
            ).orEmpty()

    private fun bestForYouOrdering(
        origin: GeoPoint,
        candidates: List<RouteCandidate>,
        stopCount: Int,
        estimator: RouteLegEstimator,
    ): List<RouteCandidate> {
        var beam = listOf(RouteSearchState(emptyList(), 0, 0.0))
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
                            .map { candidate ->
                                val leg = estimator.estimate(from, candidate.point)
                                RouteSearchState(
                                    ordering = state.ordering + candidate,
                                    distanceMeters = state.distanceMeters + leg.distanceMeters,
                                    relevanceCredit =
                                        state.relevanceCredit +
                                            (candidate.recommendationScore ?: 0) *
                                            RELEVANCE_CREDIT_METERS_PER_BASIS_POINT,
                                )
                            }
                    }.sortedWith(routeSearchComparator)
                    .take(ROUTE_SEARCH_BEAM_WIDTH)
                    .toList()
        }
        return beam.minWithOrNull(routeSearchComparator)?.ordering.orEmpty()
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
    val recommendationScore: Int?,
)

private data class RouteSearchState(
    val ordering: List<RouteCandidate>,
    val distanceMeters: Int,
    val relevanceCredit: Double,
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
