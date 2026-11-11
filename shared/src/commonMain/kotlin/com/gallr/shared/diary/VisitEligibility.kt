package com.gallr.shared.diary

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** A visit can be recorded from the opening day on, while the show runs and after it closes (FR-002). */
fun canRecordVisit(
    exhibition: Exhibition,
    today: LocalDate,
): Boolean = exhibition.openingDate <= today

/**
 * The dates the entry sheet offers. [today] and [yesterday] say whether those shortcuts are shown; any other date is
 * picked within [earliest]..[latest]. [preselected] is today for a running show and nothing for a closed one.
 */
data class VisitDateOptions(
    val today: Boolean,
    val yesterday: Boolean,
    val earliest: LocalDate,
    val latest: LocalDate,
    val preselected: LocalDate?,
)

/** Null for a show that has not opened yet (US1 scenarios 2–4). */
fun visitDateOptions(
    opening: LocalDate,
    closing: LocalDate,
    today: LocalDate,
): VisitDateOptions? {
    if (opening > today) return null
    val running = today <= closing
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    return VisitDateOptions(
        today = running,
        yesterday = yesterday in opening..closing,
        earliest = opening,
        latest = minOf(closing, today),
        preselected = if (running) today else null,
    )
}
