package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.EstimatedLeg
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Spec 089 DR-D11: the monochrome route card's words, QR target and drawing. */
class RouteShareCardContentTest {
    private val link = "https://gallrmap.com/route/route-1?s=share&v=1791432000"

    @Test
    fun cardNamesTheRouteItsStopsAndItsLength() {
        val content = RouteShareCardContent.from(route(3), link, AppLanguage.KO, sixHundredMetresPerLeg)

        assertEquals("전시 동선", content.eyebrow)
        assertEquals("삼청동 산책", content.name)
        assertEquals(listOf("01 전시 0", "02 전시 1", "03 전시 2"), content.stopLines)
        assertEquals("3곳 · 약 1.2 KM", content.summary)
        assertEquals("삼청동 산책", content.shareDescriptor)
    }

    @Test
    fun englishCardUsesEnglishNames() {
        val content = RouteShareCardContent.from(route(2), link, AppLanguage.EN, sixHundredMetresPerLeg)

        assertEquals("EXHIBITION ROUTE", content.eyebrow)
        assertEquals(listOf("01 Show 0", "02 Show 1"), content.stopLines)
        assertEquals("2 STOPS · ~600 M", content.summary)
    }

    @Test
    fun theQrOpensTheShareLink() {
        val content = RouteShareCardContent.from(route(2), link, AppLanguage.KO, sixHundredMetresPerLeg)

        assertEquals(link, content.qrTarget)
    }

    @Test
    fun theDrawingFitsTheStopsInsideTheSquareInOrder() {
        val content = RouteShareCardContent.from(route(3), link, AppLanguage.KO, sixHundredMetresPerLeg)

        assertEquals(3, content.drawing.size)
        content.drawing.forEach { point ->
            assertTrue(point.x in 0.0..1.0 && point.y in 0.0..1.0, "$point outside the square")
        }
        // Stops run north, so each later stop is drawn higher up the square.
        assertTrue(content.drawing[0].y > content.drawing[1].y && content.drawing[1].y > content.drawing[2].y)
    }

    @Test
    fun stopsAtOnePlaceAreDrawnInTheMiddle() {
        val base = route(2)
        val sameSpot = base.copy(stops = base.stops.map { stop -> stop.copy(point = GeoPoint(37.57, 126.98)) })

        val content = RouteShareCardContent.from(sameSpot, link, AppLanguage.KO, sixHundredMetresPerLeg)

        content.drawing.forEach { point -> assertEquals(DrawingPoint(0.5, 0.5), point) }
    }

    @Test
    fun bothPalettesAreMonochrome() {
        val accent = 0xFFFF5400.toInt()
        listOf(RouteShareCardPalette.LIGHT, RouteShareCardPalette.DARK).forEach { palette ->
            assertTrue(accent !in palette.colors(), "accent on the route card")
        }
        assertEquals(0xFFFFFFFF.toInt(), RouteShareCardPalette.LIGHT.paper)
        assertEquals(0xFF121212.toInt(), RouteShareCardPalette.DARK.paper)
        assertEquals(0xFFFFFFFF.toInt(), RouteShareCardPalette.DARK.qrTile)
    }

    private val sixHundredMetresPerLeg =
        RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 600, travelMinutes = 8) }

    private fun route(stopCount: Int) =
        PersonalRoute(
            id = "route-1",
            name = "삼청동 산책",
            stops =
                (0 until stopCount).map { index ->
                    PersonalRouteStop(
                        exhibitionId = "e$index",
                        nameKo = "전시 $index",
                        nameEn = "Show $index",
                        venueNameKo = "공간 $index",
                        venueNameEn = "Venue $index",
                        point = GeoPoint(37.57 + index / 100.0, 126.98 + index / 200.0),
                        regionKo = "종로구",
                        regionEn = "Jongno-gu",
                        cityKo = "서울",
                    )
                },
            isPublished = true,
            revision = Instant.parse("2026-10-08T04:00:00Z"),
        )
}
