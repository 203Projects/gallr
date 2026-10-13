package com.gallr.shared.curatornote

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

// The facts a reader sees beside a curator's note (DESIGN.md "Curator's eye", direction B of 2026-10-13): what the
// carousel card says under the hook, and the chips the detail block shows after the tip. Pure functions so the date
// is injected and the strings are testable.

/** A closing badge appears while the show closes within this many days, counting today. */
const val CLOSING_BADGE_DAYS = 14

/** `D-18` while the show closes within [CLOSING_BADGE_DAYS], `D-DAY` on the last day, null otherwise or once it ended. */
fun closingBadge(daysToClosing: Int): String? =
    when {
        daysToClosing < 0 -> null
        daysToClosing == 0 -> "D-DAY"
        daysToClosing <= CLOSING_BADGE_DAYS -> "D-$daysToClosing"
        else -> null
    }

/** Days from [today] to the exhibition's closing date; negative once it ended. */
fun daysToClosing(
    exhibition: Exhibition,
    today: LocalDate,
): Int = today.daysUntil(exhibition.closingDate)

/** The card's facts line: `에스더쉬퍼 · 용산구 · 2026.09.02 – 2026.10.31 · 노트 3단락`. */
fun curatorEyeFactsLine(
    card: CuratorEyeCard,
    language: AppLanguage,
): String {
    val exhibition = card.exhibition
    val count = card.note.insights(language).size
    val notes = if (language == AppLanguage.KO) "노트 ${count}단락" else insightsLabel(count)
    return listOf(
        exhibition.localizedVenueName(language),
        exhibition.localizedRegion(language),
        exhibition.localizedDateRange(language),
        notes,
    ).filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * The chips after the note on the detail screen: when it closes (with the badge while it is near), the hours as
 * one line, and the venue with its district. Blank facts leave no chip.
 */
fun curatorNoteFactChips(
    exhibition: Exhibition,
    language: AppLanguage,
    today: LocalDate,
): List<String> {
    val badge = closingBadge(daysToClosing(exhibition, today))
    val closing = closingLabel(exhibition.closingDate, language)
    val hours =
        exhibition.hours
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" · ")
            .orEmpty()
    val place =
        listOf(exhibition.localizedVenueName(language), exhibition.localizedRegion(language))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    return listOf(
        listOfNotNull(closing, badge).joinToString(" · "),
        hours,
        place,
    ).filter { it.isNotBlank() }
}

private fun closingLabel(
    closingDate: LocalDate,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "${closingDate.month.ordinal + 1}.${closingDate.day.toString().padStart(2, '0')}까지"
        AppLanguage.EN -> "Until ${EN_MONTH_NAMES[closingDate.month.ordinal]} ${closingDate.day}"
    }

private fun insightsLabel(count: Int): String = if (count == 1) "1 insight" else "$count insights"

private val EN_MONTH_NAMES =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
