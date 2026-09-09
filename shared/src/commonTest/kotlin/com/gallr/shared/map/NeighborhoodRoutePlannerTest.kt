package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NeighborhoodRoutePlannerTest {
    private val today = LocalDate(2026, 8, 30)
    private val origin = GeoPoint(37.5665, 126.9780)
    private val planner = NeighborhoodRoutePlanner()

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
                recommendations = emptyList(),
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
    fun `for you route selects highest recommendations then minimizes travel ordering`() {
        val low = exhibition("low", 37.567, 126.979, venue = "A")
        val high = exhibition("high", 37.575, 126.990, venue = "B")
        val medium = exhibition("medium", 37.570, 126.985, venue = "C")
        val recommendations =
            listOf(
                recommendation(high, 9000),
                recommendation(medium, 8000),
                recommendation(low, 1000),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    exhibitions = listOf(low, high, medium),
                    recommendations = recommendations,
                    bookmarkedIds = emptySet(),
                    request = request(RouteCurationMode.FOR_YOU, stopCount = 2),
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
        val recommendations =
            listOf(
                recommendation(distantHigh, 9_000),
                recommendation(nearbyMedium, 8_700),
                recommendation(nearbySecond, 8_600),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(distantHigh, nearbyMedium, nearbySecond),
                    recommendations,
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                ),
            ).route

        assertEquals(setOf("near-medium", "near-second"), route.stops.map { it.id }.toSet())
    }

    @Test
    fun `for you route prefers a compact same direction cluster over opposite stops`() {
        val eastA = exhibition("east-a", 37.5665, 126.988, "East A")
        val eastB = exhibition("east-b", 37.5665, 126.998, "East B")
        val west = exhibition("west", 37.5665, 126.968, "West")
        val recommendations =
            listOf(
                recommendation(eastA, 8_800),
                recommendation(west, 8_790),
                recommendation(eastB, 8_700),
            )

        val route =
            assertIs<RoutePlanResult.Success>(
                planner.plan(
                    listOf(west, eastB, eastA),
                    recommendations,
                    emptySet(),
                    request(RouteCurationMode.FOR_YOU, 2),
                ),
            ).route

        assertEquals(setOf("east-a", "east-b"), route.stops.map { it.id }.toSet())
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
                    recommendations = emptyList(),
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
                    recommendations = emptyList(),
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
                    recommendations = emptyList(),
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
                    emptyList(),
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
                    emptyList(),
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
                    emptyList(),
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
                    emptyList(),
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
                emptyList(),
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
                planner.plan(exhibitions, emptyList(), emptySet(), request),
            ).route
        val reverse =
            assertIs<RoutePlanResult.Success>(
                planner.plan(exhibitions.reversed(), emptyList(), emptySet(), request),
            ).route

        assertEquals(forward.stops.map { it.id }, reverse.stops.map { it.id })
        assertEquals(forward.totalDistanceMeters, reverse.totalDistanceMeters)
        assertEquals(forward.estimatedTravelMinutes, reverse.estimatedTravelMinutes)
    }

    private fun request(
        mode: RouteCurationMode,
        stopCount: Int,
    ) = RoutePlanningRequest(
        origin = origin,
        visitDate = today,
        mode = mode,
        stopCount = stopCount,
        maxRadiusKm = 5.0,
        visitMinutesPerStop = 45,
    )

    private fun recommendation(
        exhibition: Exhibition,
        score: Int,
    ) = ExhibitionRecommendation(exhibition, score, listOf(RecommendationEvidence.Featured))

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
        venue: String,
        closingDate: LocalDate = LocalDate(2026, 9, 15),
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
    )
}
