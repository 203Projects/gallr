package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.hours.DailyOpening
import kotlinx.datetime.LocalTime

/** One stop as the timeline sees it: where it is and its opening interval on the visit date, if known. */
internal data class TimelineStop(
    val point: GeoPoint,
    val opening: DailyOpening?,
)

/**
 * Timing of one stop on a walked route, in minutes of the visit date.
 *
 * [fits] is false when known hours cannot hold the whole visit (arriving after closing, or a visit that would
 * run past closing) or when the visit would end at or after midnight.
 */
internal data class TimelineEntry(
    val arrivalMinutes: Int,
    val visitStartMinutes: Int,
    val visitEndMinutes: Int,
    val closesMinutes: Int?,
    val fits: Boolean,
) {
    val waitMinutes: Int get() = visitStartMinutes - arrivalMinutes
}

/**
 * Minute arithmetic shared by the neighbourhood planner and the personal route evaluator (spec 089, E-D3).
 *
 * The walk never drops a stop: a stop that does not fit is reported with `fits = false` and the clock moves on
 * from the end of its visit, so an author-ordered route keeps every stop. The planner treats any misfit as an
 * infeasible ordering, which preserves its earlier behaviour.
 */
internal class RouteTimeline(
    private val visitMinutes: Int,
    val legEstimator: RouteLegEstimator,
) {
    /** Timing for arriving at a stop with [opening] at [arrivalMinutes]. */
    fun entry(
        opening: DailyOpening?,
        arrivalMinutes: Int,
    ): TimelineEntry {
        val visitStart = maxOf(arrivalMinutes, opening?.opens?.minutesOfDay() ?: arrivalMinutes)
        val visitEnd = visitStart + visitMinutes
        val closes = opening?.closes?.minutesOfDay()
        val fitsHours = closes == null || visitEnd <= closes
        return TimelineEntry(
            arrivalMinutes = arrivalMinutes,
            visitStartMinutes = visitStart,
            visitEndMinutes = visitEnd,
            closesMinutes = closes,
            fits = fitsHours && visitEnd < MINUTES_PER_DAY,
        )
    }

    /** Walks [stops] in order from [origin], leaving at [startMinutes]. */
    fun walk(
        origin: GeoPoint,
        stops: List<TimelineStop>,
        startMinutes: Int,
    ): List<TimelineEntry> {
        val entries = ArrayList<TimelineEntry>(stops.size)
        var position = origin
        var clock = startMinutes
        for (stop in stops) {
            val leg = legEstimator.estimate(position, stop.point)
            val entry = entry(stop.opening, clock + leg.travelMinutes)
            entries += entry
            position = stop.point
            clock = entry.visitEndMinutes
        }
        return entries
    }

    /**
     * Waiting at the origin is not route time: when the first stop would be reached before it opens, the
     * departure moves later so the visitor arrives as it opens, provided that adds no misfit.
     */
    fun departingForOpening(
        origin: GeoPoint,
        stops: List<TimelineStop>,
        startMinutes: Int,
        entries: List<TimelineEntry>,
    ): Pair<Int, List<TimelineEntry>> {
        val firstWait = entries.firstOrNull()?.waitMinutes ?: 0
        if (firstWait <= 0) return startMinutes to entries
        val laterStart = startMinutes + firstWait
        val later = walk(origin, stops, laterStart)
        if (later.count { !it.fits } > entries.count { !it.fits }) return startMinutes to entries
        return laterStart to later
    }
}

internal fun LocalTime.minutesOfDay(): Int = toSecondOfDay() / SECONDS_PER_MINUTE

internal fun Int.toLocalTime(): LocalTime =
    LocalTime.fromSecondOfDay(coerceIn(0, MINUTES_PER_DAY - 1) * SECONDS_PER_MINUTE)

internal const val MINUTES_PER_DAY = 24 * 60
internal const val SECONDS_PER_MINUTE = 60
