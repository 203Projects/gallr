package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.hours.DailyOpening
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 089 (E-D3): the shared timeline reports every stop, flagging misfits instead of dropping them. */
class RouteTimelineTest {
    private val origin = GeoPoint(37.5796, 126.9770)
    private val first = GeoPoint(37.5800, 126.9800)
    private val second = GeoPoint(37.5810, 126.9850)
    private val tenMinutes = RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 800, travelMinutes = 10) }
    private val timeline = RouteTimeline(visitMinutes = 45, legEstimator = tenMinutes)

    @Test
    fun walksEveryStopWithArrivalVisitAndClosing() {
        val entries =
            timeline.walk(
                origin = origin,
                stops = listOf(stop(first, 10, 18), stop(second, 10, 18)),
                startMinutes = 11 * 60,
            )

        assertEquals(2, entries.size)
        assertEquals(11 * 60 + 10, entries[0].arrivalMinutes)
        assertEquals(11 * 60 + 10, entries[0].visitStartMinutes)
        assertEquals(11 * 60 + 55, entries[0].visitEndMinutes)
        assertEquals(18 * 60, entries[0].closesMinutes)
        assertTrue(entries[0].fits)
        assertEquals(11 * 60 + 65, entries[1].arrivalMinutes)
        assertTrue(entries[1].fits)
    }

    @Test
    fun waitsForOpeningWhenArrivingEarly() {
        val entry = timeline.walk(origin, listOf(stop(first, 12, 18)), startMinutes = 11 * 60).single()

        assertEquals(11 * 60 + 10, entry.arrivalMinutes)
        assertEquals(12 * 60, entry.visitStartMinutes)
        assertEquals(50, entry.waitMinutes)
    }

    @Test
    fun reportsAMisfitAndKeepsWalkingFromItsVisitEnd() {
        val entries =
            timeline.walk(
                origin = origin,
                stops = listOf(stop(first, 10, 11), stop(second, 10, 18)),
                startMinutes = 10 * 60 + 30,
            )

        assertEquals(2, entries.size)
        assertFalse(entries[0].fits, "a 45-minute visit from 10:40 cannot end before an 11:00 close")
        assertEquals(entries[0].visitEndMinutes + 10, entries[1].arrivalMinutes)
        assertTrue(entries[1].fits)
    }

    @Test
    fun unknownHoursNeverWaitAndAlwaysFitWithinTheDay() {
        val entry = timeline.walk(origin, listOf(stop(first, opening = null)), startMinutes = 9 * 60).single()

        assertEquals(entry.arrivalMinutes, entry.visitStartMinutes)
        assertNull(entry.closesMinutes)
        assertTrue(entry.fits)
    }

    @Test
    fun aVisitEndingAtMidnightDoesNotFit() {
        val entry = timeline.walk(origin, listOf(stop(first, opening = null)), startMinutes = 23 * 60 + 10).single()

        assertFalse(entry.fits)
    }

    @Test
    fun departureMovesLaterSoTheFirstStopIsReachedAsItOpens() {
        val stops = listOf(stop(first, 12, 18), stop(second, 10, 18))
        val planned = timeline.walk(origin, stops, startMinutes = 10 * 60)

        val (departure, shifted) = timeline.departingForOpening(origin, stops, startMinutes = 10 * 60, planned)

        assertEquals(11 * 60 + 50, departure)
        assertEquals(0, shifted.first().waitMinutes)
        assertTrue(shifted.all { it.fits })
    }

    @Test
    fun departureStaysWhenTheFirstStopIsAlreadyOpenOnArrival() {
        val stops = listOf(stop(first, 10, 18), stop(second, 10, 18))
        val planned = timeline.walk(origin, stops, startMinutes = 11 * 60)

        val (departure, kept) = timeline.departingForOpening(origin, stops, startMinutes = 11 * 60, planned)

        assertEquals(11 * 60, departure)
        assertEquals(planned, kept)
    }

    private fun stop(
        point: GeoPoint,
        opensHour: Int,
        closesHour: Int,
    ) = stop(point, DailyOpening(LocalTime(opensHour, 0), LocalTime(closesHour, 0)))

    private fun stop(
        point: GeoPoint,
        opening: DailyOpening?,
    ) = TimelineStop(point = point, opening = opening)
}
