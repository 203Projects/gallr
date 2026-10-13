package com.gallr.app.ui.route

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteMapFeaturesTest {
    private val origin = GeoPoint(latitude = 37.5796, longitude = 126.9770)
    private val stop = GeoPoint(latitude = 37.5759, longitude = 126.9854)

    @Test
    fun `a route that never leaves its origin draws no line`() {
        val features = routeLineFeatures(listOf(origin))

        assertTrue(features.features.isEmpty())
    }

    @Test
    fun `an empty route draws no line`() {
        val features = routeLineFeatures(emptyList())

        assertTrue(features.features.isEmpty())
    }

    @Test
    fun `a route between two points draws one line through them in order`() {
        val line =
            routeLineFeatures(listOf(origin, stop))
                .features
                .single()
                .geometry

        assertEquals(
            listOf(origin.longitude to origin.latitude, stop.longitude to stop.latitude),
            line.coordinates.map { position -> position.longitude to position.latitude },
        )
    }
}
