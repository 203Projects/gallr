package com.gallr.app.ui.route

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.WalkingRouter
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
 * A single point gets the closest zoom; an unmeasured panel gets the widest, [minZoom].
 */
internal fun routeMapViewport(
    points: List<GeoPoint>,
    widthDp: Float,
    heightDp: Float,
    paddingDp: Float,
    minZoom: Double = ROUTE_MAP_MIN_ZOOM,
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
    if (innerWidth <= 0f || innerHeight <= 0f) return RouteMapViewport(latitude, longitude, minZoom)

    val lonSpan = maxLon - minLon
    val mercatorSpan = mercatorY(maxLat) - mercatorY(minLat)
    val zoomForWidth =
        if (lonSpan > 0) log2(innerWidth * FULL_TURN_DEGREES / (TILE_SIZE_DP * lonSpan)) else Double.POSITIVE_INFINITY
    val zoomForHeight =
        if (mercatorSpan > 0) log2(innerHeight * 2 * PI / (TILE_SIZE_DP * mercatorSpan)) else Double.POSITIVE_INFINITY
    val zoom = min(zoomForWidth, zoomForHeight)
    return RouteMapViewport(latitude, longitude, max(minZoom, min(ROUTE_MAP_MAX_ZOOM, zoom)))
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

/**
 * One leg on the route map: its path, whether that path is a walking path (solid) or an estimate (dashed), and who
 * drew a walking path, for the credit.
 */
internal data class RouteMapLeg(
    val geometry: List<GeoPoint>,
    val routed: Boolean,
    val router: WalkingRouter? = null,
)

/** The route's legs split by how they are drawn (spec 093 FR-007), and the routers whose paths are on the map. */
internal data class RouteLegLines(
    val routed: List<List<GeoPoint>>,
    val estimated: List<List<GeoPoint>>,
    val routers: Set<WalkingRouter> = emptySet(),
)

/**
 * Each leg as its own line, into each stop in order from [origin]: a walking path when its leg has one, otherwise a
 * straight estimate. A leg that goes nowhere (two stops at one place) draws nothing.
 */
internal fun routeLegLines(
    origin: GeoPoint,
    stops: List<GeoPoint>,
    legs: List<RouteMapLeg>,
): RouteLegLines {
    val routed = mutableListOf<List<GeoPoint>>()
    val estimated = mutableListOf<List<GeoPoint>>()
    val routers = mutableSetOf<WalkingRouter>()
    var previous = origin
    stops.forEachIndexed { index, stop ->
        val leg = legs.getOrNull(index)
        val points = mutableListOf(previous)
        (leg?.geometry.orEmpty() + stop).forEach { point -> if (point != points.last()) points += point }
        if (points.size >= MIN_LINE_POINTS) {
            if (leg?.routed == true && leg.geometry.isNotEmpty()) {
                routed += points
                leg.router?.let(routers::add)
            } else {
                estimated += points
            }
        }
        previous = stop
    }
    return RouteLegLines(routed, estimated, routers)
}

private const val MIN_LINE_POINTS = 2

/** The map's description for screen readers: its stops and, when the route's distance is known, that distance. */
internal fun routeMapLabel(
    stopCount: Int,
    language: AppLanguage,
    distanceMeters: Int? = null,
): String {
    val base =
        when (language) {
            AppLanguage.KO -> "경로 지도 · 정류장 ${stopCount}개"
            AppLanguage.EN -> "ROUTE MAP · $stopCount STOPS"
        }
    return distanceMeters?.let { "$base · ${estimatedDistanceLabel(it, language)}" } ?: base
}

internal fun routeMapExpandLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "지도 크게 보기" else "EXPAND MAP"

internal fun routeMapFitLabel(language: AppLanguage): String =
    if (language == AppLanguage.KO) "경로 전체 보기" else "FIT ROUTE"

internal fun routeMapCloseLabel(language: AppLanguage): String = if (language == AppLanguage.KO) "닫기" else "CLOSE"

/** The small panel's credit for its OpenStreetMap tiles (ODbL: on or next to the map, research R7). */
internal const val ROUTE_MAP_PANEL_CREDIT = "© OpenStreetMap"

/**
 * The full-screen map's credit: the map data always, then each routing service whose walking path is on the map, as
 * its terms require (research R7). A service is named only for the paths it drew, never by configuration.
 */
internal fun routeMapCredit(routers: Set<WalkingRouter>): String =
    (listOf(MAP_DATA_CREDIT) + WalkingRouter.entries.filter { it in routers }.map(::routerCredit))
        .joinToString(separator = " · ")

private fun routerCredit(router: WalkingRouter): String =
    when (router) {
        WalkingRouter.OPENROUTESERVICE -> "© openrouteservice by HeiGIT"
        WalkingRouter.OSRM -> "Routing © OSRM"
    }

private const val MAP_DATA_CREDIT = "© OpenFreeMap · © OpenStreetMap"

internal const val ROUTE_MAP_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

private fun mercatorY(latitudeDegrees: Double): Double = ln(tan(PI / 4 + latitudeDegrees * PI / 360))

private const val TILE_SIZE_DP = 512.0
private const val FULL_TURN_DEGREES = 360.0
