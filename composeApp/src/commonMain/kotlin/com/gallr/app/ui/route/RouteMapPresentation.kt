package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Camera target for the route panel: the route's centre and the zoom that fits every point. */
internal data class RouteMapViewport(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

internal const val ROUTE_MAP_MIN_ZOOM = 11.0
internal const val ROUTE_MAP_MAX_ZOOM = 16.0

/**
 * Fits [points] into a panel of [widthDp] × [heightDp] with [paddingDp] on every side. MapLibre zoom levels
 * are defined over density-independent pixels and 512px tiles, so the sizes are in dp, not device pixels.
 * A single point gets the closest zoom; an unmeasured panel gets the widest.
 */
internal fun routeMapViewport(
    points: List<GeoPoint>,
    widthDp: Float,
    heightDp: Float,
    paddingDp: Float,
): RouteMapViewport {
    require(points.isNotEmpty()) { "a route viewport needs at least one point" }
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val latitude = (minLat + maxLat) / 2
    val longitude = (minLon + maxLon) / 2
    val innerWidth = widthDp - 2 * paddingDp
    val innerHeight = heightDp - 2 * paddingDp
    if (innerWidth <= 0f || innerHeight <= 0f) return RouteMapViewport(latitude, longitude, ROUTE_MAP_MIN_ZOOM)

    val lonSpan = maxLon - minLon
    val mercatorSpan = mercatorY(maxLat) - mercatorY(minLat)
    val zoomForWidth =
        if (lonSpan > 0) log2(innerWidth * FULL_TURN_DEGREES / (TILE_SIZE_DP * lonSpan)) else Double.POSITIVE_INFINITY
    val zoomForHeight =
        if (mercatorSpan > 0) log2(innerHeight * 2 * PI / (TILE_SIZE_DP * mercatorSpan)) else Double.POSITIVE_INFINITY
    val zoom = min(zoomForWidth, zoomForHeight)
    return RouteMapViewport(latitude, longitude, max(ROUTE_MAP_MIN_ZOOM, min(ROUTE_MAP_MAX_ZOOM, zoom)))
}

/**
 * The line to draw: origin, then each leg's provider geometry when it has one, else a straight segment to
 * the stop. Consecutive duplicates are dropped so joins stay clean.
 */
internal fun routeLinePoints(
    origin: GeoPoint,
    stops: List<GeoPoint>,
    legGeometries: List<List<GeoPoint>>,
): List<GeoPoint> {
    val line = mutableListOf(origin)
    stops.forEachIndexed { index, stop ->
        val geometry = legGeometries.getOrNull(index).orEmpty()
        (geometry + stop).forEach { point ->
            if (point != line.last()) line += point
        }
    }
    return line
}

internal fun routeMapLabel(
    stopCount: Int,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "경로 지도 · 정류장 ${stopCount}개"
        AppLanguage.EN -> "ROUTE MAP · $stopCount STOPS"
    }

private fun mercatorY(latitudeDegrees: Double): Double = ln(tan(PI / 4 + latitudeDegrees * PI / 360))

private const val TILE_SIZE_DP = 512.0
private const val FULL_TURN_DEGREES = 360.0
