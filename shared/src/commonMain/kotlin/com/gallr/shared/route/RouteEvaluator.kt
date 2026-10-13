package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.hours.DailyOpening
import com.gallr.shared.hours.OpeningHoursCompleteness
import com.gallr.shared.hours.WeeklyOpeningHours
import com.gallr.shared.hours.parseOpeningHours
import com.gallr.shared.map.EstimatedLeg
import com.gallr.shared.map.EstimatedRouteLeg
import com.gallr.shared.map.LocalApproximateRouteLegEstimator
import com.gallr.shared.map.MINUTES_PER_DAY
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.map.RouteTimeline
import com.gallr.shared.map.TimelineEntry
import com.gallr.shared.map.TimelineStop
import com.gallr.shared.map.minutesOfDay
import com.gallr.shared.map.toLocalTime
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Times an author's route in the author's order (spec 089). It never reorders or drops a stop: each stop gets a
 * [RouteStopVerdict] instead.
 *
 * The planned day is today, anchored at the later of now and the first stop's opening; when the first stop
 * cannot be visited today, it is the next day within a week on which the first stop's venue opens and its
 * exhibition runs, anchored at that opening. When the first stop has unknown hours, has ended or is no longer
 * listed, the plan starts now without moving to another day (design "Reference time", E-D15).
 */
object RouteEvaluator {
    fun evaluate(
        stops: List<PersonalRouteStop>,
        exhibitionsById: Map<String, Exhibition>,
        origin: GeoPoint?,
        now: Instant,
        zone: TimeZone,
        legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
        visitMinutesPerStop: Int = DEFAULT_VISIT_MINUTES,
    ): PersonalRouteEvaluation? {
        if (stops.isEmpty()) return null
        val clock = now.toLocalDateTime(zone)
        val nowMinutes = clock.time.minutesOfDay()
        val first = StopFacts(stops.first(), exhibitionsById[stops.first().exhibitionId])
        val plan = planDay(first, clock.date, nowMinutes)
        val estimator = SamePointIsFree(legEstimator)
        val timeline = RouteTimeline(visitMinutesPerStop, estimator)
        val start = origin ?: stops.first().point
        val facts = stops.map { StopFacts(it, exhibitionsById[it.exhibitionId]) }
        val timelineStops = facts.map { TimelineStop(it.stop.point, it.timedOpeningOn(plan.day)) }
        val firstLegMinutes = estimator.estimate(start, stops.first().point).travelMinutes
        val startMinutes =
            when (plan.reason) {
                PlannedDayReason.TODAY_NOW -> nowMinutes
                else -> (plan.anchorMinutes - firstLegMinutes).coerceAtLeast(0)
            }
        val entries = timeline.walk(start, timelineStops, startMinutes)
        val legs = legs(start, origin != null, stops, estimator)
        val showDeparture =
            origin != null &&
                (plan.reason == PlannedDayReason.LATER_AT_OPENING || startMinutes > nowMinutes)
        return PersonalRouteEvaluation(
            plannedDay = plan.day,
            plannedDayReason = plan.reason,
            anchor = plan.anchorMinutes.toLocalTime(),
            departure = if (showDeparture) startMinutes.toLocalTime() else null,
            stops =
                facts.zip(entries) { stopFacts, entry ->
                    EvaluatedStop(
                        stop = stopFacts.stop,
                        verdict = stopFacts.verdictOn(plan.day, entry),
                        arrival = entry.arrivalMinutes.withinDay(),
                        visitStart = entry.visitStartMinutes.withinDay(),
                        visitEnd = entry.visitEndMinutes.withinDay(),
                    )
                },
            legs = legs,
            totalDistanceMeters = legs.sumOf(EstimatedRouteLeg::distanceMeters),
            travelMinutes = legs.sumOf(EstimatedRouteLeg::estimatedTravelMinutes),
            visitMinutes = visitMinutesPerStop * stops.size,
            waitMinutes = entries.sumOf(TimelineEntry::waitMinutes),
        )
    }

    private fun planDay(
        first: StopFacts,
        today: LocalDate,
        nowMinutes: Int,
    ): PlannedDay {
        val hours = first.hours
        val unusable =
            first.exhibition == null ||
                hours.completeness == OpeningHoursCompleteness.UNKNOWN ||
                first.endedBy(today)
        if (unusable) {
            return PlannedDay(today, PlannedDayReason.TODAY_NOW, nowMinutes)
        }
        first.openingIfRunning(today)?.let { opening ->
            if (nowMinutes < opening.opens.minutesOfDay()) {
                return PlannedDay(today, PlannedDayReason.TODAY_AT_OPENING, opening.opens.minutesOfDay())
            }
            if (nowMinutes < opening.closes.minutesOfDay()) {
                return PlannedDay(today, PlannedDayReason.TODAY_NOW, nowMinutes)
            }
        }
        for (offset in 1..MAX_ROLLOVER_DAYS) {
            val day = today.plus(DatePeriod(days = offset))
            val opening = first.openingIfRunning(day) ?: continue
            return PlannedDay(day, PlannedDayReason.LATER_AT_OPENING, opening.opens.minutesOfDay())
        }
        return PlannedDay(today, PlannedDayReason.TODAY_NOW, nowMinutes)
    }

    private fun legs(
        start: GeoPoint,
        fromDevice: Boolean,
        stops: List<PersonalRouteStop>,
        estimator: RouteLegEstimator,
    ): List<EstimatedRouteLeg> {
        val legs = mutableListOf<EstimatedRouteLeg>()
        var from = start
        var fromId: String? = null
        stops.forEachIndexed { index, stop ->
            if (index > 0 || fromDevice) {
                val leg = estimator.estimate(from, stop.point)
                legs +=
                    EstimatedRouteLeg(
                        fromExhibitionId = fromId,
                        toExhibitionId = stop.exhibitionId,
                        distanceMeters = leg.distanceMeters,
                        estimatedTravelMinutes = leg.travelMinutes,
                        geometry = leg.geometry,
                        quality = leg.quality,
                    )
            }
            from = stop.point
            fromId = stop.exhibitionId
        }
        return legs
    }

    private data class PlannedDay(
        val day: LocalDate,
        val reason: PlannedDayReason,
        val anchorMinutes: Int,
    )

    /** What the catalogue says about one stop; [exhibition] is null when it is no longer listed. */
    private class StopFacts(
        val stop: PersonalRouteStop,
        val exhibition: Exhibition?,
    ) {
        val hours: WeeklyOpeningHours = exhibition?.let { parseOpeningHours(it.hours) } ?: WeeklyOpeningHours.UNKNOWN

        fun endedBy(day: LocalDate): Boolean = exhibition != null && day > exhibition.closingDate

        private fun runsOn(day: LocalDate): Boolean {
            val listed = exhibition ?: return false
            return day in listed.openingDate..listed.closingDate
        }

        /** The venue's opening on [day] when the exhibition runs that day and the venue is open. */
        fun openingIfRunning(day: LocalDate): DailyOpening? = if (runsOn(day)) hours.openingOn(day) else null

        /** The opening the timeline should wait for; only an open, running stop makes the visitor wait. */
        fun timedOpeningOn(day: LocalDate): DailyOpening? =
            if (hours.isKnownClosedOn(day)) null else openingIfRunning(day)

        fun verdictOn(
            day: LocalDate,
            entry: TimelineEntry,
        ): RouteStopVerdict {
            val listed = exhibition ?: return RouteStopVerdict.Unavailable
            if (day < listed.openingDate) return RouteStopVerdict.NotYetOpen(listed.openingDate)
            if (day > listed.closingDate) return RouteStopVerdict.Ended
            if (hours.completeness == OpeningHoursCompleteness.UNKNOWN) return RouteStopVerdict.HoursUnknown
            if (hours.isKnownClosedOn(day)) return RouteStopVerdict.ClosedOnPlannedDay
            val opening = hours.openingOn(day) ?: return RouteStopVerdict.HoursUnknown
            val closes = opening.closes.minutesOfDay()
            return when {
                entry.arrivalMinutes >= closes -> {
                    RouteStopVerdict.ArrivesAfterClose(
                        arrival = entry.arrivalMinutes.toLocalTime(),
                        closes = opening.closes,
                        afterMidnight = entry.arrivalMinutes >= MINUTES_PER_DAY,
                    )
                }

                entry.visitEndMinutes > closes -> {
                    RouteStopVerdict.VisitCutShort(closes - entry.visitStartMinutes)
                }

                else -> {
                    RouteStopVerdict.Open
                }
            }
        }
    }

    /** A stop's own point to itself costs nothing, so starting at stop 1 adds no first leg. */
    private class SamePointIsFree(
        private val delegate: RouteLegEstimator,
    ) : RouteLegEstimator {
        override fun estimate(
            from: GeoPoint,
            to: GeoPoint,
        ): EstimatedLeg =
            if (from == to) {
                EstimatedLeg(distanceMeters = 0, travelMinutes = 0, geometry = listOf(from, to))
            } else {
                delegate.estimate(from, to)
            }
    }
}

/** A clock time on the planned day, or null once the timeline has run past midnight. */
private fun Int.withinDay(): LocalTime? = takeIf { it < MINUTES_PER_DAY }?.toLocalTime()

/** Visit time per stop, matching the planner's default. */
const val DEFAULT_VISIT_MINUTES = 45

/** How far ahead the planned day may move when the first stop cannot be visited today. */
const val MAX_ROLLOVER_DAYS = 7
