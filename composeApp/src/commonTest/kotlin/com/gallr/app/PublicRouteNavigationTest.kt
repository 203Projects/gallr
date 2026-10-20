package com.gallr.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 089 US9 (DD3, DD18) under spec 094: the public route preview, 모두 보기 and spec 092's 동선 둘러보기 on per-tab trails. */
class PublicRouteNavigationTest {
    @Test
    fun aPreviewOpenedFromMyRoutesGoesBackToMy() {
        val navigation = AppNavigationState()
        navigation.selectTab(AppTab.MY)

        navigation.showPublicRoute("p1")
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)

        navigation.back()
        assertEquals(AppTab.MY, navigation.selectedTab)
        assertNull(navigation.destination)
    }

    @Test
    fun signingInFromAPreviewReturnsToThatPreview() {
        val navigation = AppNavigationState()
        navigation.selectTab(AppTab.MY)
        navigation.showPublicRoute("p1")

        navigation.showAccount()
        assertEquals(AppDestination.Account, navigation.destination)

        navigation.back()
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)
    }

    @Test
    fun seeAllOpensTheMyTabRoutesSectionAtItsTop() {
        val navigation = AppNavigationState()
        navigation.selectTab(AppTab.MY)
        navigation.showPublicRoute("p1")
        navigation.selectTab(AppTab.HOME)

        navigation.showMyRoutes()

        assertEquals(AppTab.MY, navigation.selectedTab)
        assertNull(navigation.destination)
        assertEquals(MyTabRequest.MY_ROUTES, navigation.myTabRequest)
    }

    @Test
    fun theBrowseScreenOpensFromATabAndReturnsToIt() {
        val navigation = AppNavigationState()
        navigation.selectTab(AppTab.MY)

        navigation.showPublicRouteBrowse()
        assertEquals(AppDestination.PublicRouteBrowse, navigation.destination)

        navigation.back()
        assertNull(navigation.destination)
        assertEquals(AppTab.MY, navigation.selectedTab, "back returns to the tab it was opened from")
    }

    @Test
    fun aPreviewOpenedFromTheBrowseScreenReturnsToIt() {
        val navigation = AppNavigationState()
        navigation.showPublicRouteBrowse()

        navigation.showPublicRoute("p1")
        assertEquals(AppDestination.PublicRoutePreview("p1"), navigation.destination)

        navigation.back()
        assertEquals(AppDestination.PublicRouteBrowse, navigation.destination)

        navigation.back()
        assertNull(navigation.destination)
    }
}
