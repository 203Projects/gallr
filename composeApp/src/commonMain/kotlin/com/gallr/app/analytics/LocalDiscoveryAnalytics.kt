package com.gallr.app.analytics

import com.gallr.shared.analytics.AnalyticsRouteMode
import com.gallr.shared.analytics.DistanceBand
import com.gallr.shared.analytics.DurationBand
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.RouteCurationMode

/** Closed coarse route summary; it cannot represent origin, geometry, or stop identity. */
internal data class RouteAnalyticsSummary(
    val mode: AnalyticsRouteMode,
    val stopCount: Int,
    val distanceBand: DistanceBand,
    val durationBand: DurationBand,
)

internal enum class RouteMapHandoffAction {
    START_ROUTE,
    OPEN_STOP,
}

/** Route interaction policy deliberately has no field capable of carrying a stop identity. */
internal data class RouteMapHandoffAnalyticsDecision(
    val recordsRouteStarted: Boolean,
    val recordsItemEvent: Boolean = false,
)

internal fun routeMapHandoffAnalyticsDecision(action: RouteMapHandoffAction): RouteMapHandoffAnalyticsDecision =
    RouteMapHandoffAnalyticsDecision(
        recordsRouteStarted = action == RouteMapHandoffAction.START_ROUTE,
    )

/** One instance belongs to one mounted recommendation-screen visit. */
internal class RecommendationDisplayAnalyticsGate {
    private var recorded = false

    fun shouldRecord(): Boolean {
        if (recorded) return false
        recorded = true
        return true
    }
}

internal const val ROUTE_DETAIL_ANALYTICS_SUPPRESSED = true

internal fun ExhibitionRouteEstimate.toAnalyticsSummary(): RouteAnalyticsSummary =
    RouteAnalyticsSummary(
        mode = mode.toAnalyticsRouteMode(),
        stopCount = stops.size,
        distanceBand = distanceBand(totalDistanceMeters),
        durationBand = durationBand(estimatedTotalMinutes),
    )

internal fun RouteCurationMode.toAnalyticsRouteMode(): AnalyticsRouteMode =
    when (this) {
        RouteCurationMode.NEIGHBORHOOD -> AnalyticsRouteMode.NEIGHBORHOOD
        RouteCurationMode.FOR_YOU -> AnalyticsRouteMode.FOR_YOU
        RouteCurationMode.CLOSING_SOON -> AnalyticsRouteMode.CLOSING_SOON
        RouteCurationMode.SAVED -> AnalyticsRouteMode.SAVED
    }

internal fun distanceBand(distanceMeters: Int): DistanceBand =
    when {
        distanceMeters < 2_000 -> DistanceBand.UNDER_TWO_KM
        distanceMeters <= 5_000 -> DistanceBand.TWO_TO_FIVE_KM
        else -> DistanceBand.OVER_FIVE_KM
    }

internal fun durationBand(durationMinutes: Int): DurationBand =
    when {
        durationMinutes < 120 -> DurationBand.UNDER_TWO_HOURS
        durationMinutes <= 240 -> DurationBand.TWO_TO_FOUR_HOURS
        else -> DurationBand.OVER_FOUR_HOURS
    }
