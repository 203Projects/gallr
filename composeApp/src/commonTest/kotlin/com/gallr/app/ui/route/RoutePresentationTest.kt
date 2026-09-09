package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.map.EstimatedRouteLeg
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RouteLegQuality
import com.gallr.shared.map.RouteWarning
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutePresentationTest {
    @Test
    fun `route modes are bilingual and explicit`() {
        assertEquals("NEIGHBORHOOD", RouteCurationMode.NEIGHBORHOOD.localizedLabel(AppLanguage.EN))
        assertEquals("취향 맞춤", RouteCurationMode.FOR_YOU.localizedLabel(AppLanguage.KO))
        assertEquals("CLOSING SOON", RouteCurationMode.CLOSING_SOON.localizedLabel(AppLanguage.EN))
        assertEquals("저장한 전시", RouteCurationMode.SAVED.localizedLabel(AppLanguage.KO))
    }

    @Test
    fun `estimated distances use rounded metres then one decimal kilometer`() {
        assertEquals("~850 M", estimatedDistanceLabel(846, AppLanguage.EN))
        assertEquals("약 850 M", estimatedDistanceLabel(846, AppLanguage.KO))
        assertEquals("~1.0 KM", estimatedDistanceLabel(1_000, AppLanguage.EN))
        assertEquals("~3.5 KM", estimatedDistanceLabel(3_451, AppLanguage.EN))
    }

    @Test
    fun `estimated durations remain compact and bilingual`() {
        assertEquals("~46 MIN", estimatedDurationLabel(46, AppLanguage.EN))
        assertEquals("약 46분", estimatedDurationLabel(46, AppLanguage.KO))
        assertEquals("~3 HR", estimatedDurationLabel(180, AppLanguage.EN))
        assertEquals("약 3시간 5분", estimatedDurationLabel(185, AppLanguage.KO))
    }

    @Test
    fun `summary distinguishes travel from total visit time`() {
        val summary = routeSummaryPresentation(route(), AppLanguage.EN)

        assertEquals("ESTIMATED DISTANCE · ~1.5 KM", summary.distance)
        assertEquals("ESTIMATED TRAVEL · ~22 MIN", summary.travelTime)
        assertEquals("TOTAL WITH VISITS · ~1 HR 52 MIN", summary.totalTime)
    }

    @Test
    fun `leg opening time and warnings never claim verified directions or hours`() {
        val leg = route().legs.first()

        assertEquals("FROM START · ~700 M · ~10 MIN", routeLegLabel(0, leg, AppLanguage.EN))
        assertEquals("HOURS · 11:00–18:00", routeHoursLabel(" 11:00–18:00 ", AppLanguage.EN))
        assertEquals("운영 시간 미확인", routeHoursLabel(" ", AppLanguage.KO))
        assertEquals(
            "ESTIMATED DISTANCE — NOT TURN-BY-TURN DIRECTIONS",
            RouteWarning.APPROXIMATE_DISTANCE.localizedLabel(AppLanguage.EN),
        )
        assertEquals("방문 전 운영 시간을 확인하세요", RouteWarning.HOURS_UNVERIFIED.localizedLabel(AppLanguage.KO))
    }

    @Test
    fun `numbered stop semantics include position estimates and raw time`() {
        val route = route()
        val label =
            routeStopSemanticsLabel(
                stopIndex = 0,
                stopCount = route.stops.size,
                exhibition = route.stops.first(),
                leg = route.legs.first(),
                language = AppLanguage.EN,
            )

        assertEquals(
            "Stop 1 of 2. Exhibition one. Gallery one. " +
                "FROM START · ~700 M · ~10 MIN. HOURS · 11:00–18:00.",
            label,
        )
        assertEquals("Open stop 2 of 2 in Maps", routeStopMapContentDescription(1, 2, AppLanguage.EN))
    }

    @Test
    fun `personalized route stop semantics include visible why this evidence`() {
        val route = route()
        val whyThis = "WHY THIS · SAME ARTIST AS YOUR SAVE: KIMSOOJA"

        assertEquals(
            "Stop 1 of 2. Exhibition one. Gallery one. " +
                "FROM START · ~700 M · ~10 MIN. HOURS · 11:00–18:00. $whyThis.",
            routeStopSemanticsLabel(
                stopIndex = 0,
                stopCount = route.stops.size,
                exhibition = route.stops.first(),
                leg = route.legs.first(),
                language = AppLanguage.EN,
                whyThisLabel = whyThis,
            ),
        )
    }

    @Test
    fun `insufficient and map failure copy is actionable in both languages`() {
        assertEquals(
            "Only 1 exhibition fits this 3-stop route. Reduce the stops or choose another mode.",
            insufficientRouteMessage(3, 1, AppLanguage.EN),
        )
        assertEquals(
            "지도를 열지 못했습니다. 다시 시도해 주세요.",
            routeMapOpenErrorLabel(AppLanguage.KO),
        )
    }

    private fun route(): ExhibitionRouteEstimate {
        val first = exhibition("one", hours = "11:00–18:00")
        val second = exhibition("two", hours = null)
        return ExhibitionRouteEstimate(
            mode = RouteCurationMode.NEIGHBORHOOD,
            stops = listOf(first, second),
            legs =
                listOf(
                    EstimatedRouteLeg(
                        fromExhibitionId = null,
                        toExhibitionId = first.id,
                        distanceMeters = 700,
                        estimatedTravelMinutes = 10,
                        geometry = emptyList(),
                        quality = RouteLegQuality.APPROXIMATE,
                    ),
                    EstimatedRouteLeg(
                        fromExhibitionId = first.id,
                        toExhibitionId = second.id,
                        distanceMeters = 800,
                        estimatedTravelMinutes = 12,
                        geometry = emptyList(),
                        quality = RouteLegQuality.APPROXIMATE,
                    ),
                ),
            totalDistanceMeters = 1_500,
            estimatedTravelMinutes = 22,
            estimatedVisitMinutes = 90,
            warnings = setOf(RouteWarning.APPROXIMATE_DISTANCE, RouteWarning.HOURS_UNVERIFIED),
        )
    }

    private fun exhibition(
        id: String,
        hours: String?,
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Exhibition $id",
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 9, 1),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = hours,
    )
}
