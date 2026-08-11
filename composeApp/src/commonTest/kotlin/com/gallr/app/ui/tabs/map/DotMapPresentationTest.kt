package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.map.DotCell
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.GeoBounds
import com.gallr.shared.data.model.map.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotMapPresentationTest {
    @Test
    fun `fit expands narrow source geometry into the requested presentation padding`() {
        val source = geometry(
            NormalizedPoint(0.2, 0.1),
            NormalizedPoint(0.6, 0.9),
        )

        val fitted = DotMapPresentation.fit(source, horizontalPadding = 0.14, verticalPadding = 0.025)

        assertEquals(0.14, fitted.geometry.cells.minOf { it.point.x }, absoluteTolerance = 0.0001)
        assertEquals(0.86, fitted.geometry.cells.maxOf { it.point.x }, absoluteTolerance = 0.0001)
        assertEquals(0.025, fitted.geometry.cells.minOf { it.point.y }, absoluteTolerance = 0.0001)
        assertEquals(0.975, fitted.geometry.cells.maxOf { it.point.y }, absoluteTolerance = 0.0001)
    }

    @Test
    fun `country label rails separate nearby cities and keep labels outside the map`() {
        val resolved = resolveCountryLabelRails(
            listOf(
                "seoul" to NormalizedPoint(0.3, 0.2),
                "suwon" to NormalizedPoint(0.4, 0.23),
                "busan" to NormalizedPoint(0.7, 0.7),
            ),
        )

        assertTrue(resolved.getValue("suwon").labelY - resolved.getValue("seoul").labelY >= 0.144)
        assertEquals(CountryLabelSide.LEFT, resolved.getValue("seoul").side)
        assertEquals(CountryLabelSide.RIGHT, resolved.getValue("busan").side)
        assertEquals(0.7, resolved.getValue("busan").labelY, absoluteTolerance = 0.0001)
    }

    private fun geometry(vararg points: NormalizedPoint) = DotMapGeometry(
        key = "test",
        version = 1,
        bounds = GeoBounds(north = 38.0, east = 130.0, south = 33.0, west = 125.0),
        cells = points.mapIndexed { index, point -> DotCell(index.toString(), point) },
    )
}
