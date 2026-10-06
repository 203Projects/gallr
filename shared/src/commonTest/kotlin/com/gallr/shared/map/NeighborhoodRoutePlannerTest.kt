package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.hours.parseOpeningHours
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RouteRelevance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NeighborhoodRoutePlannerTest {
    /** A Monday, so "Tuesday - Sunday" venues are closed on the visit date. */
    private val today = LocalDate(2026, 8, 31)
    private val origin = GeoPoint(37.5665, 126.9780)
    private val planner = NeighborhoodRoutePlanner()

    private companion object {
        const val OPEN_DAILY = "10am - 6pm\nMonday - Sunday"
    }

    @Test
    fun `neighborhood route returns distinct ordered stops and estimated totals`() {
        val result =
            planner.plan(
                exhibitions =
                    listOf(
                        exhibition("near", 37.567, 126.979, venue = "A"),
                        exhibition("middle", 37.570, 126.985, venue = "B"),
                        exhibition("far", 37.575, 126.990, venue = "C"),
                    ),
                bookmarkedIds = emptySet(),
                request = request(RouteCurationMode.NEIGHBORHOOD, stopCount = 3),
            )

        val route = assertIs<RoutePlanResult.Success>(result).route
        assertEquals(3, route.stops.size)
        assertEquals(3, route.legs.size)
        assertTrue(route.totalDistanceKm > 0.0)
        assertTrue(route.estimatedTravelMinutes > 0)
        assertEquals(135, route.estimatedVisitMinutes)
        assertTrue(RouteWarning.APPROXIMATE_DISTANCE in route.warnings)
        assertTrue(RouteWarning.HOURS_UNVERIFIED in route.warnings)
        assertTrue(route.recommendationEvidenceByExhibitionId.isEmpty())
    }

    @Test
    fun `for you route selects highest relevance then minimizes travel ordering`() {
        val low = exhibition("low", 37.567, 126.979, venue = "A")
        val high = exhibition("high", 37.575, 126.990, venue = "B")
        val medium = exhibition("medium", 37.570, 126.985, venue = "C")
        val relevance =
            listOf(
                relevance(high, 9000, personal = true),
                relevance(medium, 8000, personal = true),
                relevance(low, 1000),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    exhibitions = listOf(low, high, medium),
                    bookmarkedIds = emptySet(),
                    request = request(RouteCurationMode.FOR_YOU, stopCount = 2),
                    forYouRelevance = relevance,
                ),
            ).route

        assertEquals(setOf("high", "medium"), route.stops.map { it.id }.toSet())
        assertEquals(
            route.stops.associate { it.id to listOf(RecommendationEvidence.Featured) },
            route.recommendationEvidenceByExhibitionId,
        )
    }

    @Test
    fun `for you selection trades a small relevance difference for a much shorter route`() {
        val distantHigh = exhibition("distant-high", 37.605, 126.978, venue = "Far")
        val nearbyMedium = exhibition("near-medium", 37.568, 126.979, venue = "Near A")
        val nearbySecond = exhibition("near-second", 37.569, 126.980, venue = "Near B")
        val relevance =
            listOf(
                relevance(distantHigh, 9_000, personal = true),
                relevance(nearbyMedium, 8_700, personal = true),
                relevance(nearbySecond, 8_600, personal = true),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(distantHigh, nearbyMedium, nearbySecond),
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                    relevance,
                ),
            ).route

        assertEquals(setOf("near-medium", "near-second"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `for you route prefers a compact same direction cluster over opposite stops`() {
        val eastA = exhibition("east-a", 37.5665, 126.988, "East A")
        val eastB = exhibition("east-b", 37.5665, 126.998, "East B")
        val west = exhibition("west", 37.5665, 126.968, "West")
        val relevance =
            listOf(
                relevance(eastA, 8_800, personal = true),
                relevance(west, 8_790, personal = true),
                relevance(eastB, 8_700, personal = true),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(west, eastB, eastA),
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                    relevance,
                ),
            ).route

        assertEquals(setOf("east-a", "east-b"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `for you route fills a full route from non personal candidates`() {
        val a = exhibition("a", 37.567, 126.979, "A")
        val b = exhibition("b", 37.570, 126.985, "B")
        val c = exhibition("c", 37.575, 126.990, "C")
        val relevance = listOf(relevance(a, 900, evidence = emptyList()), relevance(b, 800), relevance(c, 700))

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(listOf(a, b, c), emptySet(), request(RouteCurationMode.FOR_YOU, 3), relevance),
            ).route

        assertEquals(3, route.stops.size)
        assertEquals(setOf("a", "b", "c"), route.recommendationEvidenceByExhibitionId.keys)
        assertEquals(emptyList(), route.recommendationEvidenceByExhibitionId.getValue("a"))
    }

    @Test
    fun `for you prefers a personal candidate over a non personal one at a similar distance`() {
        val personal = exhibition("personal", 37.5625, 126.978, "South")
        val fillerNear = exhibition("filler-near", 37.5705, 126.978, "North A")
        val fillerNext = exhibition("filler-next", 37.5725, 126.978, "North B")
        val relevance =
            listOf(
                relevance(personal, 2_000, personal = true),
                relevance(fillerNear, 2_000),
                relevance(fillerNext, 2_000),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(fillerNext, fillerNear, personal),
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                    relevance,
                ),
            ).route

        assertTrue("personal" in route.stops.map { it.id }, route.stops.map { it.id }.toString())
    }

    @Test
    fun `for you drops a personal candidate that needs a large detour`() {
        val personalFar = exhibition("personal-far", 37.5365, 126.978, "Far South")
        val fillerNear = exhibition("filler-near", 37.5705, 126.978, "North A")
        val fillerNext = exhibition("filler-next", 37.5725, 126.978, "North B")
        val relevance =
            listOf(
                relevance(personalFar, 2_000, personal = true),
                relevance(fillerNear, 2_000),
                relevance(fillerNext, 2_000),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(personalFar, fillerNear, fillerNext),
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                    relevance,
                ),
            ).route

        assertEquals(setOf("filler-near", "filler-next"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `for you keeps a saved exhibition as a stop with saved evidence`() {
        val saved = exhibition("saved", 37.567, 126.979, "A")
        val other = exhibition("other", 37.570, 126.985, "B")
        val relevance =
            listOf(
                relevance(saved, 6_000, personal = true, evidence = listOf(RecommendationEvidence.Saved)),
                relevance(other, 800),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(other, saved),
                    setOf(saved.id),
                    request(RouteCurationMode.FOR_YOU, 2),
                    relevance,
                ),
            ).route

        assertEquals(setOf("saved", "other"), route.stops.map { it.id }.toSet())
        assertEquals(
            listOf(RecommendationEvidence.Saved),
            route.recommendationEvidenceByExhibitionId.getValue("saved"),
        )
    }

    @Test
    fun `for you candidates outside the relevance list are never stops`() {
        val ranked = exhibition("ranked", 37.567, 126.979, "A")
        val rankedToo = exhibition("ranked-too", 37.570, 126.985, "B")
        val unranked = exhibition("unranked", 37.568, 126.980, "C")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(unranked, rankedToo, ranked),
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                    listOf(relevance(ranked, 500), relevance(rankedToo, 400)),
                ),
            ).route

        assertEquals(setOf("ranked", "ranked-too"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `saved route includes only bookmarked exhibitions`() {
        val savedA = exhibition("saved-a", 37.567, 126.979, venue = "A")
        val savedB = exhibition("saved-b", 37.570, 126.985, venue = "B")
        val other = exhibition("other", 37.568, 126.980, venue = "C")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    exhibitions = listOf(other, savedB, savedA),
                    bookmarkedIds = setOf(savedA.id, savedB.id),
                    request = request(RouteCurationMode.SAVED, stopCount = 2),
                ),
            ).route

        assertEquals(setOf(savedA.id, savedB.id), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `closing soon route prioritizes the earliest eligible closing dates`() {
        val soon = exhibition("soon", 37.567, 126.979, "A", closingDate = LocalDate(2026, 8, 31))
        val next = exhibition("next", 37.570, 126.985, "B", closingDate = LocalDate(2026, 9, 2))
        val later = exhibition("later", 37.568, 126.980, "C", closingDate = LocalDate(2026, 9, 20))

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    exhibitions = listOf(later, next, soon),
                    bookmarkedIds = emptySet(),
                    request = request(RouteCurationMode.CLOSING_SOON, stopCount = 2),
                ),
            ).route

        assertEquals(setOf("soon", "next"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `invalid coordinates duplicate venues and ended exhibitions are excluded`() {
        val validA = exhibition("a", 37.567, 126.979, "Same")
        val duplicate = exhibition("duplicate", 37.567, 126.979, "Same")
        val validB = exhibition("b", 37.570, 126.985, "Other")
        val invalid = exhibition("invalid", Double.NaN, 126.98, "Invalid")
        val ended = exhibition("ended", 37.568, 126.981, "Ended", closingDate = LocalDate(2026, 8, 29))

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    exhibitions = listOf(duplicate, invalid, ended, validB, validA),
                    bookmarkedIds = emptySet(),
                    request = request(RouteCurationMode.NEIGHBORHOOD, stopCount = 2),
                ),
            ).route

        assertEquals(2, route.stops.size)
        assertEquals(
            2,
            route.stops
                .map { it.venueNameEn }
                .distinct()
                .size,
        )
    }

    @Test
    fun `gallery identity deduplicates coordinate jitter and renamed venue text`() {
        val first = exhibition("first", 37.567000, 126.979000, "Old name").copy(galleryId = "gallery-one")
        val duplicate = exhibition("duplicate", 37.567001, 126.979001, "New name").copy(galleryId = "gallery-one")
        val other = exhibition("other", 37.570, 126.985, "Other").copy(galleryId = "gallery-two")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(duplicate, other, first),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2),
                ),
            ).route

        assertEquals(
            2,
            route.stops
                .mapNotNull { it.galleryId }
                .distinct()
                .size,
        )

        val legacyFirst = exhibition("legacy-first", 37.567000, 126.979000, "Legacy")
        val legacyDuplicate = exhibition("legacy-duplicate", 37.567001, 126.979001, "Legacy")
        val legacyOther = exhibition("legacy-other", 37.570, 126.985, "Other")
        val legacyRoute =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(legacyDuplicate, legacyOther, legacyFirst),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2),
                ),
            ).route
        assertEquals(
            2,
            legacyRoute.stops
                .map { it.venueNameEn }
                .distinct()
                .size,
        )

        val distinctAddressA =
            exhibition("address-a", 37.567, 126.979, "Common").copy(addressEn = "1 First Street")
        val distinctAddressB =
            exhibition("address-b", 37.568, 126.980, "Common").copy(addressEn = "2 Second Street")
        val distinctAddressRoute =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(distinctAddressB, distinctAddressA),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2),
                ),
            ).route
        assertEquals(setOf("address-a", "address-b"), distinctAddressRoute.stops.map { it.id }.toSet())
    }

    @Test
    fun `authoritative provider geometry removes approximate warning and is pair cached`() {
        var calls = 0
        val provider =
            RouteLegEstimator { from, to ->
                calls += 1
                EstimatedLeg(
                    distanceMeters = 100,
                    travelMinutes = 2,
                    geometry = listOf(from, to),
                    quality = RouteLegQuality.ROUTED,
                )
            }
        val routePlanner = NeighborhoodRoutePlanner(provider)
        val exhibitions =
            (1..5).map { index ->
                exhibition("ex-$index", 37.566 + index * 0.001, 126.978 + index * 0.001, "V$index")
            }

        val route =
            assertIs<RoutePlanResult.Success>(
                routePlanner.plan(
                    exhibitions,
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 5),
                ),
            ).route

        assertTrue(RouteWarning.APPROXIMATE_DISTANCE !in route.warnings)
        assertTrue(route.legs.all { it.geometry.isNotEmpty() && it.quality == RouteLegQuality.ROUTED })
        assertTrue(calls <= 30, "provider called $calls times")
    }

    @Test
    fun `insufficient candidates and invalid stop bounds are explicit`() {
        val one = exhibition("one", 37.567, 126.979, "A")

        assertIs<RoutePlanResult.InsufficientCandidates>(
            planner.plan(
                listOf(one),
                emptySet(),
                request(RouteCurationMode.NEIGHBORHOOD, 2),
            ),
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            request(RouteCurationMode.NEIGHBORHOOD, 1)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            request(RouteCurationMode.NEIGHBORHOOD, 6)
        }
    }

    @Test
    fun `input order produces identical route and arithmetic`() {
        val exhibitions =
            listOf(
                exhibition("a", 37.567, 126.979, "A"),
                exhibition("b", 37.570, 126.985, "B"),
                exhibition("c", 37.575, 126.990, "C"),
            )
        val request = request(RouteCurationMode.NEIGHBORHOOD, 3)

        val forward =
            assertIs<RoutePlanResult.Success>(
                planner.plan(exhibitions, emptySet(), request),
            ).route
        val reverse =
            assertIs<RoutePlanResult.Success>(
                planner.plan(exhibitions.reversed(), emptySet(), request),
            ).route

        assertEquals(forward.stops.map { it.id }, reverse.stops.map { it.id })
        assertEquals(forward.totalDistanceMeters, reverse.totalDistanceMeters)
        assertEquals(forward.estimatedTravelMinutes, reverse.estimatedTravelMinutes)
    }

    @Test
    fun `venues closed on the visit weekday are excluded in every mode`() {
        val closedMonday = exhibition("closed-mon", 37.567, 126.979, "Closed", hours = "10am - 6pm\nTuesday - Sunday")
        val openA = exhibition("open-a", 37.570, 126.985, "Open A", hours = OPEN_DAILY)
        val openB = exhibition("open-b", 37.572, 126.988, "Open B", hours = OPEN_DAILY)
        val exhibitions = listOf(closedMonday, openA, openB)
        val relevance = exhibitions.map { relevance(it, 5_000, personal = true) }

        RouteCurationMode.entries.forEach { mode ->
            val route =
                assertIs<RoutePlanResult.Success>(
                    planner.plan(exhibitions, exhibitions.map { it.id }.toSet(), request(mode, 2), relevance),
                    mode.name,
                ).route
            assertEquals(setOf("open-a", "open-b"), route.stops.map { it.id }.toSet(), mode.name)
        }
    }

    @Test
    fun `known hours bound every stop between opening and closing`() {
        val latecomer = exhibition("noon", 37.567, 126.979, "Noon", hours = "12pm - 6pm\nMonday - Sunday")
        val early = exhibition("ten", 37.570, 126.985, "Ten", hours = OPEN_DAILY)

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(latecomer, early),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(10, 0)),
                ),
            ).route

        assertEquals(route.stops.map { it.id }, route.stopSchedules.map { it.exhibitionId })
        route.stopSchedules.forEach { schedule ->
            val opening = parseOpening(route.stops.first { it.id == schedule.exhibitionId }.hours)
            assertTrue(schedule.visitStart >= opening.opens, schedule.toString())
            assertTrue(schedule.visitEnd <= opening.closes, schedule.toString())
            assertTrue(schedule.visitStart >= schedule.arrival, schedule.toString())
            assertEquals(RouteStopHoursStatus.VERIFIED, schedule.hoursStatus)
        }
        assertTrue(route.estimatedWaitMinutes > 0)
        assertEquals(
            route.estimatedTravelMinutes + route.estimatedVisitMinutes + route.estimatedWaitMinutes,
            route.estimatedTotalMinutes,
        )
    }

    @Test
    fun `only a schedule feasible ordering is returned`() {
        val nearLate = exhibition("near-late", 37.5675, 126.978, "Near", hours = "1pm - 6pm\nMonday - Sunday")
        val farEarly = exhibition("far-early", 37.5845, 126.978, "Far", hours = "9am - 11:30am\nMonday - Sunday")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(nearLate, farEarly),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(10, 0)),
                ),
            ).route

        assertEquals(listOf("far-early", "near-late"), route.stops.map { it.id })
        assertTrue(route.stopSchedules.first().visitEnd <= LocalTime(11, 30))
        assertEquals(LocalTime(13, 0), route.stopSchedules.last().visitStart)
    }

    @Test
    fun `an early arrival waits for opening and the wait is counted`() {
        val eleven = exhibition("eleven", 37.567, 126.979, "Eleven", hours = "11am - 6pm\nMonday - Sunday")
        val open = exhibition("open", 37.570, 126.985, "Open", hours = OPEN_DAILY)

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(eleven, open),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(10, 0)),
                ),
            ).route

        val waits = route.stopSchedules.map { it.visitStart.toSecondOfDay() / 60 - it.arrival.toSecondOfDay() / 60 }
        assertEquals(waits.sum(), route.estimatedWaitMinutes)
        val elevenSchedule = route.stopSchedules.first { it.exhibitionId == "eleven" }
        assertEquals(LocalTime(11, 0), elevenSchedule.visitStart)
        assertEquals(LocalTime(11, 45), elevenSchedule.visitEnd)
    }

    @Test
    fun `non for you modes fall back to first fit when the nearest set cannot be scheduled`() {
        val nearA = exhibition("near-a", 37.5675, 126.978, "Near A", hours = "10am - 5pm\nMonday - Sunday")
        val nearB = exhibition("near-b", 37.5680, 126.978, "Near B", hours = "10am - 5pm\nMonday - Sunday")
        val lateOpen = exhibition("late-open", 37.5700, 126.978, "Late", hours = "10am - 8pm\nMonday - Sunday")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(nearA, nearB, lateOpen),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(16, 0)),
                ),
            ).route

        assertEquals(setOf("near-a", "late-open"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `unverified hours are disclosed per stop and the route warning follows`() {
        val verified = exhibition("verified", 37.567, 126.979, "V", hours = OPEN_DAILY)
        val partial = exhibition("partial", 37.570, 126.985, "P", hours = "12pm - 7pm")
        val unknown = exhibition("unknown", 37.572, 126.988, "U", hours = null)

        val mixed =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(verified, partial, unknown),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 3),
                ),
            ).route
        assertEquals(
            mapOf(
                "verified" to RouteStopHoursStatus.VERIFIED,
                "partial" to RouteStopHoursStatus.UNVERIFIED,
                "unknown" to RouteStopHoursStatus.UNVERIFIED,
            ),
            mixed.stopSchedules.associate { it.exhibitionId to it.hoursStatus },
        )
        assertTrue(RouteWarning.HOURS_UNVERIFIED in mixed.warnings)

        val allVerified = exhibition("verified-b", 37.570, 126.985, "VB", hours = OPEN_DAILY)
        val clean =
            assertIs<RoutePlanResult.Success>(
                planner.plan(listOf(verified, allVerified), emptySet(), request(RouteCurationMode.NEIGHBORHOOD, 2)),
            ).route
        assertTrue(RouteWarning.HOURS_UNVERIFIED !in clean.warnings)
    }

    @Test
    fun `closures are counted in the shortage`() {
        val closedA = exhibition("closed-a", 37.567, 126.979, "CA", hours = "10am - 6pm\nTuesday - Sunday")
        val closedB = exhibition("closed-b", 37.570, 126.985, "CB", hours = "11am - 6pm\nTuesday - Saturday")
        val open = exhibition("open", 37.572, 126.988, "O", hours = OPEN_DAILY)

        val result =
            assertIs<RoutePlanResult.InsufficientCandidates>(
                planner.plan(listOf(closedA, closedB, open), emptySet(), request(RouteCurationMode.NEIGHBORHOOD, 2)),
            )

        assertEquals(RoutePlanResult.InsufficientCandidates(requested = 2, available = 1, closedCount = 2), result)
    }

    @Test
    fun `a venue closing too soon after the start counts as closed`() {
        val closingSoon = exhibition("closing", 37.567, 126.979, "Soon", hours = "10am - 4:30pm\nMonday - Sunday")
        val open = exhibition("open", 37.570, 126.985, "Open", hours = OPEN_DAILY)

        val result =
            planner.plan(
                listOf(closingSoon, open),
                emptySet(),
                request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(16, 0)),
            )

        assertEquals(RoutePlanResult.InsufficientCandidates(requested = 2, available = 1, closedCount = 1), result)
    }

    @Test
    fun `when every venue with known hours is closed the result is a shortage not an unverified route`() {
        val closedA = exhibition("closed-a", 37.567, 126.979, "Known A", hours = "10am - 6pm\nMonday - Sunday")
        val closedB = exhibition("closed-b", 37.570, 126.985, "Known B", hours = "11am - 7pm\nMonday - Sunday")
        val unknownA = exhibition("unknown-a", 37.568, 126.980, "Unknown A", hours = null)
        val unknownB = exhibition("unknown-b", 37.572, 126.988, "Unknown B", hours = "By appointment")

        val result =
            planner.plan(
                listOf(unknownA, closedA, unknownB, closedB),
                emptySet(),
                request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(19, 50)),
            )

        assertEquals(RoutePlanResult.InsufficientCandidates(requested = 2, available = 0, closedCount = 2), result)
    }

    @Test
    fun `an open venue with known hours still allows unknown hours stops alongside it`() {
        val openLate = exhibition("open-late", 37.567, 126.979, "Late", hours = "12pm - 9pm\nMonday - Sunday")
        val closed = exhibition("closed", 37.570, 126.985, "Closed", hours = "10am - 6pm\nMonday - Sunday")
        val unknown = exhibition("unknown", 37.568, 126.980, "Unknown", hours = null)

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(unknown, closed, openLate),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(18, 30)),
                ),
            ).route

        assertEquals(setOf("open-late", "unknown"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `venues without any known hours still form a route when nothing nearby is known to be closed`() {
        val unknownA = exhibition("unknown-a", 37.567, 126.979, "A", hours = null)
        val unknownB = exhibition("unknown-b", 37.570, 126.985, "B", hours = "By appointment")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(unknownA, unknownB),
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 2, startTime = LocalTime(21, 0)),
                ),
            ).route

        assertEquals(2, route.stops.size)
        assertTrue(RouteWarning.HOURS_UNVERIFIED in route.warnings)
    }

    @Test
    fun `without a start time the route starts at the earliest opening among candidates`() {
        val eleven = exhibition("eleven", 37.567, 126.979, "Eleven", hours = "11am - 6pm\nMonday - Sunday")
        val one = exhibition("one", 37.570, 126.985, "One", hours = "1pm - 6pm\nMonday - Sunday")

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(listOf(one, eleven), emptySet(), request(RouteCurationMode.NEIGHBORHOOD, 2)),
            ).route

        val firstArrival = route.stopSchedules.first().arrival
        val firstArrivalMinutes = firstArrival.toSecondOfDay() / 60
        assertEquals(11 * 60 + route.legs.first().estimatedTravelMinutes, firstArrivalMinutes)
    }

    @Test
    fun `routes that fit within hours keep the unconstrained stops and order`() {
        val unconstrained =
            listOf(
                exhibition("a", 37.567, 126.979, "A"),
                exhibition("b", 37.570, 126.985, "B"),
                exhibition("c", 37.575, 126.990, "C"),
            )
        val generous = unconstrained.map { it.copy(hours = "9am - 9pm\nMonday - Sunday") }

        val before =
            assertIs<RoutePlanResult.Success>(
                planner.plan(unconstrained, emptySet(), request(RouteCurationMode.NEIGHBORHOOD, 3)),
            ).route
        val after =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    generous,
                    emptySet(),
                    request(RouteCurationMode.NEIGHBORHOOD, 3, startTime = LocalTime(10, 0)),
                ),
            ).route

        assertEquals(before.stops.map { it.id }, after.stops.map { it.id })
        assertEquals(before.totalDistanceMeters, after.totalDistanceMeters)
    }

    private fun parseOpening(hours: String?) =
        parseOpeningHours(hours).openingOn(today) ?: error("expected known hours: $hours")

    private fun request(
        mode: RouteCurationMode,
        stopCount: Int,
        startTime: LocalTime? = null,
    ) = RoutePlanningRequest(
        origin = origin,
        visitDate = today,
        mode = mode,
        stopCount = stopCount,
        maxRadiusKm = 5.0,
        visitMinutesPerStop = 45,
        startTime = startTime,
    )

    private fun relevance(
        exhibition: Exhibition,
        score: Int,
        personal: Boolean = false,
        evidence: List<RecommendationEvidence> = listOf(RecommendationEvidence.Featured),
    ) = RouteRelevance(
        exhibition = exhibition,
        scoreBasisPoints = score,
        evidence = evidence,
        hasPersonalEvidence = personal,
    )

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
        venue: String,
        closingDate: LocalDate = LocalDate(2026, 9, 15),
        hours: String? = null,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = venue,
        venueNameEn = venue,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = closingDate,
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = hours,
    )
}
