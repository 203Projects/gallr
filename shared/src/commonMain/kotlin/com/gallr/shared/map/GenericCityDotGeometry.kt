package com.gallr.shared.map

import com.gallr.shared.data.model.map.DotCell
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.GeoBounds
import com.gallr.shared.data.model.map.NormalizedPoint
import kotlin.math.abs

/**
 * A neutral, presentation-only city constellation used until a city has an
 * authored boundary geometry. Geographic projection still uses the scope's
 * real bounds; this shape is never sent back to geographic APIs.
 */
object GenericCityDotGeometry {
    fun create(key: String, bounds: GeoBounds): DotMapGeometry = DotMapGeometry(
        key = "city-constellation:$key",
        version = 1,
        bounds = bounds,
        cells = buildList {
            val columns = 33
            val rows = 31
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = column.toDouble() / (columns - 1)
                    val y = row.toDouble() / (rows - 1)
                    val centeredX = (x - 0.5) / 0.48
                    val centeredY = (y - 0.5) / 0.45
                    val organicEdge = centeredX * centeredX + centeredY * centeredY +
                        0.16 * abs(centeredX * centeredY)
                    val centralCut = y in 0.47..0.55 && x in 0.28..0.72
                    if (organicEdge <= 1.0 && !centralCut) {
                        add(
                            DotCell(
                                id = "city-${row.toString().padStart(2, '0')}-${column.toString().padStart(2, '0')}",
                                point = NormalizedPoint(x, y),
                            ),
                        )
                    }
                }
            }
        },
    )
}
