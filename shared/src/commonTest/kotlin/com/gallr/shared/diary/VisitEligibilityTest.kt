package com.gallr.shared.diary

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-002 and US1 scenarios 1–4: who may record a visit and which dates the sheet offers. */
class VisitEligibilityTest {
    private val today = LocalDate(2026, 11, 11)

    @Test
    fun aVisitCanBeRecordedFromTheOpeningDayWhetherRunningOrClosed() {
        assertTrue(canRecordVisit(diaryExhibition("opens-today", opening = today), today))
        assertTrue(canRecordVisit(diaryExhibition("running", LocalDate(2026, 10, 1), LocalDate(2026, 12, 1)), today))
        assertTrue(canRecordVisit(diaryExhibition("closed", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)), today))
        assertFalse(canRecordVisit(diaryExhibition("upcoming", LocalDate(2026, 11, 12), LocalDate(2026, 12, 1)), today))
    }

    @Test
    fun aRunningShowOffersAndPreselectsTodayAndOffersYesterdayInsideTheRun() {
        assertEquals(
            VisitDateOptions(
                today = true,
                yesterday = true,
                earliest = LocalDate(2026, 10, 1),
                latest = today,
                preselected = today,
            ),
            visitDateOptions(LocalDate(2026, 10, 1), LocalDate(2026, 12, 1), today),
        )
    }

    @Test
    fun aRunningShowThatOpenedTodayOffersOnlyToday() {
        assertEquals(
            VisitDateOptions(today = true, yesterday = false, earliest = today, latest = today, preselected = today),
            visitDateOptions(today, LocalDate(2026, 12, 1), today),
        )
    }

    @Test
    fun aShowClosingTodayStillOffersToday() {
        val options = visitDateOptions(LocalDate(2026, 10, 1), today, today)!!

        assertTrue(options.today)
        assertEquals(today, options.preselected)
        assertEquals(today, options.latest)
    }

    @Test
    fun aClosedShowPreselectsNothingAndDoesNotOfferToday() {
        val closing = LocalDate(2026, 11, 1)

        assertEquals(
            VisitDateOptions(
                today = false,
                yesterday = false,
                earliest = LocalDate(2026, 9, 1),
                latest = closing,
                preselected = null,
            ),
            visitDateOptions(LocalDate(2026, 9, 1), closing, today),
        )
    }

    @Test
    fun aShowThatClosedYesterdayOffersYesterdayOnly() {
        val yesterday = LocalDate(2026, 11, 10)

        val options = visitDateOptions(LocalDate(2026, 9, 1), yesterday, today)!!

        assertFalse(options.today)
        assertTrue(options.yesterday)
        assertNull(options.preselected)
        assertEquals(yesterday, options.latest)
    }

    @Test
    fun anUpcomingShowHasNoOptions() {
        assertNull(visitDateOptions(LocalDate(2026, 11, 12), LocalDate(2026, 12, 1), today))
    }
}
