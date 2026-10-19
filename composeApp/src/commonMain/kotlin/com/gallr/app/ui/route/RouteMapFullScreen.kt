package com.gallr.app.ui.route

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.PlatformBackHandler
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.ExhibitionRouteEstimate
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.spatialk.geojson.Position

/**
 * The route on a full screen (spec 093 US2): the panel's pins and line on a map the reader can zoom and pan, north
 * always up, with "fit route" and close. It is drawn over the route screen rather than navigated to, so closing it
 * leaves that screen exactly as it was (FR-015).
 */
@Composable
internal fun RouteMapFullScreen(
    origin: GeoPoint,
    stops: List<RouteMapStop>,
    legs: List<RouteMapLeg>,
    language: AppLanguage,
    onClose: () -> Unit,
    distanceMeters: Int? = null,
) {
    PlatformBackHandler(onClose)
    val uriHandler = LocalUriHandler.current
    val line =
        remember(origin, stops, legs) {
            routeLinePoints(origin, stops.map(RouteMapStop::point), legs.map(RouteMapLeg::geometry))
        }
    val lines = remember(origin, stops, legs) { routeLegLines(origin, stops.map(RouteMapStop::point), legs) }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val viewport =
            remember(line, maxWidth, maxHeight) {
                routeMapViewport(
                    points = line,
                    widthDp = maxWidth.value,
                    heightDp = maxHeight.value - FULL_SCREEN_PIN_HEADROOM.value,
                    paddingDp = FULL_SCREEN_PADDING.value,
                    minZoom = FULL_SCREEN_MIN_ZOOM,
                )
            }
        val mapState = rememberRouteMapState(origin, stops, lines, viewport)
        val scope = rememberCoroutineScope()
        val reduceMotion = isReduceMotionOrScreenReaderActive()
        RouteMapFullScreenChrome(
            language = language,
            description = routeMapLabel(stops.size, language, distanceMeters),
            credit = routeMapCredit(routers = lines.routers),
            onFitRoute = {
                scope.launch {
                    if (reduceMotion) {
                        mapState.setCameraPosition(viewport.toCameraPosition())
                    } else {
                        mapState.animateCamera(
                            CameraUpdate(
                                center = Position(latitude = viewport.latitude, longitude = viewport.longitude),
                                zoom = viewport.zoom,
                            ),
                        )
                    }
                }
            },
            onClose = onClose,
            onCredit = { uriHandler.openUri(ROUTE_MAP_COPYRIGHT_URL) },
        ) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                state = mapState,
                cameraConstraints = FULL_SCREEN_CAMERA_CONSTRAINTS,
                interactions = FULL_SCREEN_INTERACTIONS,
                // gallr draws its own controls and credit over the map.
                overlay = {},
            )
        }
    }
}

/** The planner's route on a full screen. */
@Composable
internal fun RouteMapFullScreen(
    origin: GeoPoint,
    route: ExhibitionRouteEstimate,
    language: AppLanguage,
    onClose: () -> Unit,
) {
    val stops = remember(route) { routeMapStops(route) }
    val legs = remember(route) { routeMapLegs(route) }
    RouteMapFullScreen(
        origin = origin,
        stops = stops,
        legs = legs,
        language = language,
        onClose = onClose,
        distanceMeters = route.totalDistanceMeters,
    )
}

/**
 * Everything on the full-screen map except the map itself: close at the top left, fit route at the bottom right and
 * the credit at the bottom left, around [map]. The whole is described for screen readers by [description].
 */
@Composable
internal fun RouteMapFullScreenChrome(
    language: AppLanguage,
    description: String,
    credit: String,
    onFitRoute: () -> Unit,
    onClose: () -> Unit,
    onCredit: () -> Unit,
    map: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) {
        map()
        RouteMapControlButton(
            description = routeMapCloseLabel(language),
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(GallrSpacing.md),
        ) {
            Canvas(Modifier.size(GLYPH_SIZE)) {
                val stroke = 2.dp.toPx()
                drawLine(Color.Black, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Square)
                drawLine(Color.Black, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Square)
            }
        }
        RouteMapControlButton(
            description = routeMapFitLabel(language),
            onClick = onFitRoute,
            modifier = Modifier.align(Alignment.BottomEnd).padding(GallrSpacing.md),
        ) { CornerBracketsGlyph() }
        RouteMapCreditText(
            text = credit,
            modifier = Modifier.align(Alignment.BottomStart).clickable(role = Role.Button, onClick = onCredit),
        )
    }
}

/** The panel's control that opens the full-screen map: a 44dp outlined square with corner brackets. */
@Composable
internal fun RouteMapExpandButton(
    language: AppLanguage,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RouteMapControlButton(
        description = routeMapExpandLabel(language),
        onClick = onExpand,
        modifier = modifier,
    ) { CornerBracketsGlyph() }
}

/** Map credits on a translucent white strip, like the Map tab's (research R7). */
@Composable
internal fun RouteMapCreditText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black.copy(alpha = CREDIT_TEXT_ALPHA),
        modifier =
            modifier
                .background(Color.White.copy(alpha = CREDIT_BACKGROUND_ALPHA))
                .padding(horizontal = 4.dp),
    )
}

@Composable
private fun RouteMapControlButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .size(CONTROL_SIZE)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                },
        shape = RectangleShape,
        color = Color.White,
        border = BorderStroke(1.dp, Color.Black),
    ) {
        Box(contentAlignment = Alignment.Center) { glyph() }
    }
}

/** Four corner brackets: "show it all", used for expand and for fit route. */
@Composable
private fun CornerBracketsGlyph() {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val stroke = 2.dp.toPx()
        val arm = size.width * BRACKET_ARM
        val w = size.width
        val h = size.height
        listOf(
            Offset(0f, 0f) to listOf(Offset(arm, 0f), Offset(0f, arm)),
            Offset(w, 0f) to listOf(Offset(w - arm, 0f), Offset(w, arm)),
            Offset(0f, h) to listOf(Offset(arm, h), Offset(0f, h - arm)),
            Offset(w, h) to listOf(Offset(w - arm, h), Offset(w, h - arm)),
        ).forEach { (corner, ends) ->
            ends.forEach { end -> drawLine(Color.Black, corner, end, stroke, StrokeCap.Square) }
        }
    }
}

private val FULL_SCREEN_INTERACTIONS =
    MapInteractions(MapInteractions.Standard) {
        camera {
            // North stays up: a route reads by its streets, and a turned map loses the reader (FR-013).
            rotate { enabled = false }
        }
    }

private val FULL_SCREEN_CAMERA_CONSTRAINTS =
    CameraConstraints {
        minZoom = FULL_SCREEN_MIN_ZOOM
        maxZoom = FULL_SCREEN_MAX_ZOOM
        maxPitch = 0.0
    }

private const val FULL_SCREEN_MIN_ZOOM = 10.0
private const val FULL_SCREEN_MAX_ZOOM = 18.0
private val FULL_SCREEN_PADDING = 48.dp
private val FULL_SCREEN_PIN_HEADROOM = 32.dp
private val CONTROL_SIZE = 44.dp
private val GLYPH_SIZE = 16.dp
private const val BRACKET_ARM = 0.4f
private const val CREDIT_TEXT_ALPHA = 0.62f
private const val CREDIT_BACKGROUND_ALPHA = 0.82f
