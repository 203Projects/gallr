package com.gallr.shared.map

import com.gallr.shared.data.model.map.DotCell
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.GeoBounds
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.model.map.MapMarkState
import com.gallr.shared.data.model.map.MapProjectionItem
import com.gallr.shared.data.model.map.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DotMapProjectorTest {
    private val geometry = DotMapGeometry(
        key = "test",
        version = 1,
        bounds = GeoBounds(north = 38.0, east = 128.0, south = 36.0, west = 126.0),
        cells = listOf(
            DotCell("0", NormalizedPoint(0.25, 0.25)),
            DotCell("1", NormalizedPoint(0.50, 0.50)),
            DotCell("2", NormalizedPoint(0.75, 0.75)),
        ),
    )

    @Test
    fun `same venue coordinate becomes one mark with every exhibition id`() {
        val result = DotMapProjector.project(
            geometry,
            listOf(
                item("a", "venue", GeoPoint(37.0, 127.0), MapMarkState.UNEXPLORED),
                item("b", "venue", GeoPoint(37.0, 127.0), MapMarkState.SAVED),
            ),
        )

        assertEquals(1, result.marks.size)
        assertEquals(listOf("a", "b"), result.marks.single().itemIds)
        assertEquals(MapMarkState.SAVED, result.marks.single().state)
        assertEquals(GeoPoint(37.0, 127.0), result.marks.single().sourcePoint)
    }

    @Test
    fun `colliding groups snap deterministically to distinct nearest cells`() {
        val items = listOf(
            item("b", "second", GeoPoint(37.0, 127.0), MapMarkState.UNEXPLORED),
            item("a", "first", GeoPoint(37.0, 127.0), MapMarkState.UNEXPLORED),
        )

        val first = DotMapProjector.project(geometry, items).marks.sortedBy { it.id }
        val second = DotMapProjector.project(geometry, items.reversed()).marks.sortedBy { it.id }

        assertEquals(first.map { it.cellId }, second.map { it.cellId })
        assertNotEquals(first[0].cellId, first[1].cellId)
    }

    @Test
    fun `missing source coordinates stay in accessible results but never receive a cell`() {
        val result = DotMapProjector.project(
            geometry,
            listOf(item("missing", "missing", null, MapMarkState.UNEXPLORED)),
        )

        assertEquals(emptyList(), result.marks)
        assertEquals(listOf("missing"), result.coordinateUnavailableItemIds)
        assertNull(result.marks.firstOrNull()?.sourcePoint)
    }

    @Test
    fun `capacity overflow is stable and keeps every semantic group`() {
        val items = (1..5).map { index ->
            item(
                id = "item-$index",
                groupKey = "group-$index",
                point = GeoPoint(37.0 + index * 0.01, 127.0),
                state = MapMarkState.UNEXPLORED,
            )
        }

        val first = DotMapProjector.project(geometry, items)
        val second = DotMapProjector.project(geometry, items.reversed())

        assertEquals(5, first.marks.size)
        assertEquals(
            first.marks.sortedBy { it.id }.map { it.cellId },
            second.marks.sortedBy { it.id }.map { it.cellId },
        )
    }

    @Test
    fun `current location snaps to the nearest unoccupied cell`() {
        val point = DotMapProjector.projectLocation(
            geometry = geometry,
            point = GeoPoint(37.0, 127.0),
            occupiedCellIds = setOf("1"),
        )

        assertEquals(NormalizedPoint(0.25, 0.25), point)
    }

    @Test
    fun `current location outside the active scope is not projected`() {
        val point = DotMapProjector.projectLocation(
            geometry = geometry,
            point = GeoPoint(35.0, 129.0),
            occupiedCellIds = emptySet(),
        )

        assertNull(point)
    }

    @Test
    fun `current location keeps a clear cell around exhibition marks when space allows`() {
        val spacedGeometry = DotMapGeometry(
            key = "spaced",
            version = 1,
            bounds = geometry.bounds,
            cells = listOf(
                DotCell("0", NormalizedPoint(0.05, 0.5)),
                DotCell("1", NormalizedPoint(0.2, 0.5)),
                DotCell("2", NormalizedPoint(0.35, 0.5)),
                DotCell("3", NormalizedPoint(0.5, 0.5)),
                DotCell("4", NormalizedPoint(0.65, 0.5)),
                DotCell("5", NormalizedPoint(0.8, 0.5)),
                DotCell("6", NormalizedPoint(0.95, 0.5)),
            ),
        )

        val point = DotMapProjector.projectLocation(
            geometry = spacedGeometry,
            point = GeoPoint(37.0, 127.0),
            occupiedCellIds = setOf("3"),
        )

        assertEquals(0.5, point?.y)
        kotlin.test.assertTrue(point != null && (point.x <= 0.2 || point.x >= 0.8))
    }

    private fun item(
        id: String,
        groupKey: String,
        point: GeoPoint?,
        state: MapMarkState,
    ) = MapProjectionItem(
        id = id,
        groupKey = groupKey,
        sourcePoint = point,
        state = state,
    )
}
