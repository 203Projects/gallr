package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import org.jetbrains.compose.resources.painterResource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.roundToInt

private const val FALLBACK_SEOUL_MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val QUIET_SEOUL_MAP_STYLE_RESOURCE =
    "files/map_data/openfreemap_positron_gallr.json"

internal data class DistrictShape(
    val nameKo: String,
    val points: List<Position>,
    val center: Position,
)

internal data class DistrictShapeSet(
    val districts: List<DistrictShape>,
    val west: Double,
    val east: Double,
    val south: Double,
    val north: Double,
) {
    fun project(position: Position): Pair<Float, Float> =
        (((position.longitude - west) / (east - west)).toFloat()) to
            (((north - position.latitude) / (north - south)).toFloat())
}

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
    val labelWidthPx: Float,
    val labelHeightPx: Float,
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
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf<DistrictShapeSet?>(null) }
    var selectedOverlapGroup by remember { mutableStateOf<List<Exhibition>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val mapStyleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }
    LaunchedEffect(Unit) {
        bounds = parseDistrictShapes(
            Res.readBytes("files/map_data/seoul_districts.geojson").decodeToString(),
        )
    }
    val initialViewport = remember(initialCenter) { initialMapViewport(initialCenter) }
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(
                latitude = initialViewport.latitude,
                longitude = initialViewport.longitude,
            ),
            zoom = initialViewport.zoom,
        ),
    )
    var hasCenteredOnUser by remember { mutableStateOf(initialCenter != null) }
    var hasAppliedNearbyFrame by remember { mutableStateOf(false) }
    var locationFeedbackVersion by remember { mutableIntStateOf(if (initialCenter == null) 0 else 1) }
    var showLocationLabel by remember { mutableStateOf(false) }
    LaunchedEffect(locationFeedbackVersion) {
        if (locationFeedbackVersion > 0) {
            showLocationLabel = true
            delay(1_600)
            showLocationLabel = false
        }
    }
    LaunchedEffect(initialCenter, exhibitions) {
        val coordinates = initialCenter ?: return@LaunchedEffect
        if (!hasCenteredOnUser) {
            val viewport = initialMapViewport(coordinates)
            cameraState.position = CameraPosition(
                target = Position(
                    latitude = viewport.latitude,
                    longitude = viewport.longitude,
                ),
                zoom = viewport.zoom,
            )
            hasCenteredOnUser = true
            locationFeedbackVersion += 1
        }
        if (!hasAppliedNearbyFrame && exhibitions.isNotEmpty()) {
            adaptiveNearbyViewport(coordinates, exhibitions)?.let { viewport ->
                cameraState.animateTo(viewport.toBoundingBox(), padding = PaddingValues(48.dp))
            }
            hasAppliedNearbyFrame = true
        }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            styleUri = mapStyleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE },
            cameraState = cameraState,
            zoomRange = 8.75f..16f,
            pitchRange = 0f..0f,
            options = MapOptions(
                ornamentOptions = OrnamentOptions.AllDisabled,
                gestureOptions = GestureOptions.Standard,
            ),
        )

        val mapBounds = bounds
        val projection = cameraState.projection
        val cameraPosition = cameraState.position
        if (mapBounds != null && projection != null) {
            val density = LocalDensity.current
            val labelStyle = MaterialTheme.typography.labelMedium
            val textMeasurer = rememberTextMeasurer()
            val pinHorizontalExtentPx = with(density) { 52.dp.toPx() }
            val pinTopExtentPx = with(density) { 36.dp.toPx() }
            val pinBottomExtentPx = with(density) { 24.dp.toPx() }
            val screenPins = remember(exhibitions, mapBounds, projection, cameraPosition, density) {
                val projected = exhibitionMapPins(exhibitions, mapBounds).mapNotNull { pin ->
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
                spreadCoincidentPins(projected, with(density) { 24.dp.toPx() })
            }
            val pinGroups = remember(screenPins, language, labelStyle, density) {
                val maxLabelWidthPx = with(density) { 100.dp.roundToPx() }
                val candidates = screenPins.map { screenPin ->
                    val title = screenPin.pin.exhibition.localizedName(language)
                    val measured = textMeasurer.measure(
                        text = AnnotatedString(title),
                        style = labelStyle,
                        maxLines = 1,
                        softWrap = false,
                        constraints = Constraints(maxWidth = maxLabelWidthPx),
                    )
                    PinVisualCandidate(
                        id = screenPin.pin.exhibition.id,
                        xPx = screenPin.xPx,
                        yPx = screenPin.yPx,
                        labelWidthPx = measured.size.width.toFloat(),
                        labelHeightPx = measured.size.height.toFloat(),
                    )
                }
                groupPinsWithUnreadableTitles(candidates)
            }
            val pinsById = screenPins.associateBy { it.pin.exhibition.id }

            initialCenter?.let { coordinates ->
                val point = projection.screenLocationFromPosition(
                    Position(
                        latitude = coordinates.latitude,
                        longitude = coordinates.longitude,
                    ),
                )
                UserLocationIndicator(
                    language = language,
                    showLabel = showLocationLabel,
                    modifier = Modifier.offset {
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
                            modifier = Modifier.offset {
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
                            modifier = Modifier.offset {
                                IntOffset(
                                    x = (group.xPx - 22.dp.toPx()).roundToInt(),
                                    y = (group.yPx - 36.dp.toPx()).roundToInt(),
                                )
                            },
                        )
                    }
            }

            val hasVisibleExhibition = screenPins.any { pin ->
                pin.xPx in 0f..constraints.maxWidth.toFloat() &&
                    pin.yPx in 0f..constraints.maxHeight.toFloat()
            }
            if (!hasVisibleExhibition && exhibitionMapPins(exhibitions, mapBounds).isNotEmpty()) {
                SeoulOverviewAction(
                    language = language,
                    onClick = {
                        scope.launch {
                            cameraState.animateTo(
                                mapBounds.toBoundingBox(),
                                padding = PaddingValues(24.dp),
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }

        SavedExhibitionLegend(
            language = language,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        initialCenter?.let { coordinates ->
            MapRecenterButton(
                language = language,
                onClick = {
                    scope.launch {
                        val nearbyViewport = adaptiveNearbyViewport(coordinates, exhibitions)
                        if (nearbyViewport == null) {
                            val viewport = initialMapViewport(coordinates)
                            cameraState.animateTo(
                                CameraPosition(
                                    target = Position(
                                        latitude = viewport.latitude,
                                        longitude = viewport.longitude,
                                    ),
                                    zoom = viewport.zoom,
                                ),
                            )
                        } else {
                            cameraState.animateTo(
                                nearbyViewport.toBoundingBox(),
                                padding = PaddingValues(48.dp),
                            )
                        }
                        locationFeedbackVersion += 1
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            )
        }

        Text(
            text = "© OpenFreeMap · © OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black.copy(alpha = 0.62f),
            modifier = Modifier
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
private fun UserLocationIndicator(
    language: AppLanguage,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
            Icon(
                painter = painterResource(Res.drawable.ic_location_on),
                contentDescription = null,
                tint = GallrAccent.activeIndicator,
                modifier = Modifier.size(18.dp),
            )
            Canvas(Modifier.size(18.dp)) {
                drawCircle(
                    color = Color.White,
                    radius = 1.5.dp.toPx(),
                    center = center.copy(y = size.height * 0.37f),
                )
            }
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
        modifier = modifier
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
            Icon(
                painter = painterResource(Res.drawable.ic_my_location),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SeoulOverviewAction(
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.onBackground,
        contentColor = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = GallrSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (language == AppLanguage.KO) "서울 전체 보기" else "VIEW ALL SEOUL",
                style = MaterialTheme.typography.labelLarge,
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
        modifier = modifier
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
            Icon(
                painter = painterResource(Res.drawable.ic_location_on),
                contentDescription = null,
                tint = if (saved) GallrAccent.activeIndicator else Color.Black,
                modifier = Modifier.size(28.dp),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = Color.White,
                    radius = 2.4.dp.toPx(),
                    center = center.copy(y = size.height * 0.37f),
                )
            }
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
    val description = when (language) {
        AppLanguage.KO -> "전시 ${count}개 그룹. 목록 열기"
        AppLanguage.EN -> "$count exhibition group. Open list"
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (saved) GallrAccent.activeIndicator else Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
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
    val presentations = remember(exhibitions, userCoordinates) {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GallrSpacing.lg, bottom = GallrSpacing.xl),
        ) {
            Text(
                text = overlapSheetTitle(exhibitions.size, language),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(
                    horizontal = GallrSpacing.md,
                    vertical = GallrSpacing.sm,
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                items(presentations, key = { it.exhibition.id }) { presentation ->
                    val exhibition = presentation.exhibition
                    Column(
                        modifier = Modifier
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
                                text = overlapMetadata(
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

private fun AdaptiveNearbyViewport.toBoundingBox(): BoundingBox =
    BoundingBox(west, south, east, north)

private fun DistrictShapeSet.toBoundingBox(): BoundingBox =
    BoundingBox(west, south, east, north)

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

private fun spreadCoincidentPins(
    pins: List<ScreenExhibitionMapPin>,
    spacingPx: Float,
): List<ScreenExhibitionMapPin> = pins
    .groupBy { pin ->
        (pin.pin.position.latitude * 100_000).roundToInt() to
            (pin.pin.position.longitude * 100_000).roundToInt()
    }
    .values
    .flatMap { group ->
        group.mapIndexed { index, pin ->
            pin.copy(xPx = pin.xPx + (index - (group.lastIndex / 2f)) * spacingPx)
        }
    }

internal fun groupPinsWithUnreadableTitles(
    candidates: List<PinVisualCandidate>,
    markerHalfWidthPx: Float = 14f,
    markerTopOffsetPx: Float = 28f,
    labelTopOffsetPx: Float = 8f,
    collisionPaddingPx: Float = 4f,
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

    fun union(first: Int, second: Int) {
        val firstRoot = root(first)
        val secondRoot = root(second)
        if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
    }

    candidates.indices.forEach { firstIndex ->
        for (secondIndex in firstIndex + 1 until candidates.size) {
            if (
                candidates[firstIndex].visuallyCollidesWith(
                    other = candidates[secondIndex],
                    markerHalfWidthPx = markerHalfWidthPx,
                    markerTopOffsetPx = markerTopOffsetPx,
                    labelTopOffsetPx = labelTopOffsetPx,
                    collisionPaddingPx = collisionPaddingPx,
                )
            ) {
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

private fun PinVisualCandidate.visuallyCollidesWith(
    other: PinVisualCandidate,
    markerHalfWidthPx: Float,
    markerTopOffsetPx: Float,
    labelTopOffsetPx: Float,
    collisionPaddingPx: Float,
): Boolean {
    val firstLabel = FloatBounds(
        left = xPx - labelWidthPx / 2f,
        top = yPx + labelTopOffsetPx,
        right = xPx + labelWidthPx / 2f,
        bottom = yPx + labelTopOffsetPx + labelHeightPx,
    )
    val secondLabel = FloatBounds(
        left = other.xPx - other.labelWidthPx / 2f,
        top = other.yPx + labelTopOffsetPx,
        right = other.xPx + other.labelWidthPx / 2f,
        bottom = other.yPx + labelTopOffsetPx + other.labelHeightPx,
    )
    val firstMarker = FloatBounds(
        left = xPx - markerHalfWidthPx,
        top = yPx - markerTopOffsetPx,
        right = xPx + markerHalfWidthPx,
        bottom = yPx,
    )
    val secondMarker = FloatBounds(
        left = other.xPx - markerHalfWidthPx,
        top = other.yPx - markerTopOffsetPx,
        right = other.xPx + markerHalfWidthPx,
        bottom = other.yPx,
    )
    return firstLabel.intersects(secondLabel, collisionPaddingPx) ||
        firstLabel.intersects(secondMarker, collisionPaddingPx) ||
        secondLabel.intersects(firstMarker, collisionPaddingPx)
}

private data class FloatBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun intersects(other: FloatBounds, paddingPx: Float): Boolean =
        left - paddingPx < other.right && right + paddingPx > other.left &&
            top - paddingPx < other.bottom && bottom + paddingPx > other.top
}

internal fun parseDistrictShapes(geoJson: String): DistrictShapeSet {
    val features = Json.parseToJsonElement(geoJson)
        .jsonObject.getValue("features").jsonArray
    val districts = features.map { featureElement ->
        val feature = featureElement.jsonObject
        val name = feature.getValue("properties").jsonObject
            .getValue("name").jsonPrimitive.content
        val ring = feature.getValue("geometry").jsonObject
            .getValue("coordinates").jsonArray.first().jsonArray
        val points = ring.map { coordinate ->
            val values = coordinate.jsonArray
            Position(
                longitude = values[0].jsonPrimitive.double,
                latitude = values[1].jsonPrimitive.double,
            )
        }
        DistrictShape(
            nameKo = name,
            points = points,
            center = Position(
                longitude = (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2,
                latitude = (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2,
            ),
        )
    }
    return DistrictShapeSet(
        districts = districts,
        west = districts.minOf { district -> district.points.minOf { it.longitude } },
        east = districts.maxOf { district -> district.points.maxOf { it.longitude } },
        south = districts.minOf { district -> district.points.minOf { it.latitude } },
        north = districts.maxOf { district -> district.points.maxOf { it.latitude } },
    )
}

internal fun exhibitionMapPins(
    exhibitions: List<Exhibition>,
    bounds: DistrictShapeSet,
): List<ExhibitionMapPin> = exhibitions.mapNotNull { exhibition ->
    val latitude = exhibition.latitude
    val longitude = exhibition.longitude
    if (
        latitude == null || longitude == null ||
        !latitude.isFinite() || !longitude.isFinite() ||
        latitude !in bounds.south..bounds.north || longitude !in bounds.west..bounds.east
    ) {
        null
    } else {
        ExhibitionMapPin(
            position = Position(latitude = latitude, longitude = longitude),
            exhibition = exhibition,
        )
    }
}
