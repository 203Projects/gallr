package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 093: routed walking legs, the estimator that prefers them, and which legs to ask for (data-model.md). */
class WalkingLegsTest {
    private val seongsu = GeoPoint(37.54450, 127.05600)
    private val bridge = GeoPoint(37.53900, 127.03500)
    private val hannam = GeoPoint(37.53450, 127.00100)
    private val itaewon = GeoPoint(37.53400, 126.99400)

    @Test
    fun aLegIsFoundInBothDirectionsWithItsPathReversed() {
        val legs = WalkingLegs.EMPTY.plus(seongsu, hannam, WalkingLeg(5_230, listOf(seongsu, bridge, hannam)))

        assertEquals(listOf(seongsu, bridge, hannam), legs[seongsu, hannam]?.geometry)
        assertEquals(listOf(hannam, bridge, seongsu), legs[hannam, seongsu]?.geometry)
        assertEquals(5_230, legs[hannam, seongsu]?.distanceMeters)
        assertNull(legs[seongsu, itaewon])
    }

    @Test
    fun pointsMatchAtFiveDecimals() {
        val legs = WalkingLegs.EMPTY.plus(seongsu, hannam, WalkingLeg(5_230, listOf(seongsu, hannam)))

        assertEquals(5_230, legs[GeoPoint(37.544501, 127.056004), GeoPoint(37.534499, 127.001001)]?.distanceMeters)
        assertNull(legs[GeoPoint(37.54460, 127.05600), hannam], "ten metres away is another place")
    }

    @Test
    fun theEstimatorUsesTheWalkingPathWhenItHasOne() {
        val legs = WalkingLegs.EMPTY.plus(seongsu, hannam, WalkingLeg(5_230, listOf(seongsu, bridge, hannam)))
        val estimator = RoutedLegEstimator(legs)

        val routed = estimator.estimate(seongsu, hannam)
        assertEquals(RouteLegQuality.ROUTED, routed.quality)
        assertEquals(5_230, routed.distanceMeters)
        assertEquals(walkingMinutes(5_230), routed.travelMinutes)
        assertEquals(listOf(seongsu, bridge, hannam), routed.geometry)

        val guessed = estimator.estimate(hannam, itaewon)
        assertEquals(RouteLegQuality.APPROXIMATE, guessed.quality)
        assertEquals(LocalApproximateRouteLegEstimator().estimate(hannam, itaewon), guessed)
    }

    @Test
    fun walkingMinutesUseTheAppsOwnPace() {
        assertEquals(0, walkingMinutes(0))
        assertEquals(1, walkingMinutes(1))
        assertEquals(60, walkingMinutes(4_500), "4.5 km/h")
        assertEquals(70, walkingMinutes(5_230))
    }

    @Test
    fun onlyLegsNotYetKnownAreAskedForInBatchesOfSixPoints() {
        val stops = (0 until 9).map { venue("v$it", 37.50 + it * 0.01) }
        val known = WalkingLegs.EMPTY.plus(stops[3].point, stops[4].point, WalkingLeg(1_000, emptyList()))

        val batches = legsToRequest(stops, known)

        assertEquals(
            listOf(listOf("v0", "v1", "v2", "v3"), listOf("v4", "v5", "v6", "v7", "v8")),
            batches.map { batch -> batch.map { it.exhibitionId } },
        )
        val long = legsToRequest((0 until 8).map { venue("w$it", 37.40 + it * 0.01) }, WalkingLegs.EMPTY)
        assertEquals(listOf(6, 3), long.map { it.size }, "a batch carries at most 6 points and overlaps by one")
        assertEquals(long[0].last(), long[1].first())
    }

    @Test
    fun nothingIsAskedForWhenEveryLegIsKnownOrEmpty() {
        val a = venue("a", 37.50)
        val b = venue("b", 37.51)
        val known = WalkingLegs.EMPTY.plus(a.point, b.point, WalkingLeg(1_000, emptyList()))

        assertTrue(legsToRequest(listOf(a, b), known).isEmpty())
        assertTrue(legsToRequest(listOf(a), WalkingLegs.EMPTY).isEmpty())
        assertTrue(legsToRequest(listOf(a, a.copy(exhibitionId = "a2")), WalkingLegs.EMPTY).isEmpty(), "same place")
    }

    @Test
    fun aPlaceThatIsNotAVenueIsNeverSent() {
        val position = RouteWaypoint(exhibitionId = null, point = GeoPoint(37.55, 127.00))
        val stops = listOf(position, venue("a", 37.50), venue("b", 37.51))

        val batches = legsToRequest(stops, WalkingLegs.EMPTY)

        assertEquals(listOf(listOf("a", "b")), batches.map { batch -> batch.map { it.exhibitionId } })
    }

    private fun venue(
        id: String,
        latitude: Double,
    ) = RouteWaypoint(exhibitionId = id, point = GeoPoint(latitude, 127.00))
}
