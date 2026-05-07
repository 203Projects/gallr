package com.gallr.shared.data.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExhibitionStatusTest {

    // Fixed reference date for deterministic tests
    private val today = LocalDate(2026, 4, 2)
    private val yesterday = today.plus(-1, DateTimeUnit.DAY)
    private val tomorrow = today.plus(1, DateTimeUnit.DAY)
    private val inTwoDays = today.plus(2, DateTimeUnit.DAY)
    private val inThreeDays = today.plus(3, DateTimeUnit.DAY)
    private val inFourDays = today.plus(4, DateTimeUnit.DAY)
    private val inTenDays = today.plus(10, DateTimeUnit.DAY)
    private val threeDaysAgo = today.plus(-3, DateTimeUnit.DAY)
    private val tenDaysAgo = today.plus(-10, DateTimeUnit.DAY)

    // ── UPCOMING ──────────────────────────────────────────────────────────

    @Test
    fun upcomingWhenOpeningDateInFuture() {
        val status = exhibitionStatus(
            openingDate = tomorrow,
            closingDate = inTenDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.UPCOMING, status)
    }

    @Test
    fun upcomingTakesPriorityOverClosingSoon() {
        // 1-day exhibition opening in 2 days — would be "closing soon" by date range
        // but UPCOMING must take priority since it hasn't opened yet
        val status = exhibitionStatus(
            openingDate = inTwoDays,
            closingDate = inTwoDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.UPCOMING, status)
    }

    @Test
    fun upcomingForExhibitionOpeningInThreeDaysClosingInThreeDays() {
        val status = exhibitionStatus(
            openingDate = inThreeDays,
            closingDate = inThreeDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.UPCOMING, status)
    }

    // ── CLOSING_SOON ──────────────────────────────────────────────────────

    @Test
    fun closingSoonWhenClosingToday() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = today,
            today = today,
        )
        assertEquals(ExhibitionStatus.CLOSING_SOON, status)
    }

    @Test
    fun closingSoonWhenClosingTomorrow() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = tomorrow,
            today = today,
        )
        assertEquals(ExhibitionStatus.CLOSING_SOON, status)
    }

    @Test
    fun closingSoonWhenClosingInTwoDays() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = inTwoDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.CLOSING_SOON, status)
    }

    @Test
    fun closingSoonWhenClosingInExactlyThreeDays() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = inThreeDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.CLOSING_SOON, status)
    }

    @Test
    fun closingSoonForSingleDayExhibitionToday() {
        val status = exhibitionStatus(
            openingDate = today,
            closingDate = today,
            today = today,
        )
        assertEquals(ExhibitionStatus.CLOSING_SOON, status)
    }

    // ── ACTIVE ────────────────────────────────────────────────────────────

    @Test
    fun activeWhenClosingInFourDays() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = inFourDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.ACTIVE, status)
    }

    @Test
    fun activeWhenClosingInTenDays() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = inTenDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.ACTIVE, status)
    }

    @Test
    fun activeWhenOpenedTodayClosingFarFuture() {
        val status = exhibitionStatus(
            openingDate = today,
            closingDate = inTenDays,
            today = today,
        )
        assertEquals(ExhibitionStatus.ACTIVE, status)
    }

    // ── ENDED ─────────────────────────────────────────────────────────────

    @Test
    fun endedWhenClosingDateYesterday() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = yesterday,
            today = today,
        )
        assertEquals(ExhibitionStatus.ENDED, status)
    }

    @Test
    fun endedWhenClosingDateLongAgo() {
        val status = exhibitionStatus(
            openingDate = tenDaysAgo,
            closingDate = threeDaysAgo,
            today = today,
        )
        assertEquals(ExhibitionStatus.ENDED, status)
    }

    // ── Invalid date range (openingDate > closingDate) ────────────────────

    @Test
    fun endedWhenOpeningDateAfterClosingDate() {
        // Bad data: opening is after closing — treat as ENDED
        val status = exhibitionStatus(
            openingDate = inTenDays,
            closingDate = tomorrow,
            today = today,
        )
        assertEquals(ExhibitionStatus.ENDED, status)
    }

    @Test
    fun endedWhenOpeningDateAfterClosingDateBothInPast() {
        val status = exhibitionStatus(
            openingDate = yesterday,
            closingDate = threeDaysAgo,
            today = today,
        )
        assertEquals(ExhibitionStatus.ENDED, status)
    }

    // ── label() bilingual ─────────────────────────────────────────────────

    @Test
    fun upcomingLabelEnglish() {
        assertEquals("Upcoming", ExhibitionStatus.UPCOMING.label(AppLanguage.EN))
    }

    @Test
    fun upcomingLabelKorean() {
        assertEquals("오픈 예정", ExhibitionStatus.UPCOMING.label(AppLanguage.KO))
    }

    @Test
    fun closingSoonLabelEnglish() {
        assertEquals("Closing Soon", ExhibitionStatus.CLOSING_SOON.label(AppLanguage.EN))
    }

    @Test
    fun closingSoonLabelKorean() {
        assertEquals("종료 예정", ExhibitionStatus.CLOSING_SOON.label(AppLanguage.KO))
    }

    @Test
    fun activeLabelReturnsNull() {
        assertNull(ExhibitionStatus.ACTIVE.label(AppLanguage.EN))
        assertNull(ExhibitionStatus.ACTIVE.label(AppLanguage.KO))
    }

    @Test
    fun endedLabelReturnsNull() {
        assertNull(ExhibitionStatus.ENDED.label(AppLanguage.EN))
        assertNull(ExhibitionStatus.ENDED.label(AppLanguage.KO))
    }
}
