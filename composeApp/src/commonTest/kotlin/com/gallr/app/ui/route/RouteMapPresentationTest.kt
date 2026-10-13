package com.gallr.app.ui.route

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteMapPresentationTest {
    private val origin = GeoPoint(37.5665, 126.9780)

    @Test
    fun `viewport centres on the route and zooms to fit every point with padding`() {
        val stops = listOf(GeoPoint(37.5700, 126.9850), GeoPoint(37.5620, 126.9900))

        val viewport = routeMapViewport(listOf(origin) + stops, widthDp = 411f, heightDp = 220f, paddingDp = 24f)

        assertEquals((37.5620 + 37.5700) / 2, viewport.latitude, 1e-9)
        assertEquals((126.9780 + 126.9900) / 2, viewport.longitude, 1e-9)
        assertTrue(viewport.zoom in ROUTE_MAP_MIN_ZOOM..ROUTE_MAP_MAX_ZOOM, "zoom ${viewport.zoom}")
        // About a kilometre across in a 411dp panel reads as a neighbourhood, not a street corner.
        assertTrue(viewport.zoom in 13.5..15.0, "zoom ${viewport.zoom}")

        val wider =
            routeMapViewport(
                listOf(origin, GeoPoint(37.60, 127.05)),
                widthDp = 411f,
                heightDp = 220f,
                paddingDp = 24f,
            )
        assertTrue(wider.zoom < viewport.zoom, "wider ${wider.zoom} vs ${viewport.zoom}")
    }

    @Test
    fun `a single point or a degenerate panel still yields a usable viewport`() {
        val single = routeMapViewport(listOf(origin), widthDp = 411f, heightDp = 220f, paddingDp = 24f)
        assertEquals(origin.latitude, single.latitude, 1e-9)
        assertEquals(ROUTE_MAP_MAX_ZOOM, single.zoom)

        val unmeasured =
            routeMapViewport(listOf(origin, GeoPoint(37.57, 126.99)), widthDp = 0f, heightDp = 0f, paddingDp = 24f)
        assertEquals(ROUTE_MAP_MIN_ZOOM, unmeasured.zoom)
    }

    @Test
    fun `route line follows leg geometry when present and falls back to straight segments`() {
        val stops = listOf(GeoPoint(37.5700, 126.9850), GeoPoint(37.5620, 126.9900))
        val detour = GeoPoint(37.5690, 126.9800)

        val straight = routeLinePoints(origin, stops, legGeometries = listOf(emptyList(), emptyList()))
        assertEquals(listOf(origin) + stops, straight)

        val routed =
            routeLinePoints(origin, stops, legGeometries = listOf(listOf(origin, detour, stops[0]), emptyList()))
        assertEquals(listOf(origin, detour, stops[0], stops[1]), routed)
    }
}
