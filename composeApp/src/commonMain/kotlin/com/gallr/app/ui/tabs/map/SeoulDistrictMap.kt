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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import dev.sargunv.maplibrecompose.compose.ClickResult
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.CircleLayer
import dev.sargunv.maplibrecompose.compose.layer.SymbolLayer
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.GestureOptions
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.expressions.dsl.asBoolean
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.image
import dev.sargunv.maplibrecompose.expressions.dsl.not
import dev.sargunv.maplibrecompose.expressions.dsl.offset
import dev.sargunv.maplibrecompose.expressions.value.SymbolAnchor
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_location_on
import gallr.composeapp.generated.resources.ic_my_location
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.FeatureCollection
import io.github.dellisd.spatialk.geojson.Point
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
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
private const val PIN_FEATURE_TITLE = "title"
private const val PIN_FEATURE_COUNT = "count"
private const val PIN_FEATURE_IS_GROUP = "is_group"
private const val PIN_FONT = "Noto Sans Regular"
private val PIN_GROUPING_THRESHOLD = 16.dp

internal data class ExhibitionMapPin(
    val position: Position,
    val exhibition: Exhibition,
)

internal data class ExhibitionMapPinGroup(
    val position: Position,
    val pins: List<ExhibitionMapPin>,
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

private data class ScreenExhibitionMapPinGroup(
    val group: ExhibitionMapPinGroup,
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
    onBuildRoute: (GeoPoint) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedOverlapGroup by remember { mutableStateOf<List<Exhibition>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val mapStyleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }
    val initialViewport = remember { initialMapViewport(initialCenter) }
    val mapPins = remember(exhibitions) { exhibitionMapPins(exhibitions) }
    val exactPinGroups = remember(mapPins) { groupPinsByExactPosition(mapPins) }
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
        val projection = cameraState.projection
        val cameraPosition = cameraState.position
        val density = LocalDensity.current
        val screenPinGroups =
            if (projection == null) {
                emptyList()
            } else {
                remember(mapPins, projection, cameraPosition, density) {
                    val projectedPins =
                        mapPins.map { pin ->
                            val point = projection.screenLocationFromPosition(pin.position)
                            PinVisualCandidate(
                                id = pin.exhibition.id,
                                xPx = with(density) { point.x.toPx() },
                                yPx = with(density) { point.y.toPx() },
                            )
                        }
                    val pinsById = mapPins.associateBy { pin -> pin.exhibition.id }
                    val proximityThresholdPx = with(density) { PIN_GROUPING_THRESHOLD.toPx() }

                    groupNearlyCoincidentPins(
                        candidates = projectedPins,
                        proximityThresholdPx = proximityThresholdPx,
                    ).mapNotNull { visualGroup ->
                        val groupedPins = visualGroup.ids.mapNotNull(pinsById::get)
                        if (groupedPins.isEmpty()) {
                            null
                        } else {
                            val position =
                                if (groupedPins.size == 1) {
                                    groupedPins.single().position
                                } else {
                                    projection.positionFromScreenLocation(
                                        DpOffset(
                                            x = with(density) { visualGroup.xPx.toDp() },
                                            y = with(density) { visualGroup.yPx.toDp() },
                                        ),
                                    )
                                }
                            ScreenExhibitionMapPinGroup(
                                group =
                                    ExhibitionMapPinGroup(
                                        position = position,
                                        pins = groupedPins,
                                    ),
                                xPx = visualGroup.xPx,
                                yPx = visualGroup.yPx,
                            )
                        }
                    }
                }
            }
        val pinGroups =
            if (projection == null) {
                exactPinGroups
            } else {
                screenPinGroups.map(ScreenExhibitionMapPinGroup::group)
            }

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(mapStyleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE }),
            cameraState = cameraState,
            zoomRange = MAP_MIN_ZOOM.toFloat()..MAP_MAX_ZOOM.toFloat(),
            pitchRange = 0f..0f,
            options =
                MapOptions(
                    ornamentOptions = OrnamentOptions.AllDisabled,
                    gestureOptions = GestureOptions.Standard,
                ),
        ) {
            ExhibitionPinLayers(
                groups = pinGroups,
                savedExhibitionIds = savedExhibitionIds,
                language = language,
                onGroupClick = { group ->
                    if (group.pins.size == 1) {
                        onExhibitionTap(group.pins.single().exhibition)
                    } else {
                        selectedOverlapGroup = group.pins.map { it.exhibition }
                    }
                },
            )
        }

        if (projection != null) {
            val pinHorizontalExtentPx = with(density) { 52.dp.toPx() }
            val pinTopExtentPx = with(density) { 36.dp.toPx() }
            val pinBottomExtentPx = with(density) { 24.dp.toPx() }
            val visibleScreenPinGroups =
                remember(screenPinGroups, constraints, density) {
                    screenPinGroups.filter { screenGroup ->
                        isPinTargetFullyVisible(
                            xPx = screenGroup.xPx,
                            yPx = screenGroup.yPx,
                            viewportWidthPx = constraints.maxWidth.toFloat(),
                            viewportHeightPx = constraints.maxHeight.toFloat(),
                            horizontalExtentPx = pinHorizontalExtentPx,
                            topExtentPx = pinTopExtentPx,
                            bottomExtentPx = pinBottomExtentPx,
                        )
                    }
                }

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

            visibleScreenPinGroups.forEach { screenGroup ->
                AccessibleExhibitionPinTarget(
                    group = screenGroup.group,
                    language = language,
                    onClick = {
                        if (screenGroup.group.pins.size == 1) {
                            val exhibition =
                                screenGroup.group.pins
                                    .single()
                                    .exhibition
                            onExhibitionTap(exhibition)
                        } else {
                            selectedOverlapGroup = screenGroup.group.pins.map { it.exhibition }
                        }
                    },
                    modifier =
                        Modifier.offset {
                            val width = if (screenGroup.group.pins.size == 1) 104.dp else 44.dp
                            IntOffset(
                                x = (screenGroup.xPx - width.toPx() / 2f).roundToInt(),
                                y = (screenGroup.yPx - 36.dp.toPx()).roundToInt(),
                            )
                        },
                )
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
                onLocationRequest()
                if (coordinates != null) {
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

        MapRouteButton(
            language = language,
            onClick = {
                mapRouteOrigin(cameraState.position.target)?.let(onBuildRoute)
            },
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 32.dp),
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
private fun ExhibitionPinLayers(
    groups: List<ExhibitionMapPinGroup>,
    savedExhibitionIds: Set<String>,
    language: AppLanguage,
    onGroupClick: (ExhibitionMapPinGroup) -> Unit,
) {
    val groupsByKey = remember(groups) { groups.associateBy(::pinGroupKey) }
    val savedGroups =
        remember(groups, savedExhibitionIds) {
            groups.filter { group -> groupContainsSavedExhibition(group, savedExhibitionIds) }
        }
    val unsavedGroups =
        remember(groups, savedExhibitionIds) {
            groups.filterNot { group -> groupContainsSavedExhibition(group, savedExhibitionIds) }
        }
    val onFeatureClick: (List<Feature>) -> ClickResult = { features ->
        val group = features.firstNotNullOfOrNull { feature -> feature.id?.let(groupsByKey::get) }
        if (group == null) {
            ClickResult.Pass
        } else {
            onGroupClick(group)
            ClickResult.Consume
        }
    }

    val savedSource =
        rememberGeoJsonSource(
            data = GeoJsonData.Features(pinFeatureCollection(savedGroups, language)),
        )
    val unsavedSource =
        rememberGeoJsonSource(
            data = GeoJsonData.Features(pinFeatureCollection(unsavedGroups, language)),
        )

    ExhibitionPinBaseLayers(
        idPrefix = "gallr-unsaved",
        source = unsavedSource,
        tint = Color.Black,
        onFeatureClick = onFeatureClick,
    )
    ExhibitionPinBaseLayers(
        idPrefix = "gallr-saved",
        source = savedSource,
        tint = GallrAccent.activeIndicator,
        onFeatureClick = onFeatureClick,
    )
    ExhibitionPinGroupLayers(
        idPrefix = "gallr-unsaved",
        source = unsavedSource,
        frontTint = Color.Black,
        onFeatureClick = onFeatureClick,
    )
    ExhibitionPinGroupLayers(
        idPrefix = "gallr-saved",
        source = savedSource,
        frontTint = GallrAccent.activeIndicator,
        onFeatureClick = onFeatureClick,
    )
    ExhibitionPinCountLayers(
        idPrefix = "gallr-unsaved",
        source = unsavedSource,
        onFeatureClick = onFeatureClick,
    )
    ExhibitionPinCountLayers(
        idPrefix = "gallr-saved",
        source = savedSource,
        onFeatureClick = onFeatureClick,
    )
}

@Composable
private fun ExhibitionPinBaseLayers(
    idPrefix: String,
    source: dev.sargunv.maplibrecompose.core.source.Source,
    tint: Color,
    onFeatureClick: (List<Feature>) -> ClickResult,
) {
    val pinPainter = painterResource(Res.drawable.ic_location_on)
    val isGroup = feature.get(PIN_FEATURE_IS_GROUP).asBoolean()

    SymbolLayer(
        id = "$idPrefix-pin",
        source = source,
        filter = !isGroup,
        iconImage = image(pinPainter, size = DpSize(28.dp, 28.dp), drawAsSdf = true),
        iconColor = const(tint),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
    CircleLayer(
        id = "$idPrefix-pin-dot",
        source = source,
        filter = !isGroup,
        translate = offset(0.dp, (-17.5).dp),
        color = const(Color.White),
        radius = const(2.4.dp),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "$idPrefix-title",
        source = source,
        filter = !isGroup,
        textField = feature.get(PIN_FEATURE_TITLE).asString(),
        textFont = const(listOf(PIN_FONT)),
        textSize = const(12.sp),
        textColor = const(Color.Black),
        textHaloColor = const(Color.White),
        textHaloWidth = const(1.dp),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = offset(0.em, 0.35.em),
        textMaxWidth = const(0.em),
        textAllowOverlap = const(false),
        textIgnorePlacement = const(false),
        textPadding = const(4.dp),
        onClick = onFeatureClick,
    )
}

@Composable
private fun ExhibitionPinGroupLayers(
    idPrefix: String,
    source: dev.sargunv.maplibrecompose.core.source.Source,
    frontTint: Color,
    onFeatureClick: (List<Feature>) -> ClickResult,
) {
    val pinPainter = painterResource(Res.drawable.ic_location_on)
    val isGroup = feature.get(PIN_FEATURE_IS_GROUP).asBoolean()

    SymbolLayer(
        id = "$idPrefix-group-back-halo",
        source = source,
        filter = isGroup,
        iconImage = image(pinPainter, size = DpSize(28.dp, 28.dp), drawAsSdf = true),
        iconColor = const(Color.White),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconTranslate = offset((-4).dp, (-4).dp),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "$idPrefix-group-back",
        source = source,
        filter = isGroup,
        iconImage = image(pinPainter, size = DpSize(24.dp, 24.dp), drawAsSdf = true),
        iconColor = const(Color.Black),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconTranslate = offset((-4).dp, (-6).dp),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
    CircleLayer(
        id = "$idPrefix-group-back-dot",
        source = source,
        filter = isGroup,
        translate = offset((-4).dp, (-21.12).dp),
        color = const(Color.White),
        radius = const(2.5.dp),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "$idPrefix-group-front-halo",
        source = source,
        filter = isGroup,
        iconImage = image(pinPainter, size = DpSize(28.dp, 28.dp), drawAsSdf = true),
        iconColor = const(Color.White),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconTranslate = offset(4.dp, 5.dp),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "$idPrefix-group-front",
        source = source,
        filter = isGroup,
        iconImage = image(pinPainter, size = DpSize(24.dp, 24.dp), drawAsSdf = true),
        iconColor = const(frontTint),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconTranslate = offset(4.dp, 3.dp),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
    CircleLayer(
        id = "$idPrefix-group-front-dot",
        source = source,
        filter = isGroup,
        translate = offset(4.dp, (-12.12).dp),
        color = const(Color.White),
        radius = const(2.5.dp),
        onClick = onFeatureClick,
    )
}

@Composable
private fun ExhibitionPinCountLayers(
    idPrefix: String,
    source: dev.sargunv.maplibrecompose.core.source.Source,
    onFeatureClick: (List<Feature>) -> ClickResult,
) {
    val isGroup = feature.get(PIN_FEATURE_IS_GROUP).asBoolean()

    CircleLayer(
        id = "$idPrefix-count-badge",
        source = source,
        filter = isGroup,
        translate = offset(12.dp, (-19).dp),
        color = const(Color.White),
        radius = const(4.5.dp),
        strokeColor = const(Color.Black),
        strokeWidth = const(0.75.dp),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "$idPrefix-count",
        source = source,
        filter = isGroup,
        textField = feature.get(PIN_FEATURE_COUNT).asString(),
        textFont = const(listOf(PIN_FONT)),
        textSize = const(8.sp),
        textColor = const(Color.Black),
        textTranslate = offset(12.dp, (-19).dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
}

private fun pinFeatureCollection(
    groups: List<ExhibitionMapPinGroup>,
    language: AppLanguage,
): FeatureCollection =
    FeatureCollection(
        groups.map { group ->
            Feature(
                geometry = Point(group.position),
                id = pinGroupKey(group),
                properties =
                    mapOf(
                        PIN_FEATURE_TITLE to
                            JsonPrimitive(
                                group.pins
                                    .singleOrNull()
                                    ?.exhibition
                                    ?.localizedName(language)
                                    ?.let(::compactMapPinTitle)
                                    .orEmpty(),
                            ),
                        PIN_FEATURE_COUNT to JsonPrimitive(group.pins.size.toString()),
                        PIN_FEATURE_IS_GROUP to JsonPrimitive(group.pins.size > 1),
                    ),
            )
        },
    )

private fun pinGroupKey(group: ExhibitionMapPinGroup): String =
    group.pins.joinToString(separator = "|") { pin ->
        val id = pin.exhibition.id
        "${id.length}:$id"
    }

internal fun compactMapPinTitle(
    title: String,
    maxDisplayUnits: Int = 16,
): String {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isEmpty() || maxDisplayUnits <= 0) return ""

    val titleDisplayUnits = trimmedTitle.sumOf(Char::mapPinDisplayUnits)
    val compactTitle =
        if (titleDisplayUnits <= maxDisplayUnits) {
            trimmedTitle
        } else {
            val contentBudget = (maxDisplayUnits - 1).coerceAtLeast(0)
            var usedDisplayUnits = 0
            buildString {
                for (character in trimmedTitle) {
                    val characterUnits = character.mapPinDisplayUnits()
                    if (usedDisplayUnits + characterUnits > contentBudget) break
                    append(character)
                    usedDisplayUnits += characterUnits
                }
            }.trimEnd() + "…"
        }

    return compactTitle.replace(' ', '\u00A0')
}

private fun Char.mapPinDisplayUnits(): Int =
    if (
        this in '\u1100'..'\u11FF' ||
        this in '\u2E80'..'\u9FFF' ||
        this in '\uAC00'..'\uD7A3' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\uFF01'..'\uFF60'
    ) {
        2
    } else {
        1
    }

@Composable
private fun AccessibleExhibitionPinTarget(
    group: ExhibitionMapPinGroup,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        if (group.pins.size == 1) {
            group.pins
                .single()
                .exhibition
                .localizedName(language)
        } else {
            when (language) {
                AppLanguage.KO -> "전시 ${group.pins.size}개 그룹. 목록 열기"
                AppLanguage.EN -> "${group.pins.size} exhibition group. Open list"
            }
        }
    Box(
        modifier =
            modifier
                .width(if (group.pins.size == 1) 104.dp else 44.dp)
                .height(44.dp)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    onClick {
                        onClick()
                        true
                    }
                },
    )
}

@Composable
private fun MapRouteButton(
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        if (language == AppLanguage.KO) {
            "현재 지도 중심에서 동선 만들기"
        } else {
            "Build a route from the current map center"
        }
    Surface(
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = description },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = GallrSpacing.md),
        ) {
            Text(
                text = if (language == AppLanguage.KO) "동선 만들기" else "BUILD A ROUTE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.background,
                maxLines = 1,
            )
        }
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

internal fun groupPinsByExactPosition(pins: List<ExhibitionMapPin>): List<ExhibitionMapPinGroup> =
    pins
        .groupBy(ExhibitionMapPin::position)
        .map { (position, groupedPins) ->
            ExhibitionMapPinGroup(position = position, pins = groupedPins)
        }

internal fun groupContainsSavedExhibition(
    group: ExhibitionMapPinGroup,
    savedExhibitionIds: Set<String>,
): Boolean = group.pins.any { pin -> pin.exhibition.id in savedExhibitionIds }

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

internal fun mapRouteOrigin(position: Position): GeoPoint? =
    runCatching { GeoPoint(position.latitude, position.longitude) }.getOrNull()
