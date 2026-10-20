package com.gallr.app.ui.route.publicroutes

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PublicRouteDistrict
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 092: the 동선 둘러보기 screen's wording in Korean and English. */
class PublicRouteBrowsePresentationTest {
    private val today = LocalDate(2026, 10, 14)

    @Test
    fun theScreenAndItsEntryPointsAreNamed() {
        assertEquals("동선 둘러보기", publicRouteBrowseTitle(AppLanguage.KO))
        assertEquals("BROWSE ROUTES", publicRouteBrowseTitle(AppLanguage.EN))
        assertEquals("전체 보기", publicRoutesSeeAllLabel(AppLanguage.KO))
        assertEquals("SEE ALL", publicRoutesSeeAllLabel(AppLanguage.EN))
        assertEquals("아직 공유된 동선이 없어요", publicRoutesNoneYetMessage(AppLanguage.KO))
        assertEquals("NO SHARED ROUTES YET", publicRoutesNoneYetMessage(AppLanguage.EN))
    }

    @Test
    fun aRowIsReadAsItsNameThenItsLine() {
        assertEquals(
            "한남 산책, 한남동–이태원동 · 2곳 · 하나 · 복사 3 · 10월 20일부터",
            publicRouteBrowseRowDescription(row(firstSharedDay = LocalDate(2026, 10, 20)), today, AppLanguage.KO),
        )
        assertEquals(
            "한남 산책, Hannam-dong–Itaewon-dong · 2 STOPS · 하나 · COPIED 3",
            publicRouteBrowseRowDescription(row(), today, AppLanguage.EN),
        )
    }

    @Test
    fun aRowShowsAtMostThreeCovers() {
        val covers = listOf("a", "b", "c", "d")

        assertEquals(listOf("a", "b", "c"), browseRowCovers(BrowsedPublicRoute(row(), covers)))
        assertEquals(emptyList<String>(), browseRowCovers(BrowsedPublicRoute(row(), emptyList())))
    }

    @Test
    fun theSortsAndTheAllChipAreNamed() {
        assertEquals("인기", publicRouteSortLabel(PublicRouteSort.POPULAR, AppLanguage.KO))
        assertEquals("최신", publicRouteSortLabel(PublicRouteSort.NEWEST, AppLanguage.KO))
        assertEquals("POPULAR", publicRouteSortLabel(PublicRouteSort.POPULAR, AppLanguage.EN))
        assertEquals("NEWEST", publicRouteSortLabel(PublicRouteSort.NEWEST, AppLanguage.EN))
        assertEquals("전체", publicRouteAllDistrictsLabel(AppLanguage.KO))
        assertEquals("ALL", publicRouteAllDistrictsLabel(AppLanguage.EN))
    }

    @Test
    fun anEmptyDistrictTakesTheRightParticle() {
        val seongsu = PublicRouteDistrict(nameKo = "성수동", nameEn = "Seongsu-dong", routeCount = 1)
        val euljiro = PublicRouteDistrict(nameKo = "을지로", nameEn = "Euljiro", routeCount = 1)

        assertEquals("성수동을 지나는 동선이 없어요", publicRouteDistrictEmptyMessage(seongsu, AppLanguage.KO))
        assertEquals("을지로를 지나는 동선이 없어요", publicRouteDistrictEmptyMessage(euljiro, AppLanguage.KO))
        assertEquals("NO ROUTES THROUGH SEONGSU-DONG", publicRouteDistrictEmptyMessage(seongsu, AppLanguage.EN))
        assertEquals("필터 지우기", publicRouteClearFilterLabel(AppLanguage.KO))
        assertEquals("CLEAR FILTER", publicRouteClearFilterLabel(AppLanguage.EN))
    }

    @Test
    fun aFailedLaterPageSaysSo() {
        assertEquals("더 불러오지 못했어요", publicRouteLoadMoreFailedMessage(AppLanguage.KO))
        assertEquals("COULDN’T LOAD MORE ROUTES", publicRouteLoadMoreFailedMessage(AppLanguage.EN))
    }

    private fun row(firstSharedDay: LocalDate = today) =
        PublicRouteSummary(
            id = "p1",
            name = "한남 산책",
            stopCount = 2,
            firstDistrictKo = "한남동",
            firstDistrictEn = "Hannam-dong",
            lastDistrictKo = "이태원동",
            lastDistrictEn = "Itaewon-dong",
            authorDisplayName = "하나",
            isEditor = false,
            copyCount30d = 3,
            firstSharedDay = firstSharedDay,
            approvedAt = Instant.parse("2026-10-12T00:00:00Z"),
        )
}
