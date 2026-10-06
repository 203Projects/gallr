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
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.CircleLayer
import dev.sargunv.maplibrecompose.compose.layer.LineLayer
import dev.sargunv.maplibrecompose.compose.layer.SymbolLayer
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.GestureOptions
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.value.LineCap
import dev.sargunv.maplibrecompose.expressions.value.LineJoin
import gallr.composeapp.generated.resources.Res
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.FeatureCollection
import io.github.dellisd.spatialk.geojson.LineString
import io.github.dellisd.spatialk.geojson.Point
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.serialization.json.JsonPrimitive

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
    val line = remember(origin, route) { routeLinePoints(origin, stops, route.legs.map { it.geometry }) }
    val styleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }

    BoxWithConstraints(
        modifier = modifier.semantics { contentDescription = routeMapLabel(stops.size, language) },
    ) {
        val viewport =
            remember(line, maxWidth, maxHeight) {
                routeMapViewport(line, maxWidth.value, maxHeight.value, ROUTE_MAP_PADDING.value)
            }
        val cameraState = rememberCameraState(firstPosition = viewport.toCameraPosition())
        LaunchedEffect(viewport) { cameraState.position = viewport.toCameraPosition() }

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(styleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE }),
            cameraState = cameraState,
            zoomRange = ROUTE_MAP_MIN_ZOOM.toFloat()..ROUTE_MAP_MAX_ZOOM.toFloat(),
            pitchRange = 0f..0f,
            options =
                MapOptions(
                    ornamentOptions = OrnamentOptions.AllDisabled,
                    gestureOptions = GestureOptions.AllDisabled,
                ),
        ) {
            // Sources read the map's style node, so they are created inside the map content.
            val lineFeature = Feature(geometry = LineString(line.map(::position)))
            val lineSource = rememberGeoJsonSource(data = GeoJsonData.Features(FeatureCollection(listOf(lineFeature))))
            val stopSource =
                rememberGeoJsonSource(
                    data =
                        GeoJsonData.Features(
                            FeatureCollection(
                                stops.mapIndexed { index, stop ->
                                    Feature(
                                        geometry = Point(position(stop)),
                                        id = "route-stop-$index",
                                        properties = mapOf(STOP_NUMBER to JsonPrimitive((index + 1).toString())),
                                    )
                                },
                            ),
                        ),
                )
            val originFeature = Feature(geometry = Point(position(origin)))
            val originSource =
                rememberGeoJsonSource(data = GeoJsonData.Features(FeatureCollection(listOf(originFeature))))

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
        // The panel is a picture: this layer takes the touches so the list beneath keeps scrolling.
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
    }
}

private fun RouteMapViewport.toCameraPosition(): CameraPosition =
    CameraPosition(target = Position(latitude = latitude, longitude = longitude), zoom = zoom)

private fun position(point: GeoPoint): Position = Position(latitude = point.latitude, longitude = point.longitude)

private val ROUTE_MAP_PADDING = 24.dp
private const val STOP_NUMBER = "n"
