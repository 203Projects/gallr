package com.gallr.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gallr.app.ui.tabs.map.FALLBACK_SEOUL_MAP_STYLE
import com.gallr.app.ui.tabs.map.PIN_FONT
import com.gallr.app.ui.tabs.map.QUIET_SEOUL_MAP_STYLE_RESOURCE
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.ExhibitionRouteEstimate
import gallr.composeapp.generated.resources.Res
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * A static panel of the planned route on the quiet Seoul style (DESIGN.md, Route map): the line along the
 * legs, numbered stops, and the origin, fitted to the panel. Gestures are off so the list keeps scrolling.
 */
@Composable
internal fun RouteMap(
    origin: GeoPoint,
    route: ExhibitionRouteEstimate,
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val stops =
        remember(route) {
            route.stops.mapNotNull { exhibition ->
                val latitude = exhibition.latitude ?: return@mapNotNull null
                val longitude = exhibition.longitude ?: return@mapNotNull null
                runCatching { GeoPoint(latitude, longitude) }.getOrNull()
            }
        }
    val legGeometries = remember(route) { route.legs.map { it.geometry } }
    RouteMap(
        origin = origin,
        stops = stops,
        legGeometries = legGeometries,
        language = language,
        modifier = modifier,
    )
}

/**
 * The route panel for numbered [stops] reached from [origin]; [legGeometries] holds the path into each stop in
 * order, and an empty path is drawn as a straight line.
 */
@Composable
internal fun RouteMap(
    origin: GeoPoint,
    stops: List<GeoPoint>,
    legGeometries: List<List<GeoPoint>>,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    minZoom: Double = ROUTE_MAP_MIN_ZOOM,
) {
    val line = remember(origin, stops, legGeometries) { routeLinePoints(origin, stops, legGeometries) }
    val styleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = routeMapLabel(stops.size, language) },
    ) {
        val viewport =
            remember(line, maxWidth, maxHeight) {
                routeMapViewport(line, maxWidth.value, maxHeight.value, ROUTE_MAP_PADDING.value, minZoom)
            }
        val mapState =
            rememberMapState(
                baseStyle = BaseStyle.Uri(styleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE }),
                initialCameraPosition = viewport.toCameraPosition(),
            ) {
                val lineSource =
                    rememberGeoJsonSource(data = GeoJsonData.Features(routeLineFeatures(line)))
                val stopSource =
                    rememberGeoJsonSource(data = GeoJsonData.Features(routeStopFeatures(stops)))
                val originSource =
                    rememberGeoJsonSource(data = GeoJsonData.Features(routePointFeatures(origin)))

                LineLayer(
                    id = "gallr-route-line",
                    source = lineSource,
                    color = const(Color.Black),
                    width = const(2.dp),
                    cap = const(LineCap.Round),
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
                CircleLayer(
                    id = "gallr-route-stop",
                    source = stopSource,
                    color = const(Color.White),
                    radius = const(10.dp),
                    strokeColor = const(Color.Black),
                    strokeWidth = const(1.5.dp),
                )
                SymbolLayer(
                    id = "gallr-route-stop-number",
                    source = stopSource,
                    textField = feature.get(STOP_NUMBER).asString(),
                    textFont = const(listOf(PIN_FONT)),
                    textSize = const(11.sp),
                    textColor = const(Color.Black),
                    textAllowOverlap = const(true),
                    textIgnorePlacement = const(true),
                )
            }
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
    }
}

private fun RouteMapViewport.toCameraPosition(): CameraPosition =
    CameraPosition(center = Position(latitude = latitude, longitude = longitude), zoom = zoom)

private fun position(point: GeoPoint): Position = Position(latitude = point.latitude, longitude = point.longitude)

/**
 * The route line as map features. A GeoJSON line needs two positions, so a route that never leaves its
 * origin, such as a single stop the visitor is already standing at, draws no line.
 */
internal fun routeLineFeatures(line: List<GeoPoint>): FeatureCollection<LineString, JsonObject?> {
    if (line.size < MIN_LINE_POSITIONS) return FeatureCollection(emptyList())
    return FeatureCollection(listOf(Feature(geometry = LineString(line.map(::position)), properties = null)))
}

private fun routePointFeatures(point: GeoPoint): FeatureCollection<Point, JsonObject?> =
    FeatureCollection(listOf(Feature(geometry = Point(position(point)), properties = null)))

private fun routeStopFeatures(stops: List<GeoPoint>): FeatureCollection<Point, JsonObject> =
    FeatureCollection(
        stops.mapIndexed { index, stop ->
            Feature(
                geometry = Point(position(stop)),
                properties = buildJsonObject { put(STOP_NUMBER, (index + 1).toString()) },
                id = JsonPrimitive("route-stop-$index"),
            )
        },
    )

private val ROUTE_MAP_PADDING = 24.dp
private const val STOP_NUMBER = "n"
private const val MIN_LINE_POSITIONS = 2
