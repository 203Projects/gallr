package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.ProjectedMapMark

internal fun hitTestMapMark(
    marks: List<ProjectedMapMark>,
    point: NormalizedPoint,
    radius: Double,
): ProjectedMapMark? = marks
    .asSequence()
    .map { mark ->
        val dx = mark.displayPoint.x - point.x
        val dy = mark.displayPoint.y - point.y
        mark to (dx * dx + dy * dy)
    }
    .filter { (_, distanceSquared) -> distanceSquared <= radius * radius }
    .minWithOrNull(compareBy<Pair<ProjectedMapMark, Double>> { it.second }.thenBy { it.first.id })
    ?.first

internal fun focalMapPoint(
    selectedMark: ProjectedMapMark?,
    deviceLocationPoint: NormalizedPoint?,
): NormalizedPoint? = selectedMark?.displayPoint ?: deviceLocationPoint
