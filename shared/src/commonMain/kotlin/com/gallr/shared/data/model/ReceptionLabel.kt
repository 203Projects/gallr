package com.gallr.shared.data.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * Returns a human-readable label for an exhibition's reception (opening) date,
 * or null when the label should be hidden.
 *
 * @param openingTime optional free-text time string (e.g., "5 PM") appended to the label
 * @param today injectable reference date for testability; defaults to system clock
 */
fun receptionDateLabel(
    receptionDate: LocalDate,
    closingDate: LocalDate,
    lang: AppLanguage,
    openingTime: String? = null,
    today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
): String? {
    // Exhibition ended → hide
    if (closingDate < today) return null

    // Find Monday of the current week
    val daysSinceMonday = (today.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal + 7) % 7
    val thisMonday = today.plus(-daysSinceMonday, DateTimeUnit.DAY)
    val nextMonday = thisMonday.plus(7, DateTimeUnit.DAY)

    val timeSuffix = if (!openingTime.isNullOrBlank()) ", ${openingTime.trim()}" else ""

    return when {
        // More than 1 week away → hide
        receptionDate >= nextMonday -> null
        // Today
        receptionDate == today -> {
            if (lang == AppLanguage.KO) "오프닝 오늘$timeSuffix" else "Opening today$timeSuffix"
        }
        // Tomorrow
        receptionDate == today.plus(1, DateTimeUnit.DAY) -> {
            if (lang == AppLanguage.KO) "오프닝 내일$timeSuffix" else "Opening tomorrow$timeSuffix"
        }
        // Within this week (future)
        receptionDate in thisMonday..< nextMonday && receptionDate > today -> {
            val dayName = when (receptionDate.dayOfWeek) {
                DayOfWeek.MONDAY -> if (lang == AppLanguage.KO) "월요일" else "Monday"
                DayOfWeek.TUESDAY -> if (lang == AppLanguage.KO) "화요일" else "Tuesday"
                DayOfWeek.WEDNESDAY -> if (lang == AppLanguage.KO) "수요일" else "Wednesday"
                DayOfWeek.THURSDAY -> if (lang == AppLanguage.KO) "목요일" else "Thursday"
                DayOfWeek.FRIDAY -> if (lang == AppLanguage.KO) "금요일" else "Friday"
                DayOfWeek.SATURDAY -> if (lang == AppLanguage.KO) "토요일" else "Saturday"
                DayOfWeek.SUNDAY -> if (lang == AppLanguage.KO) "일요일" else "Sunday"
            }
            if (lang == AppLanguage.KO) "오프닝 $dayName$timeSuffix" else "Opening $dayName$timeSuffix"
        }
        // Past but exhibition still running → show full date
        receptionDate < today -> {
            val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            if (lang == AppLanguage.KO) {
                "오프닝 ${receptionDate.monthNumber}월 ${receptionDate.dayOfMonth}일$timeSuffix"
            } else {
                "Opening ${months[receptionDate.monthNumber - 1]} ${receptionDate.dayOfMonth}$timeSuffix"
            }
        }
        else -> null
    }
}
