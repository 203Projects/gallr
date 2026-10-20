package com.gallr.shared.route

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * When the visitor says they are going (spec 096): the route is checked from [start] on [day] instead of from now.
 * Seoul time, like the rest of route timing. "Now" is the absence of a value.
 */
data class RouteVisitTime(
    val day: LocalDate,
    val start: LocalTime,
)

/** The days and start times a visitor can choose from a given moment (spec 096 FR-001, FR-002). */
class VisitTimeOptions internal constructor(
    private val now: LocalDateTime,
) {
    /** Today and the next [VISIT_TIME_DAYS_AHEAD] days. */
    val days: List<LocalDate> = (0..VISIT_TIME_DAYS_AHEAD).map { offset -> now.date.plus(DatePeriod(days = offset)) }

    /** Every half hour from 08:00 to 21:00 on [day]; today only the steps that have not begun. */
    fun timesFor(day: LocalDate): List<LocalTime> {
        if (day !in days) return emptyList()
        val all = (FIRST_START_MINUTES..LAST_START_MINUTES step START_STEP_MINUTES).map { LocalTime(it / 60, it % 60) }
        if (day != now.date) return all
        return all.filter { it > now.time }
    }
}

fun visitTimeOptions(now: LocalDateTime): VisitTimeOptions = VisitTimeOptions(now)

/** How many days after today a visit can be planned. */
const val VISIT_TIME_DAYS_AHEAD = 13

private const val FIRST_START_MINUTES = 8 * 60
private const val LAST_START_MINUTES = 21 * 60
private const val START_STEP_MINUTES = 30
