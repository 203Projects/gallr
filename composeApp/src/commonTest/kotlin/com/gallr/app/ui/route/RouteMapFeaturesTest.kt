package com.gallr.app.ui.route

import com.gallr.app.ui.map.MapPinImage
import com.gallr.app.ui.map.parseMapPinImageId
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
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
    fun `each stop is numbered in order and pictured by its cover`() {
        val stops = listOf(RouteMapStop(origin, coverUrl = "first.jpg"), RouteMapStop(stop, coverUrl = null))

        val features = routeStopFeatures(stops).features
        val properties = features.map { it.properties }

        assertEquals(listOf("1", "2"), properties.map { it.getValue(ROUTE_STOP_NUMBER).jsonPrimitive.content })
        assertEquals(listOf(true, false), properties.map { it.getValue(ROUTE_STOP_HAS_COVER).jsonPrimitive.boolean })
        val firstImage = properties.first().getValue(ROUTE_STOP_IMAGE)
        assertEquals(
            MapPinImage(coverUrl = "first.jpg", saved = false),
            parseMapPinImageId(firstImage.jsonPrimitive.content),
        )
        assertEquals(listOf(JsonPrimitive("route-stop-0"), JsonPrimitive("route-stop-1")), features.map { it.id })
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

    @Test
    fun `walking paths are drawn solid and estimates dashed`() {
        val second = GeoPoint(latitude = 37.5720, longitude = 126.9900)
        val bend = GeoPoint(latitude = 37.5740, longitude = 126.9880)
        val legs =
            listOf(
                RouteMapLeg(geometry = listOf(origin, stop), routed = false),
                RouteMapLeg(geometry = listOf(stop, bend, second), routed = true),
            )

        val lines = routeLegLines(origin, listOf(stop, second), legs)

        assertEquals(listOf(listOf(stop, bend, second)), lines.routed)
        assertEquals(listOf(listOf(origin, stop)), lines.estimated, "the leg from the start is an estimate")
    }

    @Test
    fun `a leg without a path is a dashed straight segment and a leg that goes nowhere draws nothing`() {
        val lines =
            routeLegLines(
                origin = stop,
                stops = listOf(stop, origin),
                legs = listOf(RouteMapLeg(emptyList(), routed = false), RouteMapLeg(emptyList(), routed = false)),
            )

        assertEquals(emptyList<List<GeoPoint>>(), lines.routed)
        assertEquals(listOf(listOf(stop, origin)), lines.estimated)
        val features = routeLinesFeatures(listOf(listOf(stop, origin), listOf(origin)))
        assertEquals(1, features.features.size, "a line of one point is not drawn")
    }
}
