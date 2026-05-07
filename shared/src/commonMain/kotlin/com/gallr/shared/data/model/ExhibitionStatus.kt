package com.gallr.shared.data.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Temporal status of an exhibition, computed from opening/closing dates.
 */
enum class ExhibitionStatus {
    UPCOMING,
    CLOSING_SOON,
    ACTIVE,
    ENDED;

    /**
     * Returns the bilingual display label, or null if this status should not be shown.
     */
    fun label(lang: AppLanguage): String? = when (this) {
        UPCOMING -> when (lang) {
            AppLanguage.KO -> "오픈 예정"
            AppLanguage.EN -> "Upcoming"
        }
        CLOSING_SOON -> when (lang) {
            AppLanguage.KO -> "종료 예정"
            AppLanguage.EN -> "Closing Soon"
        }
        ACTIVE -> null
        ENDED -> null
    }
}

/**
 * Pure function to compute exhibition status from dates.
 * [today] is injected for testability — callers pass `Clock.System.todayIn(...)`.
 */
fun exhibitionStatus(
    openingDate: LocalDate,
    closingDate: LocalDate,
    today: LocalDate,
): ExhibitionStatus = when {
    openingDate > closingDate -> ExhibitionStatus.ENDED
    openingDate > today -> ExhibitionStatus.UPCOMING
    closingDate < today -> ExhibitionStatus.ENDED
    closingDate <= today.plus(CLOSING_SOON_THRESHOLD_DAYS, DateTimeUnit.DAY) -> ExhibitionStatus.CLOSING_SOON
    else -> ExhibitionStatus.ACTIVE
}

private const val CLOSING_SOON_THRESHOLD_DAYS = 3
