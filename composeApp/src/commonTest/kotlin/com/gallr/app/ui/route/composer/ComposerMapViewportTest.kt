package com.gallr.app.ui.route.composer

import com.gallr.app.ui.route.ROUTE_MAP_MIN_ZOOM
import com.gallr.app.ui.route.routeMapViewport
import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Authors may pick stops across the metropolitan area, so the composer's panel zooms out further (spec 089). */
class ComposerMapViewportTest {
    @Test
    fun aMetropolitanRouteFitsTheComposerPanel() {
        val hannamToAnsan =
            listOf(
                GeoPoint(37.5345, 127.0024),
                GeoPoint(37.3219, 126.8309),
                GeoPoint(37.5826, 126.9837),
            )

        val viewport = routeMapViewport(hannamToAnsan, 360f, 220f, 24f, minZoom = COMPOSER_MAP_MIN_ZOOM)

        assertTrue(viewport.zoom < ROUTE_MAP_MIN_ZOOM, "zoom ${viewport.zoom} cannot show the whole route")
        assertTrue(viewport.zoom >= COMPOSER_MAP_MIN_ZOOM)
    }

    @Test
    fun thePlannerKeepsItsFloor() {
        val far = listOf(GeoPoint(37.5345, 127.0024), GeoPoint(37.3219, 126.8309))

        assertEquals(ROUTE_MAP_MIN_ZOOM, routeMapViewport(far, 360f, 220f, 24f).zoom)
    }
}
