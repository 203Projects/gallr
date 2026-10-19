package com.gallr.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gallr.app.ui.map.LogoPinPainter
import com.gallr.app.ui.map.MAP_PIN_BADGE_OFFSET_X
import com.gallr.app.ui.map.MAP_PIN_BADGE_OFFSET_Y
import com.gallr.app.ui.map.MAP_PIN_IMAGE_SIZE
import com.gallr.app.ui.map.mapPinImageId
import com.gallr.app.ui.map.mapPinRingColor
import com.gallr.app.ui.map.rememberMapPinImageResolver
import com.gallr.app.ui.tabs.map.FALLBACK_SEOUL_MAP_STYLE
import com.gallr.app.ui.tabs.map.PIN_FONT
import com.gallr.app.ui.tabs.map.QUIET_SEOUL_MAP_STYLE_RESOURCE
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.RouteLegQuality
import gallr.composeapp.generated.resources.Res
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** A stop on the route panel: where it is and the cover that pictures it, when its exhibition has one. */
internal data class RouteMapStop(
    val point: GeoPoint,
    val coverUrl: String?,
)

/**
 * A static panel of the planned route on the quiet Seoul style (DESIGN.md, Route map): the line along the
 * legs, each stop as a numbered pin picturing its exhibition, and the origin, fitted to the panel. Gestures
 * are off so the list keeps scrolling.
 */
@Composable
internal fun RouteMap(
    origin: GeoPoint,
    route: ExhibitionRouteEstimate,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    val stops = remember(route) { routeMapStops(route) }
    val legs = remember(route) { routeMapLegs(route) }
    RouteMap(
        origin = origin,
        stops = stops,
        legs = legs,
        language = language,
        modifier = modifier,
        distanceMeters = route.totalDistanceMeters,
        onExpand = onExpand,
    )
}

/** The planned route's stops that have a map location, each with its exhibition's cover. */
internal fun routeMapStops(route: ExhibitionRouteEstimate): List<RouteMapStop> =
    route.stops.mapNotNull { exhibition ->
        val latitude = exhibition.latitude ?: return@mapNotNull null
        val longitude = exhibition.longitude ?: return@mapNotNull null
        runCatching { GeoPoint(latitude, longitude) }
            .getOrNull()
            ?.let { point -> RouteMapStop(point, exhibition.coverImageUrl) }
    }

/** The planned route's legs as the map draws them: walking paths solid, estimates dashed. */
internal fun routeMapLegs(route: ExhibitionRouteEstimate): List<RouteMapLeg> =
    route.legs.map { leg ->
        RouteMapLeg(leg.geometry, routed = leg.quality == RouteLegQuality.ROUTED, router = leg.router)
    }

/**
 * The route panel for numbered [stops] reached from [origin]; [legs] holds the path into each stop in order, and a
 * leg without a path is drawn as a dashed straight line. [onExpand] adds the control that opens the full-screen map;
 * the panel itself never takes a gesture.
 */
@Composable
internal fun RouteMap(
    origin: GeoPoint,
    stops: List<RouteMapStop>,
    legs: List<RouteMapLeg>,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    minZoom: Double = ROUTE_MAP_MIN_ZOOM,
    distanceMeters: Int? = null,
    onExpand: (() -> Unit)? = null,
) {
    val line =
        remember(origin, stops, legs) {
            routeLinePoints(origin, stops.map(RouteMapStop::point), legs.map(RouteMapLeg::geometry))
        }
    val lines = remember(origin, stops, legs) { routeLegLines(origin, stops.map(RouteMapStop::point), legs) }

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = routeMapLabel(stops.size, language, distanceMeters) },
    ) {
        val viewport =
            remember(line, maxWidth, maxHeight) {
                // Pins stand above their stops, so the route is fitted into the panel below that headroom.
                routeMapViewport(
                    points = line,
                    widthDp = maxWidth.value,
                    heightDp = maxHeight.value - ROUTE_MAP_PIN_HEADROOM.value,
                    paddingDp = ROUTE_MAP_PADDING.value,
                    minZoom = minZoom,
                )
            }
        val mapState = rememberRouteMapState(origin, stops, lines, viewport)
        LaunchedEffect(viewport) { mapState.setCameraPosition(viewport.toCameraPosition()) }
        val cameraConstraints =
            remember(minZoom) {
                CameraConstraints {
                    this.minZoom = minZoom
                    maxZoom = ROUTE_MAP_MAX_ZOOM
                    maxPitch = 0.0
                }
            }

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            cameraConstraints = cameraConstraints,
            interactions = MapInteractions.None,
            // The panel is a static picture, so it carries none of the map controls.
            overlay = {},
        )
        // The panel is a picture: this layer takes the touches so the list beneath keeps scrolling.
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
        RouteMapCreditText(
            text = ROUTE_MAP_PANEL_CREDIT,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        if (onExpand != null) {
            RouteMapExpandButton(
                language = language,
                onExpand = onExpand,
                modifier = Modifier.align(Alignment.TopEnd).padding(GallrSpacing.sm),
            )
        }
    }
}

/**
 * The map state both the panel and the full-screen map draw: walking paths as solid lines and estimates as dashed
 * ones, the origin dot, and each stop as a numbered pin picturing its exhibition (DESIGN.md, Route map panel).
 */
@Composable
internal fun rememberRouteMapState(
    origin: GeoPoint,
    stops: List<RouteMapStop>,
    lines: RouteLegLines,
    viewport: RouteMapViewport,
): MapState {
    val styleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }
    val pinImageResolver = rememberMapPinImageResolver()
    val mapState =
        rememberMapState(
            baseStyle = BaseStyle.Uri(styleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE }),
            initialCameraPosition = viewport.toCameraPosition(),
        ) {
            val walkedSource =
                rememberGeoJsonSource(data = GeoJsonData.Features(routeLinesFeatures(lines.routed)))
            val estimatedSource =
                rememberGeoJsonSource(data = GeoJsonData.Features(routeLinesFeatures(lines.estimated)))
            val stopSource =
                rememberGeoJsonSource(data = GeoJsonData.Features(routeStopFeatures(stops)))
            val originSource =
                rememberGeoJsonSource(data = GeoJsonData.Features(routePointFeatures(origin)))
            val plainPin = remember { LogoPinPainter(cover = null, ring = mapPinRingColor(saved = false)) }

            LineLayer(
                id = "gallr-route-line",
                source = walkedSource,
                color = const(Color.Black),
                width = const(2.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            // An estimate is never drawn as a path: a dashed straight line says "about here" (spec 093 FR-007).
            LineLayer(
                id = "gallr-route-line-estimated",
                source = estimatedSource,
                color = const(Color.Black),
                width = const(2.dp),
                dasharray = const(ESTIMATED_LINE_DASHES),
                join = const(LineJoin.Round),
            )
            CircleLayer(
                id = "gallr-route-origin",
                source = originSource,
                color = const(Color.Black),
                radius = const(4.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(1.5.dp),
            )
            // A plain logo pin stands under each stop until the map has its cover (DESIGN.md, Map pins).
            SymbolLayer(
                id = "gallr-route-stop-plain",
                source = stopSource,
                iconImage = image(plainPin, size = MAP_PIN_IMAGE_SIZE),
                iconSize = const(ROUTE_PIN_SCALE),
                iconAnchor = const(SymbolAnchor.Bottom),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
            SymbolLayer(
                id = "gallr-route-stop-cover",
                source = stopSource,
                filter = feature.get(ROUTE_STOP_HAS_COVER).asBoolean(),
                iconImage = image(feature.get(ROUTE_STOP_IMAGE).asString()),
                iconSize = const(ROUTE_PIN_SCALE),
                iconAnchor = const(SymbolAnchor.Bottom),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
            CircleLayer(
                id = "gallr-route-stop-badge",
                source = stopSource,
                translate = offset(ROUTE_BADGE_OFFSET_X, ROUTE_BADGE_OFFSET_Y),
                color = const(Color.White),
                radius = const(ROUTE_STOP_BADGE_RADIUS),
                strokeColor = const(Color.Black),
                strokeWidth = const(1.dp),
            )
            SymbolLayer(
                id = "gallr-route-stop-number",
                source = stopSource,
                textField = feature.get(ROUTE_STOP_NUMBER).asString(),
                textFont = const(listOf(PIN_FONT)),
                textSize = const(10.sp),
                textColor = const(Color.Black),
                textTranslate = offset(ROUTE_BADGE_OFFSET_X, ROUTE_BADGE_OFFSET_Y),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
        }
    SideEffect { mapState.missingImageResolver = pinImageResolver }
    return mapState
}

internal fun RouteMapViewport.toCameraPosition(): CameraPosition =
    CameraPosition(
        center = Position(latitude = latitude, longitude = longitude),
        zoom = zoom,
        padding = DpPadding(top = ROUTE_MAP_PIN_HEADROOM),
    )

private fun position(point: GeoPoint): Position = Position(latitude = point.latitude, longitude = point.longitude)

/**
 * The route line as map features. A GeoJSON line needs two positions, so a route that never leaves its
 * origin, such as a single stop the visitor is already standing at, draws no line.
 */
internal fun routeLineFeatures(line: List<GeoPoint>): FeatureCollection<LineString, JsonObject?> =
    routeLinesFeatures(listOf(line))

/** One line feature per line of two or more positions. */
internal fun routeLinesFeatures(lines: List<List<GeoPoint>>): FeatureCollection<LineString, JsonObject?> =
    FeatureCollection(
        lines
            .filter { it.size >= MIN_LINE_POSITIONS }
            .map { line -> Feature(geometry = LineString(line.map(::position)), properties = null) },
    )

private fun routePointFeatures(point: GeoPoint): FeatureCollection<Point, JsonObject?> =
    FeatureCollection(listOf(Feature(geometry = Point(position(point)), properties = null)))

/** One point per stop in route order: its number, and the pin image that pictures its exhibition. */
internal fun routeStopFeatures(stops: List<RouteMapStop>): FeatureCollection<Point, JsonObject> =
    FeatureCollection(
        stops.mapIndexed { index, stop ->
            val coverUrl = stop.coverUrl?.takeIf(String::isNotBlank)
            Feature(
                geometry = Point(position(stop.point)),
                properties =
                    buildJsonObject {
                        put(ROUTE_STOP_NUMBER, (index + 1).toString())
                        put(ROUTE_STOP_HAS_COVER, coverUrl != null)
                        // The route panel is monochrome: a saved exhibition is not marked here.
                        put(ROUTE_STOP_IMAGE, mapPinImageId(coverUrl, saved = false))
                    },
                id = JsonPrimitive("route-stop-$index"),
            )
        },
    )

private val ROUTE_MAP_PADDING = 24.dp

/** Pins on the small route panel are drawn at three quarters of their Map tab size, so close stops show. */
private const val ROUTE_PIN_SCALE = 0.75f
private val ROUTE_BADGE_OFFSET_X = MAP_PIN_BADGE_OFFSET_X * ROUTE_PIN_SCALE
private val ROUTE_BADGE_OFFSET_Y = MAP_PIN_BADGE_OFFSET_Y * ROUTE_PIN_SCALE

/** Room kept above the fitted route for the pins, which stand about 41dp over their stops. */
private val ROUTE_MAP_PIN_HEADROOM = 32.dp
private val ROUTE_STOP_BADGE_RADIUS = 8.dp
internal const val ROUTE_STOP_NUMBER = "n"
internal const val ROUTE_STOP_HAS_COVER = "has_cover"
internal const val ROUTE_STOP_IMAGE = "image"
private const val MIN_LINE_POSITIONS = 2

/** Dash and gap, in line widths. */
private val ESTIMATED_LINE_DASHES: List<Number> = listOf(2, 2)
