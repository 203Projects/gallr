package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.gallr.app.share.RouteShareCardConfig as Config

/** Spec 089 DR-D11: the route card's geometry and line fitting. */
class RouteShareCardLayoutTest {
    /** Ten pixels per character, so widths are easy to reason about. */
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun theCardIsAStoryImage() {
        assertEquals(1080, Config.CARD_WIDTH_PX)
        assertEquals(1920, Config.CARD_HEIGHT_PX)
    }

    @Test
    fun sectionsStackWithoutOverlapAboveTheQr() {
        assertTrue(Config.BRAND_TOP_PX >= Config.SAFE_TOP_PX)
        assertTrue(Config.EYEBROW_TOP_PX > Config.BRAND_TOP_PX + Config.BRAND_HEIGHT_PX)
        assertTrue(Config.NAME_TOP_PX > Config.EYEBROW_TOP_PX)
        assertTrue(Config.DRAWING_TOP_PX >= Config.NAME_TOP_PX + Config.NAME_LINE_HEIGHT_PX * Config.NAME_MAX_LINES)
        assertTrue(Config.STOPS_TOP_PX > Config.DRAWING_TOP_PX + Config.DRAWING_SIZE_PX)
        val stopsBottom = Config.STOPS_TOP_PX + Config.STOP_LINE_HEIGHT_PX * Config.MAX_STOP_LINES
        assertTrue(Config.SUMMARY_TOP_PX >= stopsBottom)
        assertTrue(Config.QR_TOP_PX > Config.SUMMARY_TOP_PX + Config.SUMMARY_HEIGHT_PX)
        assertEquals(Config.CARD_HEIGHT_PX - Config.SAFE_BOTTOM_PX, Config.QR_TOP_PX + Config.QR_MAX_PX)
    }

    @Test
    fun aLongNameTakesTwoLinesWithAnEllipsis() {
        val name = "아주 ".repeat(80).trim()
        val layout = routeShareCardLayout(content(name, 2), measure, measure)

        assertEquals(2, layout.nameLines.size)
        assertTrue(layout.nameLines.last().endsWith("…"))
        layout.nameLines.forEach { assertTrue(measure(it) <= Config.TEXT_WIDTH_PX) }
    }

    @Test
    fun twoAndTenStopsTakeOneLineEach() {
        listOf(2, 10).forEach { count ->
            val layout = routeShareCardLayout(content("동선", count, longStopNames = true), measure, measure)

            assertEquals(count, layout.stopLines.size)
            layout.stopLines.forEach { line ->
                assertTrue(measure(line) <= Config.TEXT_WIDTH_PX)
                assertTrue(line.endsWith("…"))
            }
        }
    }

    @Test
    fun shortLinesAreKeptWhole() {
        val layout = routeShareCardLayout(content("동선", 2), measure, measure)

        assertEquals(listOf("동선"), layout.nameLines)
        assertEquals(listOf("01 전시 0", "02 전시 1"), layout.stopLines)
    }

    private fun content(
        name: String,
        stopCount: Int,
        longStopNames: Boolean = false,
    ) = RouteShareCardContent.from(
        route =
            PersonalRoute(
                id = "route-1",
                name = name,
                stops =
                    (0 until stopCount).map { index ->
                        val title = if (longStopNames) "긴 제목 ".repeat(40) + index else "전시 $index"
                        PersonalRouteStop(
                            exhibitionId = "e$index",
                            nameKo = title,
                            nameEn = title,
                            venueNameKo = "공간",
                            venueNameEn = "Venue",
                            point = GeoPoint(37.57 + index / 100.0, 126.98),
                            regionKo = "종로구",
                            regionEn = "Jongno-gu",
                            cityKo = "서울",
                        )
                    },
            ),
        link = "https://gallrmap.com/route/route-1?s=share&v=1",
        language = AppLanguage.KO,
    )
}
