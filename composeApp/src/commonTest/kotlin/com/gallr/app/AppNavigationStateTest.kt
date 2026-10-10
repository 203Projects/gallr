package com.gallr.app

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteCurationMode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun `the route composer returns to where it was opened from`() {
        val state = AppNavigationState()
        state.showRoute(GeoPoint(37.57, 126.98))
        val planner = state.destination

        state.showRouteComposer()
        assertEquals(AppDestination.RouteComposer, state.destination)

        state.returnFromRouteComposer()
        assertEquals(planner, state.destination)
    }

    @Test
    fun `an exhibition opened from the composer returns to the composer`() {
        val state = AppNavigationState()
        state.showRouteComposer()

        state.showExhibition(exhibition(), returnTo = state.destination)
        state.returnFromExhibition()

        assertEquals(AppDestination.RouteComposer, state.destination)
    }

    @Test
    fun `a sign-in request opens the account screen in My Gallr`() {
        val state = AppNavigationState()
        state.showRouteComposer()

        state.showSignIn()

        assertEquals(3, state.selectedTab)
        assertEquals(MyTabRequest.SIGN_IN, state.myTabRequest)
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `a My Gallr request is handled once, not again when the tab is shown later`() {
        val state = AppNavigationState()
        state.showSignIn()

        state.onMyTabRequestHandled()
        // The user opens a route in the composer and comes back to My Gallr.
        state.showRouteComposer()
        state.returnFromRouteComposer()

        assertNull(state.myTabRequest)
    }

    @Test
    fun `leaving a sign-in the composer asked for returns to the composer once`() {
        val state = AppNavigationState()
        state.showRoute(GeoPoint(37.57, 126.98))
        state.showRouteComposer()
        state.showSignIn()

        assertTrue(state.returnFromSignIn())
        assertEquals(AppDestination.RouteComposer, state.destination)
        assertFalse(state.returnFromSignIn(), "the request is used up")
    }

    @Test
    fun `leaving a sign-in opened from the tabs stays in My Gallr`() {
        val state = AppNavigationState()
        state.showSignIn()

        assertFalse(state.returnFromSignIn())
        assertEquals(AppDestination.Tabs, state.destination)
        assertEquals(3, state.selectedTab)
    }

    @Test
    fun `choosing another tab abandons the return to the composer`() {
        val state = AppNavigationState()
        state.showRouteComposer()
        state.showSignIn()

        state.selectTab(1)

        assertFalse(state.returnFromSignIn())
        assertEquals(AppDestination.Tabs, state.destination)
    }

    @Test
    fun `a share that resumes into the composer uses up the return`() {
        val state = AppNavigationState()
        state.showRouteComposer()
        state.showSignIn()

        state.showRouteComposer()
        state.returnFromRouteComposer()

        assertEquals(3, state.selectedTab)
        assertFalse(state.returnFromSignIn())
    }

    @Test
    fun `archive activation requests add visits in My Gallr`() {
        val state = AppNavigationState()

        state.showAddPastVisits()

        assertEquals(3, state.selectedTab)
        assertEquals(MyTabRequest.ADD_PAST_VISITS, state.myTabRequest)
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
