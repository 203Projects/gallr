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
        // Reception already happened → label hidden regardless of opening time (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun pastDateWithTimeKorean() {
        // Reception already happened → label hidden in Korean too (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.KO, "5 PM", today)
        assertNull(label)
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
        // Reception already happened → label hidden even without opening time (spec 039)
        val label = receptionDateLabel(pastDate, closingFuture, AppLanguage.EN, null, today)
        assertNull(label)
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
        // Past date inside the current calendar week is still past → hidden (spec 039)
        val monday = LocalDate(2026, 4, 6) // Monday of this week; today is Wednesday
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun pastDateWithinCurrentWeekNoTime() {
        // Past date inside the current calendar week is still past → hidden (spec 039)
        val monday = LocalDate(2026, 4, 6)
        val label = receptionDateLabel(monday, closingFuture, AppLanguage.EN, null, today)
        assertNull(label)
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

    // ── Spec 039: hide past reception label ─────────────────────────────

    @Test
    fun yesterdayHiddenEnglish() {
        // Spec 039 AC #1: receptionDate = yesterday, exhibition still running → no label
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val label = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun yesterdayHiddenKorean() {
        // Spec 039 AC #4: same behavior in Korean
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val label = receptionDateLabel(yesterday, closingFuture, AppLanguage.KO, "5 PM", today)
        assertNull(label)
    }

    @Test
    fun yesterdayHidesOpeningTimeToo() {
        // Spec 039: when the label hides, the inline opening time hides with it.
        // The function returning null is the single signal both pieces use to hide.
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val withTime = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, "5 PM", today)
        val withoutTime = receptionDateLabel(yesterday, closingFuture, AppLanguage.EN, null, today)
        assertNull(withTime)
        assertNull(withoutTime)
    }
}
