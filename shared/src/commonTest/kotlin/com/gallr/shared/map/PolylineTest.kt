package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 093: walking paths arrive as precision-5 encoded polylines (contracts/walking-route.md). */
class PolylineTest {
    @Test
    fun theReferenceVectorDecodes() {
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(
            listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)),
            points,
        )
    }

    @Test
    fun aSeoulPathDecodesToItsPoints() {
        val points = decodePolyline("y{cdFsrnfWSbAh@Z")

        assertEquals(3, points.size)
        assertEquals(37.54445, points.first().latitude, 1e-9)
        assertEquals(127.05594, points.first().longitude, 1e-9)
    }

    @Test
    fun malformedInputDecodesToNothing() {
        assertTrue(decodePolyline("").isEmpty())
        assertTrue(decodePolyline("_p~iF~ps|U_").isEmpty(), "a truncated value is not half a point")
        assertTrue(decodePolyline("~~~~~~~~~~~~").isEmpty(), "a value that never ends is not a point")
    }
}
