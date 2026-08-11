package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.NormalizedPoint

data class DotMapPresentation(
    val geometry: DotMapGeometry,
    private val minX: Double,
    private val maxX: Double,
    private val minY: Double,
    private val maxY: Double,
    private val horizontalPadding: Double,
    private val verticalPadding: Double,
) {
    fun project(point: NormalizedPoint): NormalizedPoint = NormalizedPoint(
        x = remap(point.x, minX, maxX, horizontalPadding),
        y = remap(point.y, minY, maxY, verticalPadding),
    )

    companion object {
        fun fit(
            source: DotMapGeometry,
            horizontalPadding: Double,
            verticalPadding: Double,
        ): DotMapPresentation {
            val minX = source.cells.minOf { it.point.x }
            val maxX = source.cells.maxOf { it.point.x }
            val minY = source.cells.minOf { it.point.y }
            val maxY = source.cells.maxOf { it.point.y }
            val presentation = DotMapPresentation(
                geometry = source,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
            )
            return presentation.copy(
                geometry = source.copy(
                    cells = source.cells.map { cell -> cell.copy(point = presentation.project(cell.point)) },
                ),
            )
        }
    }
}

private fun remap(value: Double, minimum: Double, maximum: Double, padding: Double): Double {
    if (maximum <= minimum) return 0.5
    val normalized = (value - minimum) / (maximum - minimum)
    return (padding + normalized * (1.0 - padding * 2.0)).coerceIn(0.0, 1.0)
}
