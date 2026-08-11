package com.gallr.app.ui.tabs.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.GestureOptions
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_location_on
import gallr.composeapp.generated.resources.ic_my_location
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val FALLBACK_SEOUL_MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val QUIET_SEOUL_MAP_STYLE_RESOURCE =
    "files/map_data/openfreemap_positron_gallr.json"
internal const val MAP_MIN_ZOOM = 2.0
internal const val MAP_MAX_ZOOM = 20.0
private const val MAP_ZOOM_STEP = 1.0

internal data class ExhibitionMapPin(
    val position: Position,
    val exhibition: Exhibition,
)

private data class ScreenExhibitionMapPin(
    val pin: ExhibitionMapPin,
    val xPx: Float,
    val yPx: Float,
)

internal data class PinVisualCandidate(
    val id: String,
    val xPx: Float,
    val yPx: Float,
)

internal data class PinVisualGroup(
    val ids: List<String>,
    val xPx: Float,
    val yPx: Float,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun SeoulExhibitionMap(
    exhibitions: List<Exhibition>,
    savedExhibitionIds: Set<String>,
    language: AppLanguage,
    initialCenter: Coordinates?,
    onLocationRequest: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedOverlapGroup by remember { mutableStateOf<List<Exhibition>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val mapStyleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }
    val initialViewport = remember(initialCenter) { initialMapViewport(initialCenter) }
    val cameraState =
        rememberCameraState(
            firstPosition =
                CameraPosition(
                    target =
                        Position(
                            latitude = initialViewport.latitude,
                            longitude = initialViewport.longitude,
                        ),
                    zoom = initialViewport.zoom,
                ),
        )
    var hasCenteredOnUser by remember { mutableStateOf(initialCenter != null) }
    var locationFeedbackVersion by remember { mutableIntStateOf(if (initialCenter == null) 0 else 1) }
    var showLocationLabel by remember { mutableStateOf(false) }
    LaunchedEffect(locationFeedbackVersion) {
        if (locationFeedbackVersion > 0) {
            showLocationLabel = true
            delay(1_600)
            showLocationLabel = false
        }
    }
    LaunchedEffect(initialCenter) {
        val coordinates = initialCenter ?: return@LaunchedEffect
        if (!hasCenteredOnUser) {
            val viewport = initialMapViewport(coordinates)
            cameraState.position =
                CameraPosition(
                    target =
                        Position(
                            latitude = viewport.latitude,
                            longitude = viewport.longitude,
                        ),
                    zoom = viewport.zoom,
                )
            hasCenteredOnUser = true
            locationFeedbackVersion += 1
        }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            styleUri = mapStyleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE },
            cameraState = cameraState,
            zoomRange = MAP_MIN_ZOOM.toFloat()..MAP_MAX_ZOOM.toFloat(),
            pitchRange = 0f..0f,
            options =
                MapOptions(
                    ornamentOptions = OrnamentOptions.AllDisabled,
                    gestureOptions = GestureOptions.Standard,
                ),
        )

        val projection = cameraState.projection
        val cameraPosition = cameraState.position
        if (projection != null) {
            val density = LocalDensity.current
            val pinHorizontalExtentPx = with(density) { 52.dp.toPx() }
            val pinTopExtentPx = with(density) { 36.dp.toPx() }
            val pinBottomExtentPx = with(density) { 24.dp.toPx() }
            val screenPins =
                remember(exhibitions, projection, cameraPosition, density) {
                    exhibitionMapPins(exhibitions).mapNotNull { pin ->
                        val point = projection.screenLocationFromPosition(pin.position)
                        val xPx = with(density) { point.x.toPx() }
                        val yPx = with(density) { point.y.toPx() }
                        if (!isPinTargetFullyVisible(
                                xPx = xPx,
                                yPx = yPx,
                                viewportWidthPx = constraints.maxWidth.toFloat(),
                                viewportHeightPx = constraints.maxHeight.toFloat(),
                                horizontalExtentPx = pinHorizontalExtentPx,
                                topExtentPx = pinTopExtentPx,
                                bottomExtentPx = pinBottomExtentPx,
                            )
                        ) {
                            null
                        } else {
                            ScreenExhibitionMapPin(pin = pin, xPx = xPx, yPx = yPx)
                        }
                    }
                }
            val pinGroups =
                remember(screenPins, density) {
                    val candidates =
                        screenPins.map { screenPin ->
                            PinVisualCandidate(
                                id = screenPin.pin.exhibition.id,
                                xPx = screenPin.xPx,
                                yPx = screenPin.yPx,
                            )
                        }
                    groupNearlyCoincidentPins(
                        candidates = candidates,
                        proximityThresholdPx = with(density) { 16.dp.toPx() },
                    )
                }
            val pinsById = screenPins.associateBy { it.pin.exhibition.id }

            initialCenter?.let { coordinates ->
                val point =
                    projection.screenLocationFromPosition(
                        Position(
                            latitude = coordinates.latitude,
                            longitude = coordinates.longitude,
                        ),
                    )
                UserLocationIndicator(
                    language = language,
                    showLabel = showLocationLabel,
                    modifier =
                        Modifier.offset {
                            IntOffset(
                                x = with(density) { point.x.toPx() - 52.dp.toPx() }.roundToInt(),
                                y = with(density) { point.y.toPx() - 22.dp.toPx() }.roundToInt(),
                            )
                        },
                )
            }

            pinGroups
                .sortedBy { group -> group.ids.all(savedExhibitionIds::contains) }
                .forEach { group ->
                    val groupPins = group.ids.mapNotNull(pinsById::get)
                    if (groupPins.size == 1) {
                        val screenPin = groupPins.single()
                        ExhibitionLocationPin(
                            exhibition = screenPin.pin.exhibition,
                            saved = screenPin.pin.exhibition.id in savedExhibitionIds,
                            language = language,
                            onClick = { onExhibitionTap(screenPin.pin.exhibition) },
                            modifier =
                                Modifier.offset {
                                    IntOffset(
                                        x = (screenPin.xPx - 52.dp.toPx()).roundToInt(),
                                        y = (screenPin.yPx - 36.dp.toPx()).roundToInt(),
                                    )
                                },
                        )
                    } else if (groupPins.isNotEmpty()) {
                        ExhibitionOverlapMarker(
                            count = groupPins.size,
                            saved = groupPins.all { it.pin.exhibition.id in savedExhibitionIds },
                            language = language,
                            onClick = { selectedOverlapGroup = groupPins.map { it.pin.exhibition } },
                            modifier =
                                Modifier.offset {
                                    IntOffset(
                                        x = (group.xPx - 22.dp.toPx()).roundToInt(),
                                        y = (group.yPx - 36.dp.toPx()).roundToInt(),
                                    )
                                },
                        )
                    }
                }
        }

        SavedExhibitionLegend(
            language = language,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
        )

        MapControls(
            language = language,
            onZoomIn = {
                scope.launch {
                    cameraState.animateTo(
                        cameraState.position.copy(
                            zoom = steppedMapZoom(cameraState.position.zoom, direction = 1),
                        ),
                    )
                }
            },
            onZoomOut = {
                scope.launch {
                    cameraState.animateTo(
                        cameraState.position.copy(
                            zoom = steppedMapZoom(cameraState.position.zoom, direction = -1),
                        ),
                    )
                }
            },
            onRecenter = {
                val coordinates = initialCenter
                if (coordinates == null) {
                    onLocationRequest()
                } else {
                    scope.launch {
                        val viewport = initialMapViewport(coordinates)
                        cameraState.animateTo(
                            CameraPosition(
                                target =
                                    Position(
                                        latitude = viewport.latitude,
                                        longitude = viewport.longitude,
                                    ),
                                zoom = viewport.zoom,
                            ),
                        )
                        locationFeedbackVersion += 1
                    }
                }
            },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
        )

        Text(
            text = "© OpenFreeMap · © OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black.copy(alpha = 0.62f),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.White.copy(alpha = 0.82f))
                    .padding(horizontal = 4.dp),
        )
    }

    if (selectedOverlapGroup.isNotEmpty()) {
        ExhibitionOverlapSheet(
            exhibitions = selectedOverlapGroup,
            language = language,
            userCoordinates = initialCenter,
            onDismiss = { selectedOverlapGroup = emptyList() },
            onExhibitionTap = { exhibition ->
                selectedOverlapGroup = emptyList()
                onExhibitionTap(exhibition)
            },
        )
    }
}

@Composable
private fun MapControls(
    language: AppLanguage,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapZoomButton(
            label = "+",
            description = if (language == AppLanguage.KO) "지도 확대" else "ZOOM IN",
            onClick = onZoomIn,
        )
        MapZoomButton(
            label = "−",
            description = if (language == AppLanguage.KO) "지도 축소" else "ZOOM OUT",
            onClick = onZoomOut,
        )
        MapRecenterButton(
            language = language,
            onClick = onRecenter,
        )
    }
}

@Composable
private fun MapZoomButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .size(44.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun UserLocationIndicator(
    language: AppLanguage,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(104.dp)
                .semantics {
                    contentDescription = if (language == AppLanguage.KO) "내 위치" else "MY LOCATION"
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                drawCircle(color = Color.White, radius = 9.dp.toPx())
                drawCircle(
                    color = Color.Black,
                    radius = 8.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(color = Color.Black, radius = 3.dp.toPx())
            }
        }
        AnimatedVisibility(visible = showLabel, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = if (language == AppLanguage.KO) "내 위치" else "MY LOCATION",
                color = Color.Black,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SavedExhibitionLegend(
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            GallrPinGlyph(
                tint = GallrAccent.activeIndicator,
                size = 18.dp,
                dotRadius = 1.5.dp,
            )
        }
        Text(
            text = savedMapLegendLabel(language),
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MapRecenterButton(
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (language == AppLanguage.KO) "내 위치로 이동" else "RECENTER ON MY LOCATION"
    Surface(
        modifier =
            modifier
                .width(104.dp)
                .height(44.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_my_location),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (language == AppLanguage.KO) "내 위치" else "MY LOCATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ExhibitionLocationPin(
    exhibition: Exhibition,
    saved: Boolean,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = exhibition.localizedName(language)
    Column(
        modifier =
            modifier
                .width(104.dp)
                .heightIn(min = 44.dp)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = title
                    onClick {
                        onClick()
                        true
                    }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            GallrPinGlyph(
                tint = if (saved) GallrAccent.activeIndicator else Color.Black,
                size = 28.dp,
                dotRadius = 2.4.dp,
            )
        }
        Text(
            text = title,
            color = Color.Black,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun ExhibitionOverlapMarker(
    count: Int,
    saved: Boolean,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        when (language) {
            AppLanguage.KO -> "전시 ${count}개 그룹. 목록 열기"
            AppLanguage.EN -> "$count exhibition group. Open list"
        }
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                },
        contentAlignment = Alignment.Center,
    ) {
        StackedGallrPinGlyph(
            tint = if (saved) GallrAccent.activeIndicator else Color.Black,
        )
    }
}

@Composable
private fun StackedGallrPinGlyph(tint: Color) {
    GallrPinGlyphWithHalo(
        tint = tint,
        modifier = Modifier.offset(x = (-4).dp, y = (-4).dp),
    )
    GallrPinGlyphWithHalo(
        tint = tint,
        modifier = Modifier.offset(x = 4.dp, y = 4.dp),
    )
}

@Composable
private fun GallrPinGlyphWithHalo(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_location_on),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(),
        )
        GallrPinGlyph(
            tint = tint,
            size = 24.dp,
            dotRadius = 2.dp,
        )
    }
}

@Composable
private fun GallrPinGlyph(
    tint: Color,
    size: Dp,
    dotRadius: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_location_on),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White,
                radius = dotRadius.toPx(),
                center = center.copy(y = this.size.height * 0.37f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExhibitionOverlapSheet(
    exhibitions: List<Exhibition>,
    language: AppLanguage,
    userCoordinates: Coordinates?,
    onDismiss: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
) {
    val presentations =
        remember(exhibitions, userCoordinates) {
            sortOverlapExhibitionsByDistance(exhibitions, userCoordinates)
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = GallrSpacing.lg, bottom = GallrSpacing.xl),
        ) {
            Text(
                text = overlapSheetTitle(exhibitions.size, language),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier =
                    Modifier.padding(
                        horizontal = GallrSpacing.md,
                        vertical = GallrSpacing.sm,
                    ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
            ) {
                items(presentations, key = { it.exhibition.id }) { presentation ->
                    val exhibition = presentation.exhibition
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExhibitionTap(exhibition) }
                                .semantics { role = Role.Button }
                                .padding(
                                    horizontal = GallrSpacing.md,
                                    vertical = GallrSpacing.md,
                                ),
                    ) {
                        Text(
                            text = exhibition.localizedName(language),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = exhibition.localizedVenueName(language).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text =
                                    overlapMetadata(
                                        exhibition = exhibition,
                                        distanceKm = presentation.distanceKm,
                                        language = language,
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

internal fun isPinTargetFullyVisible(
    xPx: Float,
    yPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    horizontalExtentPx: Float = 52f,
    topExtentPx: Float = 36f,
    bottomExtentPx: Float = 24f,
): Boolean =
    xPx >= horizontalExtentPx &&
        xPx <= viewportWidthPx - horizontalExtentPx &&
        yPx >= topExtentPx &&
        yPx <= viewportHeightPx - bottomExtentPx

internal fun groupNearlyCoincidentPins(
    candidates: List<PinVisualCandidate>,
    proximityThresholdPx: Float,
): List<PinVisualGroup> {
    if (candidates.isEmpty()) return emptyList()

    val parents = IntArray(candidates.size) { it }

    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }

    fun union(
        first: Int,
        second: Int,
    ) {
        val firstRoot = root(first)
        val secondRoot = root(second)
        if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
    }

    candidates.indices.forEach { firstIndex ->
        for (secondIndex in firstIndex + 1 until candidates.size) {
            if (candidates[firstIndex].isNear(candidates[secondIndex], proximityThresholdPx)) {
                union(firstIndex, secondIndex)
            }
        }
    }

    return candidates.indices
        .groupBy(::root)
        .values
        .map { indices ->
            PinVisualGroup(
                ids = indices.map { candidates[it].id },
                xPx = indices.map { candidates[it].xPx }.average().toFloat(),
                yPx = indices.map { candidates[it].yPx }.average().toFloat(),
            )
        }
}

private fun PinVisualCandidate.isNear(
    other: PinVisualCandidate,
    proximityThresholdPx: Float,
): Boolean = hypot(xPx - other.xPx, yPx - other.yPx) <= proximityThresholdPx

internal fun steppedMapZoom(
    currentZoom: Double,
    direction: Int,
): Double =
    (currentZoom + MAP_ZOOM_STEP * direction.coerceIn(-1, 1))
        .coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)

internal fun exhibitionMapPins(exhibitions: List<Exhibition>): List<ExhibitionMapPin> =
    exhibitions.mapNotNull { exhibition ->
        val latitude = exhibition.latitude
        val longitude = exhibition.longitude
        if (
            latitude == null || longitude == null ||
            !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            null
        } else {
            ExhibitionMapPin(
                position = Position(latitude = latitude, longitude = longitude),
                exhibition = exhibition,
            )
        }
    }
