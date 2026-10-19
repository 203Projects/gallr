package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.math.ceil
import kotlin.math.roundToLong

/** The service that drew a walking path; each has its own credit on the map (spec 093 R7). */
enum class WalkingRouter { OPENROUTESERVICE, OSRM }

/** A walking path between two places: its length along the way, the line it follows and who drew it, when known. */
data class WalkingLeg(
    val distanceMeters: Int,
    val geometry: List<GeoPoint>,
    val router: WalkingRouter? = null,
)

/**
 * The walking legs known so far, looked up in either direction (spec 093). Places match at five decimal places,
 * about a metre, so an answer is found by the exact positions it was asked for. Immutable: adding returns a copy,
 * which is what a ViewModel observes.
 */
class WalkingLegs private constructor(
    private val byKey: Map<LegKey, WalkingLeg>,
) {
    val size: Int get() = byKey.size

    operator fun get(
        from: GeoPoint,
        to: GeoPoint,
    ): WalkingLeg? =
        byKey[LegKey(from, to)]
            ?: byKey[LegKey(to, from)]?.let { reverse -> reverse.copy(geometry = reverse.geometry.asReversed()) }

    fun plus(
        from: GeoPoint,
        to: GeoPoint,
        leg: WalkingLeg,
    ): WalkingLegs = WalkingLegs(byKey + (LegKey(from, to) to leg))

    companion object {
        val EMPTY = WalkingLegs(emptyMap())
    }
}

/**
 * Prefers a known walking path, with walking minutes from its length; otherwise [fallback]'s estimate. Synchronous,
 * so route selection and timing stay offline (research R5).
 */
class RoutedLegEstimator(
    private val legs: WalkingLegs,
    private val fallback: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
) : RouteLegEstimator {
    override fun estimate(
        from: GeoPoint,
        to: GeoPoint,
    ): EstimatedLeg {
        val leg = legs[from, to] ?: return fallback.estimate(from, to)
        return EstimatedLeg(
            distanceMeters = leg.distanceMeters,
            travelMinutes = walkingMinutes(leg.distanceMeters),
            geometry = leg.geometry,
            quality = RouteLegQuality.ROUTED,
            router = leg.router,
        )
    }
}

/** Minutes to walk [distanceMeters] at the app's unhurried 4.5 km/h, whichever service drew the path (research R4). */
fun walkingMinutes(distanceMeters: Int): Int =
    ceil(distanceMeters / METERS_PER_KILOMETER / WALKING_SPEED_KMH * MINUTES_PER_HOUR).toInt()

/**
 * The venue waypoints to send for every leg [legs] does not know yet: runs of consecutive unknown legs, at most six
 * points each, overlapping by one so no leg is lost between batches. A place without an exhibition (the visitor's
 * own position) is never sent; legs that start and end at the same place need no path.
 */
fun legsToRequest(
    stops: List<RouteWaypoint>,
    legs: WalkingLegs,
): List<List<RouteWaypoint>> {
    val venues = stops.filter { it.exhibitionId != null }
    val batches = mutableListOf<List<RouteWaypoint>>()
    var run = mutableListOf<RouteWaypoint>()
    venues.zipWithNext { from, to ->
        val needed = LegKey(from.point, to.point).isJourney() && legs[from.point, to.point] == null
        if (needed && run.size < MAX_BATCH_POINTS) {
            if (run.isEmpty()) run += from
            run += to
        } else if (needed) {
            batches += run
            run = mutableListOf(from, to)
        } else if (run.isNotEmpty()) {
            batches += run
            run = mutableListOf()
        }
    }
    if (run.isNotEmpty()) batches += run
    return batches
}

private data class LegKey(
    val fromLatitude: Long,
    val fromLongitude: Long,
    val toLatitude: Long,
    val toLongitude: Long,
) {
    constructor(from: GeoPoint, to: GeoPoint) : this(
        from.latitude.atFiveDecimals(),
        from.longitude.atFiveDecimals(),
        to.latitude.atFiveDecimals(),
        to.longitude.atFiveDecimals(),
    )

    fun isJourney(): Boolean = fromLatitude != toLatitude || fromLongitude != toLongitude
}

private fun Double.atFiveDecimals(): Long = (this * KEY_SCALE).roundToLong()

private const val KEY_SCALE = 1e5
private const val MAX_BATCH_POINTS = 6
private const val METERS_PER_KILOMETER = 1_000.0
private const val MINUTES_PER_HOUR = 60.0
