package com.gallr.shared.route

import com.gallr.shared.map.EstimatedRouteLeg
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** What a stop's timing on the planned day means for the visitor (spec 089 FR-006, DR-D8). */
sealed interface RouteStopVerdict {
    /** Open for the whole visit. */
    data object Open : RouteStopVerdict

    /** The venue is closed on the planned day. */
    data object ClosedOnPlannedDay : RouteStopVerdict

    /**
     * The visitor arrives at or after closing time. [afterMidnight] is true when the arrival falls on the next
     * calendar day, so [arrival] is not a meaningful clock time.
     */
    data class ArrivesAfterClose(
        val arrival: LocalTime,
        val closes: LocalTime,
        val afterMidnight: Boolean = false,
    ) : RouteStopVerdict

    /** The venue closes before a full visit ends; [minutes] are left to see the exhibition. */
    data class VisitCutShort(
        val minutes: Int,
    ) : RouteStopVerdict

    /** The exhibition has not opened by the planned day. */
    data class NotYetOpen(
        val openingDate: LocalDate,
    ) : RouteStopVerdict

    /** The exhibition has closed by the planned day. */
    data object Ended : RouteStopVerdict

    /** The exhibition is no longer in the catalogue; the stop renders from its saved snapshot. */
    data object Unavailable : RouteStopVerdict

    /** The venue's hours could not be read, so the visit cannot be checked. */
    data object HoursUnknown : RouteStopVerdict
}

/** True for statuses that make a stop unvisitable as planned; [RouteStopVerdict.HoursUnknown] is informational. */
val RouteStopVerdict.isConflict: Boolean
    get() = this != RouteStopVerdict.Open && this != RouteStopVerdict.HoursUnknown

/** Timing and status of one stop in author order. Times are null when the stop could not be timed. */
data class EvaluatedStop(
    val stop: PersonalRouteStop,
    val verdict: RouteStopVerdict,
    val arrival: LocalTime?,
    val visitStart: LocalTime?,
    val visitEnd: LocalTime?,
)

/** Why the evaluator chose its planned day (design "Reference time"). */
enum class PlannedDayReason {
    /** Today, starting now. */
    TODAY_NOW,

    /** Today, starting when the first stop opens later today. */
    TODAY_AT_OPENING,

    /** A later day within a week, starting when the first stop opens. */
    LATER_AT_OPENING,
}

/**
 * The evaluated route: the planned day, when it starts, each stop in author order, the legs and totals.
 *
 * [departure] is set only when a starting location is known and the visitor should leave later than now
 * (DR-D31). [firstConflictIndex] points at the first stop whose verdict is a conflict.
 */
data class PersonalRouteEvaluation(
    val plannedDay: LocalDate,
    val plannedDayReason: PlannedDayReason,
    val anchor: LocalTime,
    val departure: LocalTime?,
    val stops: List<EvaluatedStop>,
    val legs: List<EstimatedRouteLeg>,
    val totalDistanceMeters: Int,
    val travelMinutes: Int,
    val visitMinutes: Int,
    val waitMinutes: Int,
) {
    val conflictCount: Int get() = stops.count { it.verdict.isConflict }
    val firstConflictIndex: Int? get() = stops.indexOfFirst { it.verdict.isConflict }.takeIf { it >= 0 }
    val totalMinutes: Int get() = travelMinutes + visitMinutes + waitMinutes
}
