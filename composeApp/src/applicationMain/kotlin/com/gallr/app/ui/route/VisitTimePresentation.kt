package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.RouteVisitTime
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil

internal fun visitTimeRowLabel(
    visitTime: RouteVisitTime?,
    today: LocalDate,
    language: AppLanguage,
): String {
    val value =
        when {
            visitTime == null -> {
                visitTimeNowChipLabel(language)
            }

            today.daysUntil(visitTime.day) in 0..1 -> {
                "${visitTimeDayChipLabel(visitTime.day, today, language)} ${clockText(visitTime.start)}"
            }

            else -> {
                datedClock(visitTime, language)
            }
        }
    return if (language == AppLanguage.KO) "방문 시간 · $value" else "VISIT TIME · $value"
}

internal fun visitTimeNowChipLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "지금" else "NOW"

internal fun visitTimeDayChipLabel(
    day: LocalDate,
    today: LocalDate,
    language: AppLanguage,
): String =
    when (today.daysUntil(day)) {
        0 -> if (language == AppLanguage.KO) "오늘" else "TODAY"
        1 -> if (language == AppLanguage.KO) "내일" else "TOMORROW"
        else -> "${weekdayShort(day.dayOfWeek, language)} ${monthDay(day)}"
    }

/** The summary's reference line for a chosen time: "10.18(토) 13:00 기준" / "SAT 10.18 · FROM 13:00". */
internal fun visitTimeReference(
    visitTime: RouteVisitTime,
    language: AppLanguage,
): String {
    val weekday = weekdayShort(visitTime.day.dayOfWeek, language)
    val date = monthDay(visitTime.day)
    val clock = clockText(visitTime.start)
    return when (language) {
        AppLanguage.KO -> "$date($weekday) $clock 기준"
        AppLanguage.EN -> "$weekday $date · FROM $clock"
    }
}

internal fun visitTimeSheetTitle(language: AppLanguage): String =
    if (language == AppLanguage.KO) "언제 가세요?" else "WHEN ARE YOU GOING?"

internal fun visitTimeNoTimesLeft(language: AppLanguage): String =
    if (language == AppLanguage.KO) "오늘은 더 고를 시간이 없어요" else "No more times today"

internal fun visitTimePassedMessage(language: AppLanguage): String =
    if (language == AppLanguage.KO) {
        "선택한 날짜가 지나 지금 기준으로 돌아왔어요"
    } else {
        "That day has passed, so the route is checked from now"
    }

private fun datedClock(
    visitTime: RouteVisitTime,
    language: AppLanguage,
): String {
    val weekday = weekdayShort(visitTime.day.dayOfWeek, language)
    val date = monthDay(visitTime.day)
    val clock = clockText(visitTime.start)
    return when (language) {
        AppLanguage.KO -> "$date($weekday) $clock"
        AppLanguage.EN -> "$weekday $date $clock"
    }
}

private fun monthDay(day: LocalDate): String = "${day.month.ordinal + 1}.${day.day.toString().padStart(2, '0')}"

internal fun clockText(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

private fun weekdayShort(
    day: DayOfWeek,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> {
            when (day) {
                DayOfWeek.MONDAY -> "월"
                DayOfWeek.TUESDAY -> "화"
                DayOfWeek.WEDNESDAY -> "수"
                DayOfWeek.THURSDAY -> "목"
                DayOfWeek.FRIDAY -> "금"
                DayOfWeek.SATURDAY -> "토"
                DayOfWeek.SUNDAY -> "일"
            }
        }

        AppLanguage.EN -> {
            day.name.take(3)
        }
    }
