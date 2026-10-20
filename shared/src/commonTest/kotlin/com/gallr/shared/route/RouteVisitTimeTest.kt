package com.gallr.shared.route

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 096 FR-001, FR-002: the days and start times a visitor can choose. */
class RouteVisitTimeTest {
    private val thursday = LocalDate(2026, 10, 8)

    @Test
    fun todayAndTheNextThirteenDaysAreOffered() {
        val options = visitTimeOptions(LocalDateTime(thursday, LocalTime(9, 0)))

        assertEquals(14, options.days.size)
        assertEquals(thursday, options.days.first())
        assertEquals(LocalDate(2026, 10, 21), options.days.last())
    }

    @Test
    fun aLaterDayOffersEveryHalfHourFromEightToNine() {
        val times = visitTimeOptions(LocalDateTime(thursday, LocalTime(9, 0))).timesFor(LocalDate(2026, 10, 10))

        assertEquals(27, times.size)
        assertEquals(LocalTime(8, 0), times.first())
        assertEquals(LocalTime(8, 30), times[1])
        assertEquals(LocalTime(21, 0), times.last())
    }

    @Test
    fun todayOffersOnlyTimesStillAhead() {
        val options = visitTimeOptions(LocalDateTime(thursday, LocalTime(13, 0)))

        assertEquals(LocalTime(13, 30), options.timesFor(thursday).first(), "13:00 at 13:00 has already begun")
        val fiveAfter = visitTimeOptions(LocalDateTime(thursday, LocalTime(13, 5)))
        assertEquals(LocalTime(13, 30), fiveAfter.timesFor(thursday).first())
    }

    @Test
    fun lateInTheEveningTodayHasNoTimesLeftButStaysListed() {
        val options = visitTimeOptions(LocalDateTime(thursday, LocalTime(21, 0)))

        assertEquals(thursday, options.days.first())
        assertTrue(options.timesFor(thursday).isEmpty())
    }

    @Test
    fun daysOutsideTheOfferedRangeHaveNoTimes() {
        val options = visitTimeOptions(LocalDateTime(thursday, LocalTime(9, 0)))

        assertTrue(options.timesFor(LocalDate(2026, 10, 7)).isEmpty())
        assertTrue(options.timesFor(LocalDate(2026, 10, 22)).isEmpty())
    }

    @Test
    fun theDaysCrossAYearEnd() {
        val options = visitTimeOptions(LocalDateTime(LocalDate(2026, 12, 25), LocalTime(9, 0)))

        assertEquals(LocalDate(2027, 1, 7), options.days.last())
    }
}
