package com.gallr.app

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteCurationMode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppNavigationStateTest {
    @Test
    fun `selecting a tab always returns to the tab destination`() {
        val state = AppNavigationState()
        state.showEvent("event-one")

        state.selectTab(2)

        assertEquals(2, state.selectedTab)
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `detail destinations retain their typed identifiers`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")

        val destination = assertIs<AppDestination.EditorDetail>(state.destination)
        assertEquals("editor-one", destination.editorId)
    }

    @Test
    fun `settings is a typed destination that returns to tabs`() {
        val state = AppNavigationState()

        state.showSettings()
        assertEquals(AppDestination.Settings, state.destination)

        state.showTabs()
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `archive activation requests add visits in My Gallr`() {
        val state = AppNavigationState()

        state.showAddPastVisits()

        assertEquals(3, state.selectedTab)
        assertEquals(1, state.addPastVisitsRequest)
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `paid entry suppression follows exhibition and gallery detail navigation`() {
        val state = AppNavigationState()
        val exhibition = exhibition()

        state.showExhibition(exhibition, analyticsSuppressed = true)
        assertTrue(assertIs<AppDestination.ExhibitionDetail>(state.destination).analyticsSuppressed)

        state.showGallery(exhibition, analyticsSuppressed = true)
        assertTrue(assertIs<AppDestination.GalleryDetail>(state.destination).analyticsSuppressed)
        state.returnFromGallery()
        assertTrue(assertIs<AppDestination.ExhibitionDetail>(state.destination).analyticsSuppressed)
    }

    @Test
    fun `recommendation detail returns to the recommendation surface`() {
        val state = AppNavigationState()
        state.showRecommendations()
        state.showExhibition(
            exhibition = exhibition(),
            returnTo = AppDestination.Recommendations,
        )

        state.returnFromExhibition()

        assertEquals(AppDestination.Recommendations, state.destination)
        assertEquals(0, state.selectedTab)
    }

    @Test
    fun `route detail returns to the same map-centered route destination`() {
        val state = AppNavigationState()
        val origin = GeoPoint(37.5665, 126.9780)
        state.showRoute(origin, RouteCurationMode.FOR_YOU)
        val routeDestination = assertIs<AppDestination.RoutePlanner>(state.destination)
        state.showExhibition(exhibition(), returnTo = routeDestination)

        state.returnFromExhibition()

        assertEquals(routeDestination, state.destination)
        assertEquals(2, state.selectedTab)
    }

    @Test
    fun `each explicit route entry receives a new local request identity`() {
        val state = AppNavigationState()
        val origin = GeoPoint(37.5665, 126.9780)

        state.showRoute(origin)
        val first = assertIs<AppDestination.RoutePlanner>(state.destination)
        state.showRoute(origin)
        val second = assertIs<AppDestination.RoutePlanner>(state.destination)

        assertTrue(first.requestId != second.requestId)
    }

    private fun exhibition() =
        Exhibition(
            id = "exhibition-one",
            nameKo = "전시",
            nameEn = "Exhibition",
            venueNameKo = "장소",
            venueNameEn = "Venue",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 8, 1),
            closingDate = LocalDate(2026, 8, 31),
            isFeatured = false,
            latitude = 37.5,
            longitude = 127.0,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
