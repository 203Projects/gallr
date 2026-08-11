package com.gallr.app.ui.tabs.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapInitialViewportTest {
    @Test
    fun current_location_centers_the_initial_city_scale_viewport() {
        val viewport = initialMapViewport(Coordinates(latitude = 37.5219, longitude = 127.0411))

        assertEquals(37.5219, viewport.latitude)
        assertEquals(127.0411, viewport.longitude)
        assertEquals(12.5, viewport.zoom)
    }

    @Test
    fun unavailable_location_falls_back_to_the_seoul_overview() {
        val viewport = initialMapViewport(null)

        assertEquals(37.5665, viewport.latitude)
        assertEquals(126.9780, viewport.longitude)
        assertEquals(11.8, viewport.zoom)
    }
}
