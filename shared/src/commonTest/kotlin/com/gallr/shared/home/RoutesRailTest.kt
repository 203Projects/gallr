package com.gallr.shared.home

import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 091 (R5): the Home rail shows the public ranking's first routes in the ranking's order. */
class RoutesRailTest {
    @Test
    fun keepsTheRankingOrderAndStopsAtThree() {
        val ranked =
            listOf(
                summary("a", copies = 9),
                summary("b", copies = 5),
                summary("c", copies = 5),
                summary("d", copies = 1),
            )

        assertEquals(listOf("a", "b", "c"), routesRail(ranked).map { it.id })
    }

    @Test
    fun passesFewerThanThreeThrough() {
        assertEquals(listOf("a", "b"), routesRail(listOf(summary("a"), summary("b"))).map { it.id })
        assertEquals(emptyList(), routesRail(emptyList()))
    }

    @Test
    fun aRouteCardShowsItsStopsCoversUpToTheCardLimit() {
        assertEquals(4, HOME_ROUTE_COVER_LIMIT)
        val route = HomeRoute(summary("a").copy(stopCount = 6), stopCoverUrls = listOf("1", "2", "3", "4", "5"))
        val bare = HomeRoute(summary("b").copy(stopCount = 2))

        assertEquals(listOf("1", "2", "3", "4"), route.shownCovers)
        // Six stops, four covers on the card: two more are counted, whether or not they have a cover.
        assertEquals(2, route.hiddenStopCount)
        assertEquals(emptyList(), bare.shownCovers)
        assertEquals(0, bare.hiddenStopCount)
    }

    @Test
    fun aLimitOfZeroShowsNothing() {
        assertEquals(emptyList(), routesRail(listOf(summary("a")), limit = 0))
        assertEquals(3, HOME_ROUTES_LIMIT)
    }

    private fun summary(
        id: String,
        copies: Int = 0,
    ) = PublicRouteSummary(
        id = id,
        name = "동선 $id",
        stopCount = 3,
        firstDistrictKo = "종로구",
        firstDistrictEn = "Jongno-gu",
        lastDistrictKo = "용산구",
        lastDistrictEn = "Yongsan-gu",
        authorDisplayName = "hanshin",
        isEditor = false,
        copyCount30d = copies,
        firstSharedDay = LocalDate(2026, 10, 12),
        approvedAt = Instant.parse("2026-10-10T00:00:00Z"),
    )
}
