package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.fixture.DiscoveryFixture
import com.gallr.shared.map.EstimatedLeg
import com.gallr.shared.map.RouteLegEstimator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 089 reference-day rule and verdicts (design "Reference time", E-D15, DR-D31). 2026-10-08 is a Thursday;
 * every clock is Asia/Seoul and the device zone never matters.
 */
class RouteEvaluatorTest {
    private val seoul = TimeZone.of("Asia/Seoul")
    private val tenMinutes = RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 800, travelMinutes = 10) }
    private val home = GeoPoint(37.560, 126.970)
    private val thursday = LocalDate(2026, 10, 8)
    private var nextLatitude = 37.580

    @Test
    fun beforeTheFirstStopOpensThePlanStartsAtItsOpeningWithADeparture() {
        val evaluation = evaluate(at(thursday, 8, 0), listOf(gallery("a"), gallery("b")), origin = home)

        assertEquals(thursday, evaluation.plannedDay)
        assertEquals(PlannedDayReason.TODAY_AT_OPENING, evaluation.plannedDayReason)
        assertEquals(LocalTime(10, 0), evaluation.anchor)
        assertEquals(LocalTime(9, 50), evaluation.departure)
        assertEquals(LocalTime(10, 0), evaluation.stops.first().arrival)
        assertEquals(RouteStopVerdict.Open, evaluation.stops.first().verdict)
    }

    @Test
    fun midDayThePlanStartsNowWithoutADepartureLine() {
        val evaluation = evaluate(at(thursday, 13, 0), listOf(gallery("a"), gallery("b")), origin = home)

        assertEquals(PlannedDayReason.TODAY_NOW, evaluation.plannedDayReason)
        assertEquals(LocalTime(13, 0), evaluation.anchor)
        assertNull(evaluation.departure, "leaving now is not news (DR-D31)")
        assertEquals(LocalTime(13, 10), evaluation.stops.first().arrival)
    }

    @Test
    fun afterTheFirstStopClosesThePlanMovesToTomorrowsOpening() {
        val evaluation = evaluate(at(thursday, 19, 0), listOf(gallery("a"), gallery("b")), origin = home)

        assertEquals(LocalDate(2026, 10, 9), evaluation.plannedDay)
        assertEquals(PlannedDayReason.LATER_AT_OPENING, evaluation.plannedDayReason)
        assertEquals(LocalTime(10, 0), evaluation.anchor)
        assertEquals(LocalTime(9, 50), evaluation.departure)
        assertEquals(RouteStopVerdict.Open, evaluation.stops.first().verdict)
    }

    @Test
    fun whenTheFirstStopIsClosedTodayThePlanMovesToItsNextOpenDay() {
        val monday = LocalDate(2026, 10, 12)
        val closedMonday = gallery("a", hours = "Tuesday–Sunday 10:00–18:00 · Closed Monday")

        val evaluation = evaluate(at(monday, 9, 0), listOf(closedMonday, gallery("b")), origin = null)

        assertEquals(LocalDate(2026, 10, 13), evaluation.plannedDay)
        assertEquals(PlannedDayReason.LATER_AT_OPENING, evaluation.plannedDayReason)
        assertNull(evaluation.departure, "with stop 1 as the start there is no departure line")
    }

    @Test
    fun theRolloverNeverPassesTheFirstExhibitionsEndDate() {
        val endsToday = gallery("a", closing = thursday)

        val evaluation = evaluate(at(thursday, 19, 0), listOf(endsToday, gallery("b")), origin = null)

        assertEquals(thursday, evaluation.plannedDay)
        assertEquals(PlannedDayReason.TODAY_NOW, evaluation.plannedDayReason)
        assertIs<RouteStopVerdict.ArrivesAfterClose>(evaluation.stops.first().verdict)
    }

    @Test
    fun aFirstStopOpeningWithinAWeekMovesThePlanToItsOpeningDay() {
        val opensSunday = gallery("a", opening = LocalDate(2026, 10, 11))

        val evaluation = evaluate(at(thursday, 9, 0), listOf(opensSunday, gallery("b")), origin = null)

        assertEquals(LocalDate(2026, 10, 11), evaluation.plannedDay)
        assertEquals(RouteStopVerdict.Open, evaluation.stops.first().verdict)
    }

    @Test
    fun aFirstStopOpeningAfterAWeekKeepsTodayAndIsFlagged() {
        val opensLater = gallery("a", opening = LocalDate(2026, 10, 20))

        val evaluation = evaluate(at(thursday, 9, 0), listOf(opensLater, gallery("b")), origin = null)

        assertEquals(thursday, evaluation.plannedDay)
        assertEquals(RouteStopVerdict.NotYetOpen(LocalDate(2026, 10, 20)), evaluation.stops.first().verdict)
        assertEquals(0, evaluation.firstConflictIndex)
    }

    @Test
    fun anUnreadableEndedOrMissingFirstStopAnchorsNowWithoutRollover() {
        val unknown = gallery("a", hours = "By appointment only")
        val ended = gallery("e", closing = LocalDate(2026, 10, 1))

        val unknownFirst = evaluate(at(thursday, 20, 0), listOf(unknown, gallery("b")), origin = null)
        val endedFirst = evaluate(at(thursday, 20, 0), listOf(ended, gallery("b")), origin = null)
        val missingFirst =
            RouteEvaluator.evaluate(
                stops = listOf(stopOf(gallery("gone")), stopOf(gallery("b"))),
                exhibitionsById = mapOf("b" to gallery("b")),
                origin = null,
                now = at(thursday, 20, 0),
                zone = seoul,
                legEstimator = tenMinutes,
            )

        listOf(unknownFirst, endedFirst, requireNotNull(missingFirst)).forEach {
            assertEquals(thursday, it.plannedDay)
            assertEquals(PlannedDayReason.TODAY_NOW, it.plannedDayReason)
            assertEquals(LocalTime(20, 0), it.anchor)
        }
        assertEquals(RouteStopVerdict.HoursUnknown, unknownFirst.stops.first().verdict)
        assertEquals(RouteStopVerdict.Ended, endedFirst.stops.first().verdict)
        assertEquals(RouteStopVerdict.Unavailable, missingFirst.stops.first().verdict)
    }

    @Test
    fun eachStopGetsItsOwnVerdictInAuthorOrder() {
        val stops =
            listOf(
                gallery("open"),
                gallery("closes-soon", hours = "Tuesday–Sunday 10:00–12:20 · Closed Monday"),
                gallery("already-closed", hours = "Tuesday–Sunday 10:00–12:45 · Closed Monday"),
                gallery("closed-thursday", hours = "Friday - Wednesday 10am - 6pm"),
                gallery("unknown", hours = null),
            )

        val evaluation = evaluate(at(thursday, 11, 0), stops, origin = null)

        assertEquals(
            listOf("open", "closes-soon", "already-closed", "closed-thursday", "unknown"),
            evaluation.stops.map { it.stop.exhibitionId },
        )
        assertEquals(RouteStopVerdict.Open, evaluation.stops[0].verdict)
        assertEquals(RouteStopVerdict.VisitCutShort(25), evaluation.stops[1].verdict)
        assertEquals(
            RouteStopVerdict.ArrivesAfterClose(arrival = LocalTime(12, 50), closes = LocalTime(12, 45)),
            evaluation.stops[2].verdict,
        )
        assertEquals(RouteStopVerdict.ClosedOnPlannedDay, evaluation.stops[3].verdict)
        assertEquals(RouteStopVerdict.HoursUnknown, evaluation.stops[4].verdict)
        assertEquals(3, evaluation.conflictCount)
        assertEquals(1, evaluation.firstConflictIndex)
    }

    @Test
    fun justBeforeAndAfterMidnightInSeoulPlanTheSameFriday() {
        val stops = listOf(gallery("a"), gallery("b"))
        val beforeMidnight = LocalDateTime(2026, 10, 8, 23, 59).toInstant(seoul)
        val afterMidnight = LocalDateTime(2026, 10, 9, 0, 1).toInstant(seoul)

        val late = evaluate(beforeMidnight, stops, origin = null)
        val early = evaluate(afterMidnight, stops, origin = null)

        assertEquals(LocalDate(2026, 10, 9), late.plannedDay)
        assertEquals(PlannedDayReason.LATER_AT_OPENING, late.plannedDayReason)
        assertEquals(LocalDate(2026, 10, 9), early.plannedDay)
        assertEquals(PlannedDayReason.TODAY_AT_OPENING, early.plannedDayReason)
    }

    @Test
    fun totalsCoverLegsVisitsAndWaiting() {
        val evaluation = evaluate(at(thursday, 13, 0), listOf(gallery("a"), gallery("b")), origin = home)

        assertEquals(2, evaluation.legs.size)
        assertEquals(1_600, evaluation.totalDistanceMeters)
        assertEquals(20, evaluation.travelMinutes)
        assertEquals(90, evaluation.visitMinutes)
        assertEquals(110, evaluation.totalMinutes)
    }

    @Test
    fun withoutADeviceOriginTheFirstLegIsSkipped() {
        val evaluation = evaluate(at(thursday, 13, 0), listOf(gallery("a"), gallery("b")), origin = null)

        assertEquals(1, evaluation.legs.size)
        assertEquals(LocalTime(13, 0), evaluation.stops.first().arrival)
    }

    @Test
    fun anEmptyRouteHasNoEvaluation() {
        assertNull(
            RouteEvaluator.evaluate(emptyList(), emptyMap(), null, at(thursday, 13, 0), seoul, tenMinutes),
        )
    }

    @Test
    fun everyPublishedFixturePairEvaluatesWithoutDroppingStops() {
        val exhibitions = DiscoveryFixture.exhibitions.filter { it.latitude != null }
        val byId = exhibitions.associateBy { it.id }
        exhibitions.zipWithNext().forEach { (first, second) ->
            val evaluation =
                RouteEvaluator.evaluate(
                    stops = listOf(stopOf(first), stopOf(second)),
                    exhibitionsById = byId,
                    origin = null,
                    now = at(LocalDate(2026, 10, 2), 15, 0),
                    zone = seoul,
                )
            assertEquals(listOf(first.id, second.id), requireNotNull(evaluation).stops.map { it.stop.exhibitionId })
        }
    }

    @Test
    fun stopsReachedAfterMidnightAreNotTimedAndSaySo() {
        val sevenHours = RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 30_000, travelMinutes = 420) }

        // 17:00 at stop 1, a 45-minute visit and seven hours of travel reach stop 2 at 00:45 the next day.
        val evaluation =
            evaluate(at(thursday, 17, 0), listOf(gallery("a"), gallery("b")), origin = null, legEstimator = sevenHours)

        val late = evaluation.stops[1]
        assertNull(late.arrival, "a clock time past midnight would name the wrong day")
        assertNull(late.visitStart)
        assertNull(late.visitEnd)
        val verdict = assertIs<RouteStopVerdict.ArrivesAfterClose>(late.verdict)
        assertTrue(verdict.afterMidnight)
        assertEquals(LocalTime(18, 0), verdict.closes)
        assertEquals(RouteStopVerdict.Open, evaluation.stops[0].verdict)
    }

    private fun evaluate(
        now: Instant,
        exhibitions: List<Exhibition>,
        origin: GeoPoint?,
        legEstimator: RouteLegEstimator = tenMinutes,
    ): PersonalRouteEvaluation =
        requireNotNull(
            RouteEvaluator.evaluate(
                stops = exhibitions.map(::stopOf),
                exhibitionsById = exhibitions.associateBy { it.id },
                origin = origin,
                now = now,
                zone = seoul,
                legEstimator = legEstimator,
            ),
        )

    private fun at(
        date: LocalDate,
        hour: Int,
        minute: Int,
    ): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(seoul)

    private fun stopOf(exhibition: Exhibition) = requireNotNull(exhibition.toRouteStop())

    private fun gallery(
        id: String,
        hours: String? = "Tuesday–Sunday 10:00–18:00 · Closed Monday",
        opening: LocalDate = LocalDate(2026, 9, 1),
        closing: LocalDate = LocalDate(2026, 12, 31),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = opening,
        closingDate = closing,
        isFeatured = false,
        latitude = nextLatitude.also { nextLatitude += 0.001 },
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = hours,
    )
}
