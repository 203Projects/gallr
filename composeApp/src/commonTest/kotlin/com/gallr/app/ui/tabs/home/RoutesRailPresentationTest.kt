package com.gallr.app.ui.tabs.home

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Spec 091 (FR-012): the 지금 주목받는 동선 rail reads the same in both languages. */
class RoutesRailPresentationTest {
    @Test
    fun theRailHasATitleAndSubtitleInBothLanguages() {
        assertEquals("지금 주목받는 동선", homeCopy(AppLanguage.KO, null).routesTitle)
        assertEquals("최근 30일에 가장 많이 담긴 동선", homeCopy(AppLanguage.KO, null).routesSubtitle)
        assertEquals("ROUTES GETTING ATTENTION", homeCopy(AppLanguage.EN, null).routesTitle)
        assertEquals("MOST COPIED IN THE LAST 30 DAYS", homeCopy(AppLanguage.EN, null).routesSubtitle)
    }

    @Test
    fun stopCountsAndDistrictsRead() {
        assertEquals("3곳", routeStopCount(3, AppLanguage.KO))
        assertEquals("1 stop", routeStopCount(1, AppLanguage.EN))
        assertEquals("3 stops", routeStopCount(3, AppLanguage.EN))
        assertEquals("종로구 → 용산구", routeDistricts(route(), AppLanguage.KO))
        assertEquals("Jongno-gu → Yongsan-gu", routeDistricts(route(), AppLanguage.EN))
        assertEquals("종로구", routeDistricts(route(lastKo = "종로구", lastEn = "Jongno-gu"), AppLanguage.KO))
    }

    @Test
    fun theAuthorLineNamesTheAuthorOrSaysSo() {
        assertEquals("hanshin", routeAuthorLine(route(), AppLanguage.KO))
        assertEquals("에디터 · hanshin", routeAuthorLine(route(editor = true), AppLanguage.KO))
        assertEquals("EDITOR · hanshin", routeAuthorLine(route(editor = true), AppLanguage.EN))
        assertEquals("이름 없는 작성자", routeAuthorLine(route(author = null), AppLanguage.KO))
        assertEquals("Unnamed author", routeAuthorLine(route(author = " "), AppLanguage.EN))
    }

    @Test
    fun theCardDescriptionCarriesSectionPositionAndFacts() {
        assertEquals(
            "지금 주목받는 동선, 2 / 3, 북촌 산책, hanshin, 복사 4회, 3곳, 종로구 → 용산구",
            routeCardDescription(route(), index = 1, count = 3, title = "지금 주목받는 동선", language = AppLanguage.KO),
        )
    }

    private fun route(
        author: String? = "hanshin",
        editor: Boolean = false,
        lastKo: String = "용산구",
        lastEn: String = "Yongsan-gu",
    ) = PublicRouteSummary(
        id = "r1",
        name = "북촌 산책",
        stopCount = 3,
        firstDistrictKo = "종로구",
        firstDistrictEn = "Jongno-gu",
        lastDistrictKo = lastKo,
        lastDistrictEn = lastEn,
        authorDisplayName = author,
        isEditor = editor,
        copyCount30d = 4,
        firstSharedDay = LocalDate(2026, 10, 12),
        approvedAt = Instant.parse("2026-10-10T00:00:00Z"),
    )
}
