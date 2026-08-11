package com.gallr.shared.data.model.map

import kotlin.math.max
import kotlin.math.min

/** Original geographic coordinate used for scope, distance, and navigation calculations. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude is outside the valid range" }
        require(longitude in -180.0..180.0) { "longitude is outside the valid range" }
    }
}

/** Geographic bounds whose projection is used only to place marks on an abstract scope. */
data class GeoBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
) {
    init {
        require(north > south) { "north must be greater than south" }
        require(east > west) { "east must be greater than west" }
    }

    fun project(point: GeoPoint): NormalizedPoint =
        NormalizedPoint(
            x = ((point.longitude - west) / (east - west)).coerceIn(0.0, 1.0),
            y = ((north - point.latitude) / (north - south)).coerceIn(0.0, 1.0),
        )

    fun contains(point: GeoPoint): Boolean = point.latitude in south..north && point.longitude in west..east

    companion object {
        fun containing(
            points: List<GeoPoint>,
            paddingFraction: Double = 0.08,
        ): GeoBounds? {
            if (points.isEmpty()) return null
            val rawNorth = points.maxOf { it.latitude }
            val rawSouth = points.minOf { it.latitude }
            val rawEast = points.maxOf { it.longitude }
            val rawWest = points.minOf { it.longitude }
            val latSpan = max(rawNorth - rawSouth, 0.05)
            val lngSpan = max(rawEast - rawWest, 0.05)
            return GeoBounds(
                north = min(90.0, rawNorth + latSpan * paddingFraction),
                east = min(180.0, rawEast + lngSpan * paddingFraction),
                south = max(-90.0, rawSouth - latSpan * paddingFraction),
                west = max(-180.0, rawWest - lngSpan * paddingFraction),
            )
        }
    }
}

/** Presentation-only coordinate. It must never be accepted by geographic APIs. */
data class NormalizedPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x in 0.0..1.0 && y in 0.0..1.0) { "normalized coordinates must be in 0..1" }
    }
}

data class MapScopeId(
    val value: String,
)

enum class MapScopeKind { COUNTRY, CITY, DISTRICT }

enum class PersonalMapMode { TO_VISIT, VISITED, ALL }

data class MapScope(
    val id: MapScopeId,
    val kind: MapScopeKind,
    val parentId: MapScopeId?,
    val countryCode: String,
    val cityKey: String?,
    val districtKey: String?,
    val labelKo: String,
    val labelEn: String,
    val geoBounds: GeoBounds,
    val geometryKey: String?,
)

data class DotCell(
    val id: String,
    val point: NormalizedPoint,
)

data class DotMapGeometry(
    val key: String,
    val version: Int,
    val bounds: GeoBounds,
    val cells: List<DotCell>,
) {
    init {
        require(version > 0) { "geometry version must be positive" }
        require(cells.isNotEmpty()) { "geometry must contain at least one cell" }
    }
}

enum class MapMarkState(
    internal val priority: Int,
) {
    UNEXPLORED(0),
    SAVED(1),
    VISITED(2),
    SELECTED(3),
}

data class MapProjectionItem(
    val id: String,
    val groupKey: String,
    val sourcePoint: GeoPoint?,
    val state: MapMarkState,
)

data class ProjectedMapMark(
    val id: String,
    val cellId: String,
    val displayPoint: NormalizedPoint,
    val sourcePoint: GeoPoint,
    val state: MapMarkState,
    val itemIds: List<String>,
)

data class DotMapProjection(
    val marks: List<ProjectedMapMark>,
    val coordinateUnavailableItemIds: List<String>,
)

data class ScopeAggregate(
    val activeExhibitionCount: Int,
    val visitedExhibitionCount: Int,
    val visitCount: Int,
    val savedUnvisitedCount: Int,
    val unexploredCount: Int,
    val coordinateUnavailableCount: Int,
)
