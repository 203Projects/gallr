package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteVisitTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 096 contracts/visit-time-control.md: the words for "when are you going". 2026-10-08 is a Thursday. */
class VisitTimePresentationTest {
    private val thursday = LocalDate(2026, 10, 8)

    @Test
    fun theRowNamesNowTodayTomorrowOrTheDate() {
        assertEquals("방문 시간 · 지금", visitTimeRowLabel(null, thursday, AppLanguage.KO))
        assertEquals("VISIT TIME · NOW", visitTimeRowLabel(null, thursday, AppLanguage.EN))
        assertEquals("방문 시간 · 오늘 13:00", visitTimeRowLabel(at(thursday), thursday, AppLanguage.KO))
        assertEquals("VISIT TIME · TODAY 13:00", visitTimeRowLabel(at(thursday), thursday, AppLanguage.EN))
        assertEquals("방문 시간 · 내일 13:00", visitTimeRowLabel(at(LocalDate(2026, 10, 9)), thursday, AppLanguage.KO))
        assertEquals(
            "VISIT TIME · TOMORROW 13:00",
            visitTimeRowLabel(at(LocalDate(2026, 10, 9)), thursday, AppLanguage.EN),
        )
        assertEquals("방문 시간 · 10.10(토) 13:00", visitTimeRowLabel(at(LocalDate(2026, 10, 10)), thursday, AppLanguage.KO))
        assertEquals(
            "VISIT TIME · SAT 10.10 13:00",
            visitTimeRowLabel(at(LocalDate(2026, 10, 10)), thursday, AppLanguage.EN),
        )
    }

    @Test
    fun dayChipsSayNowTodayTomorrowThenWeekdayAndDate() {
        assertEquals("지금", visitTimeNowChipLabel(AppLanguage.KO))
        assertEquals("NOW", visitTimeNowChipLabel(AppLanguage.EN))
        assertEquals("오늘", visitTimeDayChipLabel(thursday, thursday, AppLanguage.KO))
        assertEquals("내일", visitTimeDayChipLabel(LocalDate(2026, 10, 9), thursday, AppLanguage.KO))
        assertEquals("토 10.10", visitTimeDayChipLabel(LocalDate(2026, 10, 10), thursday, AppLanguage.KO))
        assertEquals("SAT 10.10", visitTimeDayChipLabel(LocalDate(2026, 10, 10), thursday, AppLanguage.EN))
    }

    @Test
    fun theReferenceLineNamesTheChosenDayAndTime() {
        assertEquals("10.10(토) 13:00 기준", visitTimeReference(at(LocalDate(2026, 10, 10)), AppLanguage.KO))
        assertEquals("SAT 10.10 · FROM 13:00", visitTimeReference(at(LocalDate(2026, 10, 10)), AppLanguage.EN))
    }

    @Test
    fun theSheetAndMessagesAreBilingual() {
        assertEquals("언제 가세요?", visitTimeSheetTitle(AppLanguage.KO))
        assertEquals("WHEN ARE YOU GOING?", visitTimeSheetTitle(AppLanguage.EN))
        assertEquals("오늘은 더 고를 시간이 없어요", visitTimeNoTimesLeft(AppLanguage.KO))
        assertEquals("No more times today", visitTimeNoTimesLeft(AppLanguage.EN))
        assertEquals("선택한 날짜가 지나 지금 기준으로 돌아왔어요", visitTimePassedMessage(AppLanguage.KO))
        assertEquals("That day has passed, so the route is checked from now", visitTimePassedMessage(AppLanguage.EN))
    }

    private fun at(day: LocalDate) = RouteVisitTime(day, LocalTime(13, 0))
}
