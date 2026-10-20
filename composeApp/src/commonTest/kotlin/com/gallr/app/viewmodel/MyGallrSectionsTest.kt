package com.gallr.app.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

/** MY shows the 동선 section only while routes are released (ReleaseFeatures.ROUTES). */
class MyGallrSectionsTest {
    @Test
    fun routesSectionFollowsTheReleaseSwitch() {
        assertEquals(listOf(MyGallrSection.VISITS, MyGallrSection.FOLLOWING), myGallrSections(routesEnabled = false))
        assertEquals(MyGallrSection.entries.toList(), myGallrSections(routesEnabled = true))
    }
}
