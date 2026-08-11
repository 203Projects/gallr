package com.gallr.shared.map

import com.gallr.shared.data.model.map.DotCell
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.DotMapProjection
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.model.map.MapProjectionItem
import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.ProjectedMapMark

/** Deterministically snaps geographic items to presentation-only geometry cells. */
object DotMapProjector {
    fun project(
        geometry: DotMapGeometry,
        items: List<MapProjectionItem>,
    ): DotMapProjection {
        val unavailable = items.filter { it.sourcePoint == null }.map { it.id }.sorted()
        val groups =
            items
                .filter { it.sourcePoint != null }
                .groupBy { it.groupKey }
                .toList()
                .sortedBy { it.first }
        val occupied = mutableSetOf<String>()
        val cells = geometry.cells.sortedBy { it.id }

        val marks =
            groups.mapNotNull { (groupKey, groupItems) ->
                val sourcePoint = groupItems.firstNotNullOfOrNull { it.sourcePoint } ?: return@mapNotNull null
                val target = geometry.bounds.project(sourcePoint)
                val cell = nearestCell(target.x, target.y, cells, occupied)
                occupied += cell.id
                ProjectedMapMark(
                    id = groupKey,
                    cellId = cell.id,
                    displayPoint = cell.point,
                    sourcePoint = sourcePoint,
                    state = groupItems.maxBy { it.state.priority }.state,
                    itemIds = groupItems.map { it.id }.sorted(),
                )
            }

        return DotMapProjection(
            marks = marks,
            coordinateUnavailableItemIds = unavailable,
        )
    }

    /**
     * Places the device location on the same presentation grid as map marks.
     * It stays hidden outside the current scope and prefers an empty cell so
     * the location pulse never obscures an exhibition marker.
     */
    fun projectLocation(
        geometry: DotMapGeometry,
        point: GeoPoint,
        occupiedCellIds: Set<String>,
    ): NormalizedPoint? {
        if (!geometry.bounds.contains(point)) return null
        val target = geometry.bounds.project(point)
        val occupiedCells = geometry.cells.filter { it.id in occupiedCellIds }
        val clearance = typicalCellSpacing(geometry.cells) * LOCATION_CLEARANCE_IN_CELLS
        val safeCells =
            geometry.cells.filter { candidate ->
                candidate.id !in occupiedCellIds &&
                    occupiedCells.all { occupied ->
                        squaredDistance(candidate, occupied) >= clearance * clearance
                    }
            }
        return nearestCell(
            x = target.x,
            y = target.y,
            cells = safeCells.ifEmpty { geometry.cells }.sortedBy { it.id },
            occupied = if (safeCells.isEmpty()) occupiedCellIds else emptySet(),
        ).point
    }

    private fun typicalCellSpacing(cells: List<DotCell>): Double {
        if (cells.size < 2) return 0.0
        val nearestDistances =
            cells
                .map { cell ->
                    cells
                        .asSequence()
                        .filterNot { it.id == cell.id }
                        .minOf { other -> squaredDistance(cell, other) }
                }.sorted()
        return kotlin.math.sqrt(nearestDistances[nearestDistances.size / 2])
    }

    private fun squaredDistance(
        first: DotCell,
        second: DotCell,
    ): Double {
        val dx = first.point.x - second.point.x
        val dy = first.point.y - second.point.y
        return dx * dx + dy * dy
    }

    private const val LOCATION_CLEARANCE_IN_CELLS = 2.9

    private fun nearestCell(
        x: Double,
        y: Double,
        cells: List<DotCell>,
        occupied: Set<String>,
    ): DotCell {
        val available = cells.filterNot { it.id in occupied }.ifEmpty { cells }
        return available.minWith(
            compareBy<DotCell> {
                val dx = it.point.x - x
                val dy = it.point.y - y
                dx * dx + dy * dy
            }.thenBy { it.id },
        )
    }
}
