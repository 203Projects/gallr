package com.gallr.shared.route

import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Spec 092: paging rules for the 동선 둘러보기 screen (data-model.md). */
class PublicRouteBrowseTest {
    @Test
    fun aPageIsAppendedInItsOrder() {
        val loaded = listOf(route("a"), route("b"))

        val appended = appendPage(loaded, PublicRoutePage(listOf(route("c"), route("d")), hasMore = true))

        assertEquals(listOf("a", "b", "c", "d"), appended.map { it.summary.id })
    }

    @Test
    fun aRouteAlreadyLoadedIsNotShownTwice() {
        val loaded = listOf(route("a"), route("b"))

        val appended = appendPage(loaded, PublicRoutePage(listOf(route("b"), route("c"), route("c")), hasMore = false))

        assertEquals(listOf("a", "b", "c"), appended.map { it.summary.id })
    }

    @Test
    fun theNextOffsetCountsTheRowsRequestedNotTheRowsKept() {
        assertEquals(0, nextOffset(pagesLoaded = 0, pageSize = 20))
        assertEquals(40, nextOffset(pagesLoaded = 2, pageSize = 20))
    }

    @Test
    fun aDistrictThatIsStillOfferedStaysSelected() {
        val districts = listOf(district("성수동"), district("한남동"))

        assertEquals("성수동", resolveDistrict("성수동", districts))
        assertNull(resolveDistrict(null, districts))
    }

    @Test
    fun aDistrictNoLongerOfferedFallsBackToAll() {
        assertNull(resolveDistrict("삼청동", listOf(district("성수동"))))
    }

    @Test
    fun sortsTravelAsTheirWireNames() {
        assertEquals("popular", PublicRouteSort.POPULAR.wire)
        assertEquals("newest", PublicRouteSort.NEWEST.wire)
        assertEquals(PublicRouteSort.POPULAR, PublicRouteBrowseQuery().sort)
        assertNull(PublicRouteBrowseQuery().districtKo)
    }

    @Test
    fun aDistrictIsNamedInTheReadersLanguage() {
        val seongsu = PublicRouteDistrict(nameKo = "성수동", nameEn = "Seongsu-dong", routeCount = 2)
        val unnamed = PublicRouteDistrict(nameKo = "서촌", nameEn = " ", routeCount = 1)

        assertEquals("성수동", seongsu.localizedName(AppLanguage.KO))
        assertEquals("Seongsu-dong", seongsu.localizedName(AppLanguage.EN))
        assertEquals("서촌", unnamed.localizedName(AppLanguage.EN), "a blank English name falls back to Korean")
    }

    private fun district(nameKo: String) = PublicRouteDistrict(nameKo = nameKo, nameEn = nameKo, routeCount = 1)

    private fun route(id: String) =
        BrowsedPublicRoute(
            summary =
                PublicRouteSummary(
                    id = id,
                    name = id,
                    stopCount = 2,
                    firstDistrictKo = "한남동",
                    firstDistrictEn = "Hannam-dong",
                    lastDistrictKo = "이태원동",
                    lastDistrictEn = "Itaewon-dong",
                    authorDisplayName = null,
                    isEditor = false,
                    copyCount30d = 0,
                    firstSharedDay = LocalDate(2026, 10, 14),
                    approvedAt = Instant.parse("2026-10-12T00:00:00Z"),
                ),
            coverUrls = emptyList(),
        )
}
