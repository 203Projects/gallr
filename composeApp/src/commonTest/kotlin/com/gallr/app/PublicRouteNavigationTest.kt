package com.gallr.app

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.RouteCurationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Spec 089 US9 (DD3, DD18): the public route preview and 모두 보기 from the route sheet; spec 092 동선 둘러보기. */
class PublicRouteNavigationTest {
    @Test
    fun aPreviewOpensFromTheRouteSheetAndReturnsToIt() {
        val navigation = AppNavigationState()
        navigation.showRoute(GeoPoint(37.57, 126.98), RouteCurationMode.NEIGHBORHOOD)
        val sheet = navigation.destination

        navigation.showPublicRoute("p1")
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)

        navigation.returnFromPublicRoute()
        assertEquals(sheet, navigation.destination)
    }

    @Test
    fun signingInFromAPreviewReturnsToThatPreview() {
        val navigation = AppNavigationState()
        navigation.showRoute(GeoPoint(37.57, 126.98), RouteCurationMode.NEIGHBORHOOD)
        navigation.showPublicRoute("p1")

        navigation.showSignIn()
        assertIs<AppDestination.Tabs>(navigation.destination)

        assertEquals(true, navigation.returnFromSignIn())
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)
    }

    @Test
    fun seeAllOpensTheMyTabRoutesSection() {
        val navigation = AppNavigationState()
        navigation.showRoute(GeoPoint(37.57, 126.98), RouteCurationMode.NEIGHBORHOOD)

        navigation.showMyRoutes()

        assertEquals(3, navigation.selectedTab)
        assertIs<AppDestination.Tabs>(navigation.destination)
        assertEquals(MyTabRequest.MY_ROUTES, navigation.myTabRequest)
    }

    @Test
    fun theBrowseScreenOpensFromATabAndReturnsToIt() {
        val navigation = AppNavigationState()
        navigation.selectTab(3)

        navigation.showPublicRouteBrowse()
        assertEquals(AppDestination.PublicRouteBrowse, navigation.destination)

        navigation.returnFromPublicRouteBrowse()
        assertIs<AppDestination.Tabs>(navigation.destination)
        assertEquals(3, navigation.selectedTab, "back returns to the tab it was opened from")
    }

    @Test
    fun aPreviewOpenedFromTheBrowseScreenReturnsToIt() {
        val navigation = AppNavigationState()
        navigation.showPublicRouteBrowse()

        navigation.showPublicRoute("p1")
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)

        navigation.returnFromPublicRoute()
        assertEquals(AppDestination.PublicRouteBrowse, navigation.destination)

        navigation.returnFromPublicRouteBrowse()
        assertIs<AppDestination.Tabs>(navigation.destination)
    }
}
