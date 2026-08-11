package com.gallr.app.ui.tabs.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.MapMarkState
import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.ProjectedMapMark
import kotlin.math.max
import kotlin.math.min

data class MapMarkCluster(
    val visitedCount: Int,
    val savedCount: Int,
    val unexploredCount: Int,
)

internal object DotMapVisualStyle {
    const val BACKGROUND_RADIUS_DP = 1.7f
    const val DENSE_BACKGROUND_RADIUS_DP = 1.05f
    const val MARKER_RADIUS_DP = 3.2f
    const val UNEXPLORED_RADIUS_DP = MARKER_RADIUS_DP
    const val SAVED_RADIUS_DP = MARKER_RADIUS_DP
    const val VISITED_RADIUS_DP = MARKER_RADIUS_DP
    const val SELECTED_RADIUS_DP = 3.6f
    const val CURRENT_LOCATION_RADIUS_DP = SELECTED_RADIUS_DP
    const val CURRENT_LOCATION_PULSE_START_RADIUS_DP = 7f
    const val CURRENT_LOCATION_PULSE_END_RADIUS_DP = 13f
    const val CURRENT_LOCATION_PULSE_WIDTH_DP = 1f
    const val CURRENT_LOCATION_MOVE_DURATION_MILLIS = 260
    const val HALO_RADIUS_DP = 4.8f
    const val OUTLINE_WIDTH_DP = 1.15f
}

/** One batched drawing surface for geography and semantic marks. */
@Composable
fun DotMapCanvas(
    geometry: DotMapGeometry,
    marks: List<ProjectedMapMark>,
    selectedMarkId: String?,
    currentLocationPoint: NormalizedPoint?,
    summary: String,
    onMarkTap: (String?) -> Unit,
    backgroundDotColor: Color,
    unexploredMarkColor: Color,
    primaryColor: Color,
    canvasColor: Color,
    clusters: Map<String, MapMarkCluster> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val backgroundRadius =
        with(density) {
            (
                if (geometry.cells.size > 1_500) {
                    DotMapVisualStyle.DENSE_BACKGROUND_RADIUS_DP
                } else {
                    DotMapVisualStyle.BACKGROUND_RADIUS_DP
                }
            ).dp.toPx()
        }
    val markRadius = with(density) { DotMapVisualStyle.MARKER_RADIUS_DP.dp.toPx() }
    val selectedRadius = with(density) { DotMapVisualStyle.SELECTED_RADIUS_DP.dp.toPx() }
    val haloRadius = with(density) { DotMapVisualStyle.HALO_RADIUS_DP.dp.toPx() }
    val outlineWidth = with(density) { DotMapVisualStyle.OUTLINE_WIDTH_DP.dp.toPx() }
    val currentLocationRadius = with(density) { DotMapVisualStyle.CURRENT_LOCATION_RADIUS_DP.dp.toPx() }
    val pulseStartRadius = with(density) { DotMapVisualStyle.CURRENT_LOCATION_PULSE_START_RADIUS_DP.dp.toPx() }
    val pulseEndRadius = with(density) { DotMapVisualStyle.CURRENT_LOCATION_PULSE_END_RADIUS_DP.dp.toPx() }
    val pulseWidth = with(density) { DotMapVisualStyle.CURRENT_LOCATION_PULSE_WIDTH_DP.dp.toPx() }
    val minimumTouchRadius = with(density) { 24.dp.toPx() }
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    val animatedLocationX: Float
    val animatedLocationY: Float
    if (currentLocationPoint == null) {
        animatedLocationX = 0f
        animatedLocationY = 0f
    } else {
        val locationX by animateFloatAsState(
            targetValue = currentLocationPoint.x.toFloat(),
            animationSpec =
                tween(
                    durationMillis = if (reduceMotion) 0 else DotMapVisualStyle.CURRENT_LOCATION_MOVE_DURATION_MILLIS,
                ),
            label = "current-location-x",
        )
        val locationY by animateFloatAsState(
            targetValue = currentLocationPoint.y.toFloat(),
            animationSpec =
                tween(
                    durationMillis = if (reduceMotion) 0 else DotMapVisualStyle.CURRENT_LOCATION_MOVE_DURATION_MILLIS,
                ),
            label = "current-location-y",
        )
        animatedLocationX = locationX
        animatedLocationY = locationY
    }
    val pulseProgress =
        if (currentLocationPoint == null) {
            0f
        } else {
            key(currentLocationPoint) { currentLocationPulseProgress(reduceMotion) }
        }

    Canvas(
        modifier =
            modifier
                .semantics { contentDescription = summary }
                .pointerInput(marks, minimumTouchRadius) {
                    detectTapGestures { tap ->
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val normalized =
                            NormalizedPoint(
                                x = (tap.x / size.width).coerceIn(0f, 1f).toDouble(),
                                y = (tap.y / size.height).coerceIn(0f, 1f).toDouble(),
                            )
                        val radius =
                            max(
                                minimumTouchRadius / size.width,
                                minimumTouchRadius / size.height,
                            ).toDouble()
                        onMarkTap(hitTestMapMark(marks, normalized, radius)?.id)
                    }
                },
    ) {
        geometry.cells.forEach { cell ->
            drawCircle(
                color = backgroundDotColor,
                radius = backgroundRadius,
                center = cell.point.toOffset(size.width, size.height),
            )
        }

        marks.forEach { mark ->
            val cluster = clusters[mark.id] ?: return@forEach
            val states =
                buildList {
                    repeat(cluster.visitedCount) { add(MapMarkState.VISITED) }
                    repeat(cluster.savedCount) { add(MapMarkState.SAVED) }
                    repeat(cluster.unexploredCount) { add(MapMarkState.UNEXPLORED) }
                }.toMutableList().also { aggregateStates ->
                    aggregateStates.remove(mark.state)
                }
            val center = mark.displayPoint.toOffset(size.width, size.height)
            val clusterRadius = backgroundRadius * 1.45f
            cityClusterOffsets
                .take(min(states.size, cityClusterOffsets.size))
                .forEachIndexed { index, offset ->
                    val satellite =
                        center +
                            Offset(
                                x = offset.x * markRadius * 2.5f,
                                y = offset.y * markRadius * 2.5f,
                            )
                    drawCircle(canvasColor, clusterRadius + outlineWidth, satellite)
                    when (states[index]) {
                        MapMarkState.VISITED -> {
                            drawCircle(primaryColor, clusterRadius, satellite)
                        }

                        MapMarkState.SAVED -> {
                            drawCircle(
                                color = primaryColor,
                                radius = clusterRadius,
                                center = satellite,
                                style = Stroke(width = outlineWidth),
                            )
                        }

                        else -> {
                            drawCircle(unexploredMarkColor, clusterRadius, satellite)
                        }
                    }
                }
        }

        // Knock out the geography beneath every semantic mark first. Drawing all
        // halos before all markers prevents a nearby halo from erasing a marker.
        marks.forEach { mark ->
            drawCircle(
                color = canvasColor,
                radius = haloRadius,
                center = mark.displayPoint.toOffset(size.width, size.height),
            )
        }

        marks.sortedBy { if (it.id == selectedMarkId) 1 else 0 }.forEach { mark ->
            val selected = mark.id == selectedMarkId
            val center = mark.displayPoint.toOffset(size.width, size.height)
            when {
                selected -> {
                    drawCircle(
                        color = GallrAccent.activeIndicator,
                        radius = selectedRadius,
                        center = center,
                    )
                }

                mark.state == MapMarkState.SAVED -> {
                    drawCircle(
                        color = primaryColor,
                        radius = markRadius,
                        center = center,
                        style = Stroke(width = outlineWidth),
                    )
                }

                mark.state == MapMarkState.VISITED -> {
                    drawCircle(
                        color = primaryColor,
                        radius = markRadius,
                        center = center,
                    )
                }

                else -> {
                    drawCircle(
                        color = unexploredMarkColor,
                        radius = markRadius,
                        center = center,
                    )
                }
            }
        }

        currentLocationPoint?.let {
            val center = Offset(animatedLocationX * size.width, animatedLocationY * size.height)
            val pulseRadius = pulseStartRadius + (pulseEndRadius - pulseStartRadius) * pulseProgress
            val pulseAlpha = if (reduceMotion) 0.55f else 0.65f * (1f - pulseProgress)
            drawCircle(
                color = GallrAccent.interactionFeedback.copy(alpha = pulseAlpha),
                radius = pulseRadius,
                center = center,
                style = Stroke(width = pulseWidth),
            )
            drawCircle(
                color = GallrAccent.interactionFeedback,
                radius = currentLocationRadius,
                center = center,
            )
        }
    }
}

@Composable
private fun currentLocationPulseProgress(reduceMotion: Boolean): Float {
    if (reduceMotion) return 0.35f
    val transition = rememberInfiniteTransition(label = "current-location-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1_800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "current-location-pulse-progress",
    )
    return progress
}

private fun NormalizedPoint.toOffset(
    width: Float,
    height: Float,
) = Offset((x * width).toFloat(), (y * height).toFloat())

private val cityClusterOffsets =
    listOf(
        Offset(-1f, -1f),
        Offset(0f, -1f),
        Offset(1f, -1f),
        Offset(-1f, 0f),
        Offset(1f, 0f),
        Offset(-1f, 1f),
        Offset(0f, 1f),
        Offset(1f, 1f),
        Offset(-2f, -1f),
        Offset(2f, -1f),
        Offset(-2f, 0f),
        Offset(2f, 0f),
        Offset(-2f, 1f),
        Offset(2f, 1f),
        Offset(-1f, 2f),
        Offset(0f, 2f),
        Offset(1f, 2f),
    )
