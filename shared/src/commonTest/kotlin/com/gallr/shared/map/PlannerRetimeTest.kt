package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Spec 093 US1: a planned route is retimed with walking paths without changing which stops it visits. */
class PlannerRetimeTest {
    /** Monday. */
    private val today = LocalDate(2026, 8, 31)
    private val origin = GeoPoint(37.5665, 126.9780)
    private val planner = NeighborhoodRoutePlanner()

    @Test
    fun walkingPathsReplaceTheEstimatesBetweenVenues() {
        val route = plan(exhibition("a", 37.5675, ALL_DAY), exhibition("b", 37.5720, ALL_DAY))
        val (first, second) = route.stops.map { GeoPoint(it.latitude!!, it.longitude!!) }
        val legs = WalkingLegs.EMPTY.plus(first, second, WalkingLeg(4_500, listOf(first, second)))

        val retimed = planner.retime(route, request(), RoutedLegEstimator(legs))

        assertEquals(route.stops.map { it.id }, retimed.stops.map { it.id }, "the same stops in the same order")
        assertEquals(RouteLegQuality.APPROXIMATE, retimed.legs[0].quality, "the leg from the start stays an estimate")
        assertEquals(RouteLegQuality.ROUTED, retimed.legs[1].quality)
        assertEquals(4_500, retimed.legs[1].distanceMeters)
        assertEquals(60, retimed.legs[1].estimatedTravelMinutes)
        assertEquals(retimed.legs.sumOf { it.distanceMeters }, retimed.totalDistanceMeters)
        assertEquals(retimed.legs.sumOf { it.estimatedTravelMinutes }, retimed.estimatedTravelMinutes)
        assertTrue(retimed.stopSchedules[1].arrival > route.stopSchedules[1].arrival, "a longer walk arrives later")
        assertFalse(RouteWarning.APPROXIMATE_DISTANCE in retimed.warnings, "only venue-to-venue estimates count")
    }

    @Test
    fun anEstimatedVenueLegStillCarriesTheNote() {
        val route = plan(exhibition("a", 37.5675, ALL_DAY), exhibition("b", 37.5720, ALL_DAY))

        val retimed = planner.retime(route, request(), RoutedLegEstimator(WalkingLegs.EMPTY))

        assertTrue(RouteWarning.APPROXIMATE_DISTANCE in retimed.warnings)
        assertEquals(route.stops.map { it.id }, retimed.stops.map { it.id })
    }

    @Test
    fun aStopTheLongerWalkPushesPastClosingIsFlaggedNotDropped() {
        val route = plan(exhibition("a", 37.5675, ALL_DAY), exhibition("b", 37.5720, MORNING_ONLY))
        assertTrue(route.stopSchedules.all { it.fitsHours }, "the plan itself fits")
        val (first, second) = route.stops.map { GeoPoint(it.latitude!!, it.longitude!!) }
        val legs = WalkingLegs.EMPTY.plus(first, second, WalkingLeg(9_000, listOf(first, second)))

        val retimed = planner.retime(route, request(), RoutedLegEstimator(legs))

        assertEquals(listOf("a", "b"), retimed.stops.map { it.id })
        assertTrue(retimed.stopSchedules[0].fitsHours)
        assertFalse(retimed.stopSchedules[1].fitsHours)
    }

    @Test
    fun aPlannedRouteWithOnlyEstimatesKeepsTheNote() {
        val route = plan(exhibition("a", 37.5675, ALL_DAY), exhibition("b", 37.5720, ALL_DAY))

        assertTrue(RouteWarning.APPROXIMATE_DISTANCE in route.warnings)
    }

    private fun plan(vararg exhibitions: Exhibition): ExhibitionRouteEstimate {
        val result = planner.plan(exhibitions.toList(), emptySet(), request())
        return assertIs<RoutePlanResult.Success>(result).route
    }

    private fun request() =
        RoutePlanningRequest(
            origin = origin,
            visitDate = today,
            mode = RouteCurationMode.NEIGHBORHOOD,
            stopCount = 2,
            maxRadiusKm = 5.0,
            visitMinutesPerStop = 45,
            startTime = LocalTime(10, 0),
        )

    private fun exhibition(
        id: String,
        latitude: Double,
        hours: String,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 9, 15),
        isFeatured = false,
        latitude = latitude,
        longitude = 126.9780,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = hours,
    )

    private companion object {
        const val ALL_DAY = "10am - 6pm\nMonday - Sunday"
        const val MORNING_ONLY = "10am - 12pm\nMonday - Sunday"
    }
}
