package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 089 FR-002: route names and stop lists that may be saved. */
class PersonalRouteTest {
    @Test
    fun nameIsTrimmedAndMustBeOneToSixtyCharacters() {
        assertEquals(RouteSaveProblem.NAME_EMPTY, route(name = "   ").saveProblem())
        assertNull(route(name = "  종로 미술관 산책  ").saveProblem())
        assertEquals("종로 미술관 산책", route(name = "  종로 미술관 산책  ").trimmedName)
        assertNull(route(name = "가".repeat(60)).saveProblem())
        assertEquals(RouteSaveProblem.NAME_TOO_LONG, route(name = "가".repeat(61)).saveProblem())
    }

    @Test
    fun nameLengthCountsCharactersNotUtf16Units() {
        val sixtyEmoji = "🎨".repeat(60)

        assertNull(route(name = sixtyEmoji).saveProblem())
        assertEquals(RouteSaveProblem.NAME_TOO_LONG, route(name = sixtyEmoji + "🎨").saveProblem())
    }

    @Test
    fun routeNameLengthCountsCharactersOfTheTrimmedNameLikeTheDatabase() {
        assertEquals(0, routeNameLength(""))
        assertEquals(0, routeNameLength("   "))
        assertEquals(9, routeNameLength("  종로 미술관 산책  "))
        assertEquals(3, routeNameLength("🎨🎨🎨"))
        assertEquals(MAX_ROUTE_NAME_LENGTH, routeNameLength(" " + "가".repeat(MAX_ROUTE_NAME_LENGTH) + " "))
        assertEquals(MAX_ROUTE_NAME_LENGTH + 1, routeNameLength("🎨".repeat(MAX_ROUTE_NAME_LENGTH + 1)))
    }

    @Test
    fun saveNeedsTwoToTenDistinctStops() {
        assertEquals(RouteSaveProblem.TOO_FEW_STOPS, route(stopCount = 0).saveProblem())
        assertEquals(RouteSaveProblem.TOO_FEW_STOPS, route(stopCount = 1).saveProblem())
        assertNull(route(stopCount = 2).saveProblem())
        assertNull(route(stopCount = 10).saveProblem())
        assertEquals(RouteSaveProblem.TOO_MANY_STOPS, route(stopCount = 11).saveProblem())
        val duplicated = route(stopCount = 3).let { it.copy(stops = it.stops + it.stops.first()) }
        assertEquals(RouteSaveProblem.DUPLICATE_STOP, duplicated.saveProblem())
    }

    @Test
    fun stopsKeepTheGivenOrder() {
        val stops = listOf(stop("c"), stop("a"), stop("b"))

        assertEquals(listOf("c", "a", "b"), route(stops = stops).stops.map(PersonalRouteStop::exhibitionId))
    }

    @Test
    fun anExhibitionWithCoordinatesBecomesAStopWithItsCatalogueDetails() {
        val stop = exhibition(latitude = 37.58, longitude = 126.98).toRouteStop()

        requireNotNull(stop)
        assertEquals("ex-1", stop.exhibitionId)
        assertEquals("전시", stop.nameKo)
        assertEquals("Show", stop.nameEn)
        assertEquals("갤러리", stop.venueNameKo)
        assertEquals("Gallery", stop.venueNameEn)
        assertEquals(GeoPoint(37.58, 126.98), stop.point)
        assertEquals("종로구", stop.regionKo)
        assertEquals("Jongno-gu", stop.regionEn)
        assertEquals("서울", stop.cityKo)
    }

    @Test
    fun anExhibitionWithoutCoordinatesCannotBecomeAStop() {
        assertNull(exhibition(latitude = null, longitude = 126.98).toRouteStop())
        assertNull(exhibition(latitude = 37.58, longitude = null).toRouteStop())
    }

    private fun route(
        name: String = "동선",
        stopCount: Int = 3,
        stops: List<PersonalRouteStop> = (1..stopCount).map { stop("ex-$it") },
    ) = PersonalRoute(id = "route-1", name = name, stops = stops)

    private fun stop(id: String) =
        PersonalRouteStop(
            exhibitionId = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            point = GeoPoint(37.58, 126.98),
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            cityKo = "서울",
        )

    private fun exhibition(
        latitude: Double?,
        longitude: Double?,
    ) = Exhibition(
        id = "ex-1",
        nameKo = "전시",
        nameEn = "Show",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 10, 1),
        closingDate = LocalDate(2026, 11, 30),
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "서울 종로구 삼청로 30",
        addressEn = "30 Samcheong-ro",
        coverImageUrl = null,
    )
}
