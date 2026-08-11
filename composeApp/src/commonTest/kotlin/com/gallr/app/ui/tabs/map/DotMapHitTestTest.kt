package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.model.map.MapMarkState
import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.ProjectedMapMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotMapHitTestTest {
    private val marks = listOf(
        ProjectedMapMark(
            id = "left",
            cellId = "1",
            displayPoint = NormalizedPoint(0.25, 0.5),
            sourcePoint = GeoPoint(37.5, 126.9),
            state = MapMarkState.UNEXPLORED,
            itemIds = listOf("left"),
        ),
        ProjectedMapMark(
            id = "right",
            cellId = "2",
            displayPoint = NormalizedPoint(0.75, 0.5),
            sourcePoint = GeoPoint(37.5, 127.1),
            state = MapMarkState.SAVED,
            itemIds = listOf("right"),
        ),
    )

    @Test
    fun `hit test returns the closest mark inside the minimum touch target`() {
        assertEquals(
            "right",
            hitTestMapMark(marks, NormalizedPoint(0.72, 0.52), radius = 0.08)?.id,
        )
    }

    @Test
    fun `hit test returns null outside every target`() {
        assertNull(hitTestMapMark(marks, NormalizedPoint(0.5, 0.1), radius = 0.08))
    }

    @Test
    fun `selected map mark becomes the pulsing focal point`() {
        val devicePoint = NormalizedPoint(0.1, 0.1)

        assertEquals(marks[1].displayPoint, focalMapPoint(marks[1], devicePoint))
    }

    @Test
    fun `device point remains the focal point when selection is cleared`() {
        val devicePoint = NormalizedPoint(0.1, 0.1)

        assertEquals(devicePoint, focalMapPoint(null, devicePoint))
    }
}
