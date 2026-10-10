package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private const val COUNTDOWN_DAYS = 14
private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val KO_WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")
private val EN_WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAY_NAME = Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
private val RANGE_DASH = Regex("\\s*-\\s*")

/**
 * The eyebrow label on a share card. Wording is absolute (dates, D-n) rather than relative
 * ("tomorrow") because the exported image outlives the day it was made.
 */
data class ShareCardStatus(
    val text: String,
    /** Opening day, closing day and the final countdown get the accent dot. */
    val emphasized: Boolean,
)

fun shareCardStatus(
    openingDate: LocalDate,
    closingDate: LocalDate,
    today: LocalDate,
    lang: AppLanguage,
): ShareCardStatus {
    val ko = lang == AppLanguage.KO
    val toOpening = today.daysUntil(openingDate)
    val toClosing = today.daysUntil(closingDate)
    return when {
        toClosing < 0 -> {
            ShareCardStatus(if (ko) "전시 종료" else "Ended", emphasized = false)
        }

        toOpening > 0 -> {
            ShareCardStatus(
                if (ko) {
                    "곧 시작 · ${openingDate.month.number}.${openingDate.day} 개막"
                } else {
                    "Opens ${EN_MONTHS[openingDate.month.number - 1]} ${openingDate.day}"
                },
                emphasized = false,
            )
        }

        toOpening == 0 -> {
            ShareCardStatus(if (ko) "오늘 개막" else "Opens today", emphasized = true)
        }

        toClosing == 0 -> {
            ShareCardStatus(if (ko) "오늘 마감" else "Closes today", emphasized = true)
        }

        toClosing <= COUNTDOWN_DAYS -> {
            ShareCardStatus(
                if (ko) "지금 진행 중 · D-$toClosing 마감" else "On now · $toClosing days left",
                emphasized = true,
            )
        }

        else -> {
            ShareCardStatus(if (ko) "지금 진행 중" else "On now", emphasized = false)
        }
    }
}

/** A future reception if there is one, otherwise opening hours; null when neither is known. */
fun shareCardDetailLine(
    receptionDate: LocalDate?,
    openingTime: String?,
    hours: String?,
    today: LocalDate,
    lang: AppLanguage,
): String? {
    if (receptionDate != null && receptionDate >= today) {
        return receptionLine(receptionDate, openingTime?.trim()?.takeIf { it.isNotEmpty() }, lang)
    }
    return hours?.let { hoursLine(it, lang) }
}

private fun receptionLine(
    date: LocalDate,
    time: String?,
    lang: AppLanguage,
): String =
    if (lang == AppLanguage.KO) {
        "오프닝 리셉션 ${date.month.number}.${date.day} (${weekday(date.dayOfWeek, KO_WEEKDAYS)})" +
            (time?.let { " $it" } ?: "")
    } else {
        "Opening reception ${weekday(date.dayOfWeek, EN_WEEKDAYS)}, ${EN_MONTHS[date.month.number - 1]} ${date.day}" +
            (time?.let { ", $it" } ?: "")
    }

private fun hoursLine(
    hours: String,
    lang: AppLanguage,
): String? {
    val lines = hours.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val time = lines.firstOrNull()?.replace(RANGE_DASH, " – ") ?: return null
    val days = lines.getOrNull(1)?.let { localizeDays(it, lang) } ?: return time
    return "$days $time"
}

private fun localizeDays(
    days: String,
    lang: AppLanguage,
): String {
    val names = if (lang == AppLanguage.KO) KO_WEEKDAYS else EN_WEEKDAYS
    return days
        .replace(DAY_NAME) { match -> names[DayOfWeek.entries.first { it.name.equals(match.value, true) }.ordinal] }
        .replace(RANGE_DASH, "–")
}

private fun weekday(
    day: DayOfWeek,
    names: List<String>,
): String = names[day.isoDayNumber - 1]
