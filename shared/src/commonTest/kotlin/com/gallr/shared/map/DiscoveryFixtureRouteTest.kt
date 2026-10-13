package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.fixture.DiscoveryFixture
import com.gallr.shared.hours.parseOpeningHours
import com.gallr.shared.recommendation.LocalExhibitionRecommender
import com.gallr.shared.recommendation.RouteRelevanceContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Success-criteria checks for routes on the published catalogue snapshot (spec 088). */
class DiscoveryFixtureRouteTest {
    private val planner = NeighborhoodRoutePlanner()
    private val index = LocalExhibitionRecommender().prepare(DiscoveryFixture.exhibitions)

    /** SC-001: a three-stop For You route with no history succeeds from the measured centres. */
    @Test
    fun forYouRouteSucceedsFromMeasuredNeighbourhoods() {
        listOf(
            "Hannam" to DiscoveryFixture.HANNAM,
            "Seongsu" to DiscoveryFixture.SEONGSU,
            "Cheongdam" to DiscoveryFixture.CHEONGDAM,
        ).forEach { (name, origin) ->
            val openVenues = openVenuesWithin(origin, ROUTE_RADIUS_KM)
            assertTrue(openVenues >= STOP_COUNT, "$name has only $openVenues open venues within $ROUTE_RADIUS_KM km")

            val relevance =
                index.rankRouteCandidates(
                    RouteRelevanceContext(
                        origin = origin,
                        maxDistanceKm = ROUTE_RADIUS_KM,
                        today = DiscoveryFixture.referenceDate,
                    ),
                )
            val result =
                planner.plan(
                    exhibitions = DiscoveryFixture.exhibitions,
                    bookmarkedIds = emptySet(),
                    request =
                        RoutePlanningRequest(
                            origin = origin,
                            visitDate = DiscoveryFixture.referenceDate,
                            mode = RouteCurationMode.FOR_YOU,
                            stopCount = STOP_COUNT,
                            maxRadiusKm = ROUTE_RADIUS_KM,
                        ),
                    forYouRelevance = relevance,
                )

            val route = assertIs<RoutePlanResult.Success>(result, "$name: $result").route
            assertEquals(STOP_COUNT, route.stops.size, name)
            val distinctVenues =
                route.stops
                    .mapNotNull { it.galleryId }
                    .distinct()
                    .size
            assertEquals(STOP_COUNT, distinctVenues, name)
        }
    }

    /** SC-002: no route stop is closed on the visit date or finished after its closing time. */
    @Test
    fun noStopClosedOrLateAcrossWeek() {
        val savedIds =
            DiscoveryFixture.exhibitions
                .filter { exhibition ->
                    val point = GeoPoint(exhibition.latitude!!, exhibition.longitude!!)
                    geographicDistanceKm(DiscoveryFixture.HANNAM, point) <= 3.0
                }.map { it.id }
                .sorted()
                .take(5)
                .toSet()
        var successes = 0

        for (dayOffset in 0..6) {
            val visitDate = LocalDate.fromEpochDays(DiscoveryFixture.referenceDate.toEpochDays() + dayOffset)
            for (hour in 10..17) {
                val startTime = LocalTime(hour, 0)
                for (origin in listOf(DiscoveryFixture.HANNAM, DiscoveryFixture.SAMCHEONG)) {
                    for (mode in RouteCurationMode.entries) {
                        val request =
                            RoutePlanningRequest(
                                origin = origin,
                                visitDate = visitDate,
                                mode = mode,
                                stopCount = STOP_COUNT,
                                maxRadiusKm = ROUTE_RADIUS_KM,
                                startTime = startTime,
                            )
                        val relevance =
                            if (mode == RouteCurationMode.FOR_YOU) {
                                index.rankRouteCandidates(
                                    RouteRelevanceContext(
                                        bookmarkedExhibitionIds = savedIds,
                                        origin = origin,
                                        maxDistanceKm = ROUTE_RADIUS_KM,
                                        today = visitDate,
                                    ),
                                )
                            } else {
                                emptyList()
                            }
                        val result = planner.plan(DiscoveryFixture.exhibitions, savedIds, request, relevance)
                        val route = (result as? RoutePlanResult.Success)?.route ?: continue
                        successes += 1
                        assertEquals(route.stops.map { it.id }, route.stopSchedules.map { it.exhibitionId })
                        route.stops.zip(route.stopSchedules).forEach { (stop, schedule) ->
                            val hours = parseOpeningHours(stop.hours)
                            val label = "$mode $visitDate $startTime ${stop.id}"
                            assertFalse(hours.isKnownClosedOn(visitDate), label)
                            val opening = hours.openingOn(visitDate)
                            if (opening != null) {
                                assertTrue(schedule.visitStart >= opening.opens, label)
                                assertTrue(schedule.visitEnd <= opening.closes, label)
                            }
                            val expectedStatus =
                                if (hours.isVerified) RouteStopHoursStatus.VERIFIED else RouteStopHoursStatus.UNVERIFIED
                            assertEquals(expectedStatus, schedule.hoursStatus, label)
                        }
                    }
                }
            }
        }
        assertTrue(successes > 100, "only $successes successful routes in the sweep")
    }

    private fun openVenuesWithin(
        origin: GeoPoint,
        radiusKm: Double,
    ): Int =
        DiscoveryFixture.exhibitions
            .filter { DiscoveryFixture.referenceDate in it.openingDate..it.closingDate }
            .filter { exhibition ->
                val point = GeoPoint(exhibition.latitude!!, exhibition.longitude!!)
                geographicDistanceKm(origin, point) <= radiusKm
            }.mapNotNull { it.galleryId }
            .distinct()
            .size

    private companion object {
        const val ROUTE_RADIUS_KM = 5.0
        const val STOP_COUNT = 3
    }
}
