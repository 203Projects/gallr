package com.gallr.shared.hours

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** How much is known about a venue's weekly hours, and from where. */
@Serializable
enum class OpeningHoursCompleteness {
    /** Staff confirmed the weekly schedule against the current listing; a weekday missing from it is closed. */
    CONFIRMED,

    /** Explicit days were read and nothing in the listing was left unread. */
    COMPLETE,

    /** Times were read without days (applied to every day), or part of the listing was unreadable. */
    PARTIAL,

    /** Nothing readable; the venue's hours are unknown. */
    UNKNOWN,
}

/** Opening interval on one weekday. Overnight intervals are not supported. */
@Serializable
data class DailyOpening(
    val opens: LocalTime,
    val closes: LocalTime,
) {
    init {
        require(closes > opens) { "closes must be after opens" }
    }
}

/**
 * Weekly opening hours, either confirmed by staff or derived from a venue's free-text listing.
 *
 * Public holidays and one-off closures are not modelled. A weekday missing from [byDay] is a known
 * closure only when the hours are verified ([OpeningHoursCompleteness.CONFIRMED] or
 * [OpeningHoursCompleteness.COMPLETE]); a partial reading never claims a venue is closed.
 */
@Serializable
data class WeeklyOpeningHours(
    val byDay: Map<DayOfWeek, DailyOpening>,
    val completeness: OpeningHoursCompleteness,
) {
    init {
        require(completeness != OpeningHoursCompleteness.UNKNOWN || byDay.isEmpty()) {
            "unknown hours must not carry a schedule"
        }
        require(completeness == OpeningHoursCompleteness.UNKNOWN || byDay.isNotEmpty()) {
            "known hours must carry at least one weekday"
        }
    }

    /** True when staff confirmed the schedule or the whole listing was read with explicit days. */
    val isVerified: Boolean
        get() =
            completeness == OpeningHoursCompleteness.CONFIRMED ||
                completeness == OpeningHoursCompleteness.COMPLETE

    /** The opening interval on [date], or null when unknown or closed. */
    fun openingOn(date: LocalDate): DailyOpening? = byDay[date.dayOfWeek]

    /** True only when verified hours list no opening for the weekday of [date]. */
    fun isKnownClosedOn(date: LocalDate): Boolean = isVerified && date.dayOfWeek !in byDay

    companion object {
        val UNKNOWN = WeeklyOpeningHours(emptyMap(), OpeningHoursCompleteness.UNKNOWN)
    }
}
