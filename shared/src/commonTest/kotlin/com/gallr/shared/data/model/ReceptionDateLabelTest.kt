package com.gallr.shared.data.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceptionDateLabelTest {

    // Fixed reference date: Wednesday 2026-04-08
    // This gives us clear weekday references for "Opening [day]" tests.
    private val today = LocalDate(2026, 4, 8) // Wednesday
    private val tomorrow = today.plus(1, DateTimeUnit.DAY) // Thursday
    private val saturday = LocalDate(2026, 4, 11) // Saturday (same week)
    private val pastDate = LocalDate(2026, 4, 5) // Sunday (past, last week)
    private val farFuture = today.plus(14, DateTimeUnit.DAY) // > 1 week away
    private val closingFuture = today.plus(30, DateTimeUnit.DAY)
    private val closingPast = today.plus(-1, DateTimeUnit.DAY) // yesterday

    // ── US1: Label WITH opening time ─────────────────────────────────────

    @Test
    fun todayWithTimeEnglish() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.EN, "5 PM", today)
        assertEquals("Opening today, 5 PM", label)
    }

    @Test
    fun todayWithTimeKorean() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.KO, "5 PM", today)
        assertEquals("오프닝 오늘, 5 PM", label)
    }

    @Test
    fun tomorrowWithTimeEnglish() {
        val label = receptionDateLabel(tomorrow, closingFuture, AppLanguage.EN, "3 PM", today)
        assertEquals("Opening tomorrow, 3 PM", label)
    }

    @Test
    fun tomorrowWithTimeKorean() {
        val label = receptionDateLabel(tomorrow, closingFuture, AppLanguage.KO, "3 PM", today)
        assertEquals("오프닝 내일, 3 PM", label)
    }

    @Test
    fun weekdayWithTimeEnglish() {
        val label = receptionDateLabel(saturday, closingFuture, AppLanguage.EN, "6:30 PM", today)
        assertEquals("Opening Saturday, 6:30 PM", label)
    }

    @Test
    fun weekdayWithTimeKorean() {
        val label = receptionDateLabel(saturday, closingFuture, AppLanguage.KO, "6:30 PM", today)
        assertEquals("오프닝 토요일, 6:30 PM", label)
    }

    @Test
    fun pastDateWithTimeEnglish() {
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, "5 PM", today)
        assertEquals("Opening Apr 5, 5 PM", label)
    }

    @Test
    fun pastDateWithTimeKorean() {
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.KO, "5 PM", today)
        assertEquals("오프닝 4월 5일, 5 PM", label)
    }

    // ── US2: Label WITHOUT opening time (fallback — no regression) ──────

    @Test
    fun todayWithoutTimeEnglish() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.EN, null, today)
        assertEquals("Opening today", label)
    }

    @Test
    fun todayWithoutTimeKorean() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.KO, null, today)
        assertEquals("오프닝 오늘", label)
    }

    @Test
    fun tomorrowWithoutTimeEnglish() {
        val label = receptionDateLabel(tomorrow, closingFuture, AppLanguage.EN, null, today)
        assertEquals("Opening tomorrow", label)
    }

    @Test
    fun weekdayWithoutTimeEnglish() {
        val label = receptionDateLabel(saturday, closingFuture, AppLanguage.EN, null, today)
        assertEquals("Opening Saturday", label)
    }

    @Test
    fun pastDateWithoutTimeEnglish() {
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, null, today)
        assertEquals("Opening Apr 5", label)
    }

    @Test
    fun blankTimeIsTreatedAsNull() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.EN, "  ", today)
        assertEquals("Opening today", label)
    }

    @Test
    fun emptyStringTimeIsTreatedAsNull() {
        val label = receptionDateLabel(today, closingFuture, AppLanguage.EN, "", today)
        assertEquals("Opening today", label)
    }

    @Test
    fun pastDateWithinCurrentWeekEnglish() {
        // Monday of the same week (today is Wednesday) — past but within thisMonday..<nextMonday
        val monday = LocalDate(2026, 4, 6) // Monday of this week
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, "5 PM", today)
        assertEquals("Opening Apr 6, 5 PM", label)
    }

    @Test
    fun pastDateWithinCurrentWeekNoTime() {
        val monday = LocalDate(2026, 4, 6)
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, null, today)
        assertEquals("Opening Apr 6", label)
    }

    // ── Edge cases: label hidden ────────────────────────────────────────

    @Test
    fun hiddenWhenExhibitionEnded() {
        val label = receptionDateLabel(pastDate, closingPast, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun hiddenWhenMoreThanOneWeekAway() {
        val label = receptionDateLabel(farFuture, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun hiddenWhenMoreThanOneWeekAwayWithoutTime() {
        val label = receptionDateLabel(farFuture, closingFuture, AppLanguage.EN, null, today)
        assertNull(label)
    }
}
