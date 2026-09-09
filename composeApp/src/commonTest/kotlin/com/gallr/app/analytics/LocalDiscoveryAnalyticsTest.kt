package com.gallr.app.analytics

import com.gallr.shared.analytics.AnalyticsRouteMode
import com.gallr.shared.analytics.DistanceBand
import com.gallr.shared.analytics.DurationBand
import com.gallr.shared.map.RouteCurationMode
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDiscoveryAnalyticsTest {
    @Test
    fun `route modes map without user or location data`() {
        assertEquals(AnalyticsRouteMode.NEIGHBORHOOD, RouteCurationMode.NEIGHBORHOOD.toAnalyticsRouteMode())
        assertEquals(AnalyticsRouteMode.FOR_YOU, RouteCurationMode.FOR_YOU.toAnalyticsRouteMode())
        assertEquals(AnalyticsRouteMode.CLOSING_SOON, RouteCurationMode.CLOSING_SOON.toAnalyticsRouteMode())
        assertEquals(AnalyticsRouteMode.SAVED, RouteCurationMode.SAVED.toAnalyticsRouteMode())
    }

    @Test
    fun `distance bands preserve exact boundaries without raw distance`() {
        assertEquals(DistanceBand.UNDER_TWO_KM, distanceBand(1_999))
        assertEquals(DistanceBand.TWO_TO_FIVE_KM, distanceBand(2_000))
        assertEquals(DistanceBand.TWO_TO_FIVE_KM, distanceBand(5_000))
        assertEquals(DistanceBand.OVER_FIVE_KM, distanceBand(5_001))
    }

    @Test
    fun `duration bands use total route minutes`() {
        assertEquals(DurationBand.UNDER_TWO_HOURS, durationBand(119))
        assertEquals(DurationBand.TWO_TO_FOUR_HOURS, durationBand(120))
        assertEquals(DurationBand.TWO_TO_FOUR_HOURS, durationBand(240))
        assertEquals(DurationBand.OVER_FOUR_HOURS, durationBand(241))
    }

    @Test
    fun `route item actions cannot emit identity or start a route implicitly`() {
        val openStop = routeMapHandoffAnalyticsDecision(RouteMapHandoffAction.OPEN_STOP)
        val explicitStart = routeMapHandoffAnalyticsDecision(RouteMapHandoffAction.START_ROUTE)

        assertEquals(false, openStop.recordsRouteStarted)
        assertEquals(false, openStop.recordsItemEvent)
        assertEquals(true, explicitStart.recordsRouteStarted)
        assertEquals(false, explicitStart.recordsItemEvent)
        assertEquals(true, ROUTE_DETAIL_ANALYTICS_SUPPRESSED)
    }

    @Test
    fun `recommendation display records once per mounted screen visit`() {
        val firstVisit = RecommendationDisplayAnalyticsGate()

        assertEquals(true, firstVisit.shouldRecord())
        assertEquals(false, firstVisit.shouldRecord())
        assertEquals(false, firstVisit.shouldRecord())
        assertEquals(true, RecommendationDisplayAnalyticsGate().shouldRecord())
    }
}
