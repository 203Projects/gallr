package com.gallr.app

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.home.HomeCollectionKind
import com.gallr.shared.map.RouteCurationMode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 094: a trail of screens per tab under an always-visible tab bar. */
class AppNavigationStateTest {
    @Test
    fun `the app starts at the top of Home`() {
        val state = AppNavigationState()

        assertEquals(AppTab.HOME, state.selectedTab)
        assertNull(state.destination)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `a screen opens on top of the selected tab and back closes it`() {
        val state = AppNavigationState()
        state.selectTab(AppTab.LIST)

        state.showEvent("event-one")

        assertEquals(AppDestination.EventDetail("event-one"), state.destination)
        assertEquals(1, state.trail(AppTab.LIST).size)
        assertTrue(state.trail(AppTab.HOME).isEmpty())
        assertTrue(state.back())
        assertNull(state.destination)
        assertFalse(state.back(), "back does nothing at a tab's top")
    }

    @Test
    fun `switching tabs keeps every trail`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")
        state.selectTab(AppTab.MAP)
        state.showSettings()

        state.selectTab(AppTab.HOME)

        assertEquals(AppDestination.EditorDetail("editor-one"), state.destination)
        state.selectTab(AppTab.MAP)
        assertEquals(AppDestination.Settings, state.destination)
    }

    @Test
    fun `tapping the selected tab returns it to its top and leaves other tabs alone`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")
        state.selectTab(AppTab.LIST)
        state.showEvent("event-one")
        state.showSettings()

        state.selectTab(AppTab.LIST)

        assertNull(state.destination)
        assertTrue(state.trail(AppTab.LIST).isEmpty())
        assertEquals(1, state.trail(AppTab.HOME).size)
    }

    @Test
    fun `tapping the selected tab at its top changes nothing`() {
        val state = AppNavigationState()
        val dropped = mutableListOf<Long>()
        state.onEntriesDropped = { dropped += it }

        state.selectTab(AppTab.HOME)

        assertEquals(AppTab.HOME, state.selectedTab)
        assertNull(state.destination)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `back walks the trail in reverse order`() {
        val state = AppNavigationState()
        val exhibition = exhibition()
        state.showExhibition(exhibition)
        state.showGallery(exhibition)

        state.back()

        assertIs<AppDestination.ExhibitionDetail>(state.destination)
        state.back()
        assertNull(state.destination)
    }

    @Test
    fun `opening the screen already on top adds nothing`() {
        val state = AppNavigationState()

        state.showEditor("editor-one")
        state.showEditor("editor-one")

        assertEquals(1, state.trail(AppTab.HOME).size)
    }

    @Test
    fun `every closed entry is reported so its saved state can go`() {
        val state = AppNavigationState()
        val dropped = mutableListOf<Long>()
        state.onEntriesDropped = { dropped += it }
        state.showEditor("editor-one")
        state.showEvent("event-one")
        val ids = state.trail(AppTab.HOME).map(NavEntry::id)

        state.back()
        state.selectTab(AppTab.HOME)

        assertEquals(listOf(ids[1], ids[0]), dropped)
    }

    @Test
    fun `entry ids are unique across tabs`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")
        state.selectTab(AppTab.MAP)
        state.showEditor("editor-one")

        val ids = AppTab.entries.flatMap { tab -> state.trail(tab).map(NavEntry::id) }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `screens that used to force Home open in the current tab`() {
        val state = AppNavigationState()
        state.selectTab(AppTab.MAP)
        val collection =
            HomeCollection(HomeCollectionKind.THEME, "identity", "정체성", "Identity", null, listOf(exhibition()))

        state.showCollection(collection)
        state.showRecommendations()
        state.showRoute(GeoPoint(37.57, 126.98))

        assertEquals(AppTab.MAP, state.selectedTab)
        assertEquals(3, state.trail(AppTab.MAP).size)
        assertTrue(state.trail(AppTab.HOME).isEmpty())
    }

    @Test
    fun `there is one composer and opening it from another tab goes to it`() {
        val state = AppNavigationState()
        state.selectTab(AppTab.MY)
        state.showRouteComposer()
        state.selectTab(AppTab.LIST)
        state.showEvent("event-one")

        state.showRouteComposer()

        assertEquals(AppTab.MY, state.selectedTab)
        assertEquals(AppDestination.RouteComposer, state.destination)
        assertEquals(1, state.trail(AppTab.LIST).size, "the other tab keeps its trail")
        assertEquals(1, state.trail(AppTab.MY).size)
    }

    @Test
    fun `an exhibition opened from the composer goes back to the composer`() {
        val state = AppNavigationState()
        state.showRouteComposer()

        state.showExhibition(exhibition())
        state.back()

        assertEquals(AppDestination.RouteComposer, state.destination)
    }

    @Test
    fun `sign-in opens over the screen that asked and back returns to it`() {
        val state = AppNavigationState()
        state.selectTab(AppTab.MY)
        state.showRouteComposer()

        state.showAccount()

        assertEquals(AppTab.MY, state.selectedTab)
        assertEquals(AppDestination.Account, state.destination)
        state.back()
        assertEquals(AppDestination.RouteComposer, state.destination)
    }

    @Test
    fun `sign-in asked for in another tab stays in that tab`() {
        val state = AppNavigationState()
        state.showPublicRoute("p1")

        state.showAccount()

        assertEquals(AppTab.HOME, state.selectedTab)
        state.back()
        assertEquals(AppDestination.PublicRoutePreview("p1"), state.destination)
    }

    @Test
    fun `a share that resumes after sign-in brings the composer back`() {
        val state = AppNavigationState()
        state.showRouteComposer()
        state.showAccount()

        state.showRouteComposer()

        assertEquals(AppDestination.RouteComposer, state.destination)
        assertEquals(1, state.trail(AppTab.HOME).size, "the account screen above it is closed")
    }

    @Test
    fun `adding past visits goes to the top of MY with the request`() {
        val state = AppNavigationState()
        state.selectTab(AppTab.MY)
        state.showSettings()
        state.selectTab(AppTab.HOME)

        state.showAddPastVisits()

        assertEquals(AppTab.MY, state.selectedTab)
        assertNull(state.destination)
        assertEquals(MyTabRequest.ADD_PAST_VISITS, state.myTabRequest)
    }

    @Test
    fun `a My Gallr request is handled once`() {
        val state = AppNavigationState()
        state.showAddPastVisits()

        state.onMyTabRequestHandled()
        state.selectTab(AppTab.HOME)
        state.selectTab(AppTab.MY)

        assertNull(state.myTabRequest)
    }

    @Test
    fun `detail destinations retain their typed identifiers`() {
        val state = AppNavigationState()
        state.showEditor("editor-one")

        val destination = assertIs<AppDestination.EditorDetail>(state.destination)
        assertEquals("editor-one", destination.editorId)
    }

    @Test
    fun `paid entry suppression follows exhibition and gallery detail navigation`() {
        val state = AppNavigationState()
        val exhibition = exhibition()

        state.showExhibition(exhibition, analyticsSuppressed = true)
        assertTrue(assertIs<AppDestination.ExhibitionDetail>(state.destination).analyticsSuppressed)

        state.showGallery(exhibition, analyticsSuppressed = true)
        assertTrue(assertIs<AppDestination.GalleryDetail>(state.destination).analyticsSuppressed)
        state.back()
        assertTrue(assertIs<AppDestination.ExhibitionDetail>(state.destination).analyticsSuppressed)
    }

    @Test
    fun `each explicit route entry receives a new local request identity`() {
        val state = AppNavigationState()
        val origin = GeoPoint(37.5665, 126.9780)

        state.showRoute(origin, RouteCurationMode.FOR_YOU)
        val first = assertIs<AppDestination.RoutePlanner>(state.destination)
        state.showRoute(origin, RouteCurationMode.FOR_YOU)
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
