package com.gallr.shared.map

import com.gallr.shared.data.model.map.GeoBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericCityDotGeometryTest {
    @Test
    fun `generic city constellation keeps real bounds and a dense neutral shape`() {
        val bounds = GeoBounds(north = 35.3, east = 129.3, south = 34.9, west = 128.7)

        val geometry = GenericCityDotGeometry.create("busan", bounds)

        assertEquals(bounds, geometry.bounds)
        assertEquals("city-constellation:busan", geometry.key)
        assertTrue(geometry.cells.size > 500)
        assertTrue(geometry.cells.none { it.point.y in 0.47..0.55 && it.point.x in 0.28..0.72 })
    }
}
