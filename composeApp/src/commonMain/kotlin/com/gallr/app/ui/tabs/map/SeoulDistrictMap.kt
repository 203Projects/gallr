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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gallr.app.ui.map.LogoPinPainter
import com.gallr.app.ui.map.MAP_PIN_BADGE_OFFSET_X
import com.gallr.app.ui.map.MAP_PIN_BADGE_OFFSET_Y
import com.gallr.app.ui.map.MAP_PIN_IMAGE_SIZE
import com.gallr.app.ui.map.mapPinImageId
import com.gallr.app.ui.map.mapPinRingColor
import com.gallr.app.ui.map.rememberMapPinImageResolver
import com.gallr.app.ui.map.representativeCoverUrl
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_location_on
import gallr.composeapp.generated.resources.ic_my_location
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val FALLBACK_SEOUL_MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
internal const val QUIET_SEOUL_MAP_STYLE_RESOURCE =
    "files/map_data/openfreemap_positron_gallr.json"
internal const val MAP_MIN_ZOOM = 2.0
internal const val MAP_MAX_ZOOM = 20.0

/** The Seoul map stays flat: the pitch range is closed so pins and their tap targets line up. */
private val SEOUL_MAP_CAMERA_CONSTRAINTS =
    CameraConstraints {
        minZoom = MAP_MIN_ZOOM
        maxZoom = MAP_MAX_ZOOM
        maxPitch = 0.0
    }
private const val MAP_ZOOM_STEP = 1.0
private const val PIN_FEATURE_TITLE = "title"
private const val PIN_FEATURE_COUNT = "count"
private const val PIN_FEATURE_IS_GROUP = "is_group"
private const val PIN_FEATURE_IS_SAVED = "is_saved"
private const val PIN_FEATURE_HAS_COVER = "has_cover"
private const val PIN_FEATURE_IMAGE = "image"
private const val PIN_FEATURE_DRAW_ORDER = "draw_order"
internal const val PIN_FONT = "Noto Sans Regular"

/** Pins closer than this share one pin: enough of each 48dp thumbnail stays visible to tell them apart. */
private val PIN_GROUPING_THRESHOLD = 28.dp
private val PIN_BADGE_RADIUS = 9.dp

/** Distance of a title below its pin's tip, in ems: clear of the pin's own collision box. */
private const val PIN_TITLE_OFFSET_EM = 0.7f

/** The accessible target over a pin: the pin's height from its tip, and the ring's width for a group. */
private val PIN_TARGET_HEIGHT = 56.dp

/** The recenter button's footprint before it sized to its label; the English label widens it from here. */
private val MAP_RECENTER_MIN_WIDTH = 104.dp
private val PIN_TARGET_GROUP_WIDTH = 52.dp

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
    modifier: Modifier = Modifier,
) {
    var selectedOverlapGroup by remember { mutableStateOf<List<Exhibition>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val mapStyleUri = remember { Res.getUri(QUIET_SEOUL_MAP_STYLE_RESOURCE) }
    val initialViewport = remember { initialMapViewport(initialCenter) }
    val mapPins = remember(exhibitions) { exhibitionMapPins(exhibitions) }
    val exactPinGroups = remember(mapPins) { groupPinsByExactPosition(mapPins) }
    // The layers are declared with the map state, before the projection that groups the pins exists.
    var layerPinGroups by remember { mutableStateOf(exactPinGroups) }
    val mapState =
        rememberMapState(
            baseStyle = BaseStyle.Uri(mapStyleUri.ifBlank { FALLBACK_SEOUL_MAP_STYLE }),
            initialCameraPosition =
                CameraPosition(
                    center =
                        Position(
                            latitude = initialViewport.latitude,
                            longitude = initialViewport.longitude,
                        ),
                    zoom = initialViewport.zoom,
                ),
        ) {
            ExhibitionPinLayers(
                groups = layerPinGroups,
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
    val pinImageResolver = rememberMapPinImageResolver()
    SideEffect { mapState.missingImageResolver = pinImageResolver }
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
            mapState.setCameraPosition(
                CameraPosition(
                    center =
                        Position(
                            latitude = viewport.latitude,
                            longitude = viewport.longitude,
                        ),
                    zoom = viewport.zoom,
                ),
            )
            hasCenteredOnUser = true
            locationFeedbackVersion += 1
        }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        // A new viewport instance arrives with every rendered camera or size, so it keys the projection.
        val mapViewport = mapState.viewport
        val density = LocalDensity.current
        val screenPinGroups =
            if (mapViewport == null) {
                emptyList()
            } else {
                remember(mapPins, mapViewport, density) {
                    val projectedPins =
                        mapPins.mapNotNull { pin ->
                            val point = mapState.screenLocationFromPosition(pin.position) ?: return@mapNotNull null
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
                        val position =
                            if (groupedPins.size == 1) {
                                groupedPins.single().position
                            } else {
                                mapState.positionFromScreenLocation(
                                    DpOffset(
                                        x = with(density) { visualGroup.xPx.toDp() },
                                        y = with(density) { visualGroup.yPx.toDp() },
                                    ),
                                )
                            }
                        if (groupedPins.isEmpty() || position == null) {
                            null
                        } else {
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
            if (mapViewport == null) {
                exactPinGroups
            } else {
                screenPinGroups.map(ScreenExhibitionMapPinGroup::group)
            }
        SideEffect { layerPinGroups = pinGroups }

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            cameraConstraints = SEOUL_MAP_CAMERA_CONSTRAINTS,
            // gallr draws its own controls and attribution over the map.
            overlay = {},
        )

        if (mapViewport != null) {
            val pinHorizontalExtentPx = with(density) { 52.dp.toPx() }
            val pinTopExtentPx = with(density) { PIN_TARGET_HEIGHT.toPx() }
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

            val userLocationPoint =
                initialCenter?.let { coordinates ->
                    mapState.screenLocationFromPosition(
                        Position(
                            latitude = coordinates.latitude,
                            longitude = coordinates.longitude,
                        ),
                    )
                }
            userLocationPoint?.let { point ->
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
                            val width = if (screenGroup.group.pins.size == 1) 104.dp else PIN_TARGET_GROUP_WIDTH
                            IntOffset(
                                x = (screenGroup.xPx - width.toPx() / 2f).roundToInt(),
                                y = (screenGroup.yPx - PIN_TARGET_HEIGHT.toPx()).roundToInt(),
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
                    mapState.animateCamera(
                        CameraUpdate(zoom = steppedMapZoom(mapState.cameraPosition.zoom, direction = 1)),
                    )
                }
            },
            onZoomOut = {
                scope.launch {
                    mapState.animateCamera(
                        CameraUpdate(zoom = steppedMapZoom(mapState.cameraPosition.zoom, direction = -1)),
                    )
                }
            },
            onRecenter = {
                val coordinates = initialCenter
                onLocationRequest()
                if (coordinates != null) {
                    scope.launch {
                        val viewport = initialMapViewport(coordinates)
                        mapState.animateCamera(
                            CameraUpdate(
                                center =
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

/**
 * The pins (DESIGN.md, Map pins): a plain logo pin under every group so the map is never empty while covers
 * load, the same pin with the exhibition's cover drawn on top once the map has it, the title under a single
 * exhibition and a count on a group.
 */
@Composable
private fun ExhibitionPinLayers(
    groups: List<ExhibitionMapPinGroup>,
    savedExhibitionIds: Set<String>,
    language: AppLanguage,
    onGroupClick: (ExhibitionMapPinGroup) -> Unit,
) {
    val groupsByKey = remember(groups) { groups.associateBy(::pinGroupKey) }
    val onFeatureClick: FeaturesClickHandler = { features ->
        val group = features.firstNotNullOfOrNull { feature -> feature.id?.contentOrNull?.let(groupsByKey::get) }
        if (group == null) {
            ClickResult.Pass
        } else {
            onGroupClick(group)
            ClickResult.Consume
        }
    }
    val source =
        rememberGeoJsonSource(
            data = GeoJsonData.Features(pinFeatureCollection(groups, savedExhibitionIds, language)),
        )
    val isGroup = feature.get(PIN_FEATURE_IS_GROUP).asBoolean()
    val isSaved = feature.get(PIN_FEATURE_IS_SAVED).asBoolean()
    val drawOrder = feature.get(PIN_FEATURE_DRAW_ORDER).asNumber()
    val plainPin = remember { LogoPinPainter(cover = null, ring = mapPinRingColor(saved = false)) }
    val plainSavedPin = remember { LogoPinPainter(cover = null, ring = mapPinRingColor(saved = true)) }

    // Titles lie under the pins and give way to them: a pin always shows, a title only where it is clear.
    SymbolLayer(
        id = "gallr-pin-title",
        source = source,
        filter = !isGroup,
        textField = feature.get(PIN_FEATURE_TITLE).asString(),
        textFont = const(listOf(PIN_FONT)),
        textSize = const(12.sp),
        textColor = const(Color.Black),
        textHaloColor = const(Color.White),
        textHaloWidth = const(1.dp),
        textAnchor = const(SymbolAnchor.Top),
        textOffset = textOffset(0.em, PIN_TITLE_OFFSET_EM.em),
        textMaxWidth = const(0.em),
        textAllowOverlap = const(false),
        textIgnorePlacement = const(false),
        textPadding = const(4.dp),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "gallr-pin-plain",
        source = source,
        filter = !isSaved,
        iconImage = image(plainPin, size = MAP_PIN_IMAGE_SIZE),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(false),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "gallr-pin-plain-saved",
        source = source,
        filter = isSaved,
        iconImage = image(plainSavedPin, size = MAP_PIN_IMAGE_SIZE),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(false),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "gallr-pin-cover",
        source = source,
        filter = feature.get(PIN_FEATURE_HAS_COVER).asBoolean(),
        sortKey = drawOrder,
        iconImage = image(feature.get(PIN_FEATURE_IMAGE).asString()),
        iconAnchor = const(SymbolAnchor.Bottom),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(false),
        onClick = onFeatureClick,
    )
    CircleLayer(
        id = "gallr-pin-count-badge",
        source = source,
        filter = isGroup,
        translate = offset(MAP_PIN_BADGE_OFFSET_X, MAP_PIN_BADGE_OFFSET_Y),
        color = const(Color.White),
        radius = const(PIN_BADGE_RADIUS),
        strokeColor = const(Color.Black),
        strokeWidth = const(1.dp),
        onClick = onFeatureClick,
    )
    SymbolLayer(
        id = "gallr-pin-count",
        source = source,
        filter = isGroup,
        textField = feature.get(PIN_FEATURE_COUNT).asString(),
        textFont = const(listOf(PIN_FONT)),
        textSize = const(11.sp),
        textColor = const(Color.Black),
        textTranslate = offset(MAP_PIN_BADGE_OFFSET_X, MAP_PIN_BADGE_OFFSET_Y),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
        onClick = onFeatureClick,
    )
}

private fun pinFeatureCollection(
    groups: List<ExhibitionMapPinGroup>,
    savedExhibitionIds: Set<String>,
    language: AppLanguage,
): FeatureCollection<Point, JsonObject> =
    FeatureCollection(
        groups.map { group ->
            val saved = groupContainsSavedExhibition(group, savedExhibitionIds)
            val coverUrl = representativeCoverUrl(group.pins.map { it.exhibition.coverImageUrl })
            Feature(
                geometry = Point(group.position),
                properties =
                    buildJsonObject {
                        put(
                            PIN_FEATURE_TITLE,
                            group.pins
                                .singleOrNull()
                                ?.exhibition
                                ?.localizedName(language)
                                ?.let(::compactMapPinTitle)
                                .orEmpty(),
                        )
                        put(PIN_FEATURE_COUNT, group.pins.size.toString())
                        put(PIN_FEATURE_IS_GROUP, group.pins.size > 1)
                        put(PIN_FEATURE_IS_SAVED, saved)
                        put(PIN_FEATURE_HAS_COVER, coverUrl != null)
                        put(PIN_FEATURE_IMAGE, mapPinImageId(coverUrl, saved))
                        // Saved pins are drawn last, so they stay on top where pins overlap.
                        put(PIN_FEATURE_DRAW_ORDER, if (saved) 1 else 0)
                    },
                id = JsonPrimitive(pinGroupKey(group)),
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
                .width(if (group.pins.size == 1) 104.dp else PIN_TARGET_GROUP_WIDTH)
                .height(PIN_TARGET_HEIGHT)
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

/** The button sizes to its label (at least 104dp wide): a fixed width clipped "MY LOCATION". */
@Composable
internal fun MapRecenterButton(
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (language == AppLanguage.KO) "내 위치로 이동" else "RECENTER ON MY LOCATION"
    Surface(
        modifier =
            modifier
                .widthIn(min = MAP_RECENTER_MIN_WIDTH)
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
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
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
                softWrap = false,
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
