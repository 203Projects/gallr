package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.MapChildSummary
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.ProjectedMapMark

@Composable
fun CountryCityLabels(
    summaries: List<MapChildSummary>,
    marks: List<ProjectedMapMark>,
    lang: AppLanguage,
    highlightedCityId: String?,
    onCityTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val marksById = marks.associateBy { it.id }
    val rails =
        remember(summaries, marks) {
            resolveCountryLabelRails(
                summaries.mapNotNull { summary ->
                    marksById[summary.scope.id.value]?.displayPoint?.let { summary.scope.id.value to it }
                },
            )
        }
    BoxWithConstraints(modifier = modifier) {
        val labelWidth = 68.dp
        val leaderColor = MaterialTheme.colorScheme.onBackground
        Canvas(Modifier.fillMaxSize()) {
            rails.values.forEach { rail ->
                val anchor =
                    Offset(
                        x = rail.anchor.x.toFloat() * size.width,
                        y = rail.anchor.y.toFloat() * size.height,
                    )
                val labelY = rail.labelY.toFloat() * size.height
                val railEdge =
                    when (rail.side) {
                        CountryLabelSide.LEFT -> labelWidth.toPx() - 4.dp.toPx()
                        CountryLabelSide.RIGHT -> size.width - labelWidth.toPx() + 4.dp.toPx()
                    }
                val elbowX =
                    when (rail.side) {
                        CountryLabelSide.LEFT -> size.width * 0.205f
                        CountryLabelSide.RIGHT -> size.width * 0.795f
                    }
                drawLine(
                    color = leaderColor,
                    start = Offset(railEdge, labelY),
                    end = Offset(elbowX, labelY),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = leaderColor,
                    start = Offset(elbowX, labelY),
                    end = anchor,
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        summaries.forEach { summary ->
            val rail = rails[summary.scope.id.value] ?: return@forEach
            val x =
                when (rail.side) {
                    CountryLabelSide.LEFT -> 0.dp
                    CountryLabelSide.RIGHT -> (maxWidth - labelWidth).coerceAtLeast(0.dp)
                }
            val y =
                (maxHeight * rail.labelY.toFloat() - 26.dp)
                    .coerceIn(0.dp, (maxHeight - 52.dp).coerceAtLeast(0.dp))
            Column(
                modifier =
                    Modifier
                        .offset(x = x, y = y)
                        .width(labelWidth)
                        .heightIn(min = 52.dp)
                        .clickable { onCityTap(summary.scope.id.value) }
                        .semantics { role = Role.Button }
                        .padding(vertical = GallrSpacing.xs),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = if (lang == AppLanguage.KO) summary.scope.labelKo else summary.scope.labelEn,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = summary.aggregate.activeExhibitionCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (summary.scope.id.value == highlightedCityId) {
                            GallrAccent.activeIndicator
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                )
            }
        }
    }
}

internal enum class CountryLabelSide { LEFT, RIGHT }

internal data class CountryLabelRail(
    val anchor: NormalizedPoint,
    val labelY: Double,
    val side: CountryLabelSide,
)

internal fun resolveCountryLabelRails(
    points: List<Pair<String, NormalizedPoint>>,
    minimumVerticalGap: Double = 0.145,
): Map<String, CountryLabelRail> =
    buildMap {
        CountryLabelSide.entries.forEach { side ->
            val sidePoints =
                points
                    .filter { (_, point) -> (point.x < 0.5) == (side == CountryLabelSide.LEFT) }
                    .sortedWith(compareBy<Pair<String, NormalizedPoint>> { it.second.y }.thenBy { it.first })
            if (sidePoints.isEmpty()) return@forEach

            val resolved =
                MutableList(sidePoints.size) { index ->
                    sidePoints[index].second.y.coerceIn(0.08, 0.92)
                }
            for (index in 1 until resolved.size) {
                resolved[index] = maxOf(resolved[index], resolved[index - 1] + minimumVerticalGap)
            }
            val overflow = (resolved.last() - 0.92).coerceAtLeast(0.0)
            if (overflow > 0.0) {
                for (index in resolved.indices) resolved[index] -= overflow
            }
            for (index in resolved.lastIndex - 1 downTo 0) {
                resolved[index] = minOf(resolved[index], resolved[index + 1] - minimumVerticalGap)
            }
            sidePoints.forEachIndexed { index, (id, point) ->
                put(id, CountryLabelRail(anchor = point, labelY = resolved[index], side = side))
            }
        }
    }

@Composable
fun CityDistrictLabels(
    summaries: List<MapChildSummary>,
    lang: AppLanguage,
    onDistrictTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        summaries
            .filter { it.displayPoint != null }
            .sortedByDescending { it.aggregate.activeExhibitionCount }
            .take(5)
            .forEach { summary ->
                val point = summary.displayPoint ?: return@forEach
                val labelWidth = 64.dp
                val anchorX = maxWidth * point.x.toFloat()
                val anchorY = maxHeight * point.y.toFloat()
                val proposedX = if (point.x > 0.58) anchorX - labelWidth - 10.dp else anchorX + 10.dp
                val x = proposedX.coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
                val y = (anchorY - 22.dp).coerceIn(0.dp, (maxHeight - 44.dp).coerceAtLeast(0.dp))
                Box(
                    modifier =
                        Modifier
                            .offset(x = x, y = y)
                            .width(labelWidth)
                            .heightIn(min = 44.dp)
                            .clickable { onDistrictTap(summary.scope.id.value) }
                            .semantics { role = Role.Button }
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
                            .padding(horizontal = GallrSpacing.xs, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                summary.scope.labelKo.removeSuffix("구")
                            } else {
                                summary.scope.labelEn.removeSuffix("-gu")
                            },
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
    }
}
