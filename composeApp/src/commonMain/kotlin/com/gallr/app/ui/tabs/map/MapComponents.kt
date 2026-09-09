package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.MapChildSummary
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.MapScope
import com.gallr.shared.data.model.map.PersonalMapMode
import com.gallr.shared.data.model.map.ScopeAggregate
import com.gallr.shared.map.NearbyExhibition
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import kotlin.math.round

internal val mapTabModes = listOf(PersonalMapMode.ALL, PersonalMapMode.TO_VISIT)

internal fun mapModeLabel(
    mode: PersonalMapMode,
    lang: AppLanguage,
): String =
    when (mode) {
        PersonalMapMode.ALL -> if (lang == AppLanguage.KO) "전체 전시" else "ALL EXHIBITIONS"
        PersonalMapMode.TO_VISIT -> if (lang == AppLanguage.KO) "내 전시" else "MY EXHIBITIONS"
        PersonalMapMode.VISITED -> if (lang == AppLanguage.KO) "방문함" else "VISITED"
    }

internal fun savedMapLegendLabel(lang: AppLanguage): String = if (lang == AppLanguage.KO) "내 전시" else "MY EXHIBITIONS"

@Composable
fun MapModeTabs(
    mode: PersonalMapMode,
    lang: AppLanguage,
    onModeChange: (PersonalMapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedMode = mode.takeIf(mapTabModes::contains) ?: PersonalMapMode.ALL
    val selectedIndex = mapTabModes.indexOf(selectedMode)
    SecondaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        divider = {},
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex),
                color = GallrAccent.activeIndicator,
                height = 3.dp,
            )
        },
    ) {
        mapTabModes.forEach { item ->
            Tab(
                selected = item == selectedMode,
                onClick = { onModeChange(item) },
                text = {
                    Text(
                        text = mapModeLabel(item, lang),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

@Composable
fun MapScopeHeader(
    scope: MapScope,
    lang: AppLanguage,
    exhibitionCount: Int,
    childCount: Int,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (onBack != null) {
                Row(
                    modifier =
                        Modifier
                            .height(32.dp)
                            .clickable(onClick = onBack)
                            .semantics { role = Role.Button },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (lang == AppLanguage.KO) "대한민국으로" else "BACK TO SOUTH KOREA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (lang == AppLanguage.KO) scope.labelKo else scope.labelEn.ifBlank { scope.labelKo },
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text =
                when (lang) {
                    AppLanguage.KO -> {
                        "$exhibitionCount 전시 · $childCount ${if (scope.parentId == null) "도시" else "구역"}"
                    }

                    AppLanguage.EN -> {
                        "$exhibitionCount EXHIBITIONS · " +
                            "$childCount ${if (scope.parentId == null) "CITIES" else "AREAS"}"
                    }
                },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = GallrSpacing.xs),
        )
    }
}

@Composable
fun MapLegend(
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                space = GallrSpacing.md,
                alignment = Alignment.CenterHorizontally,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(if (lang == AppLanguage.KO) "방문함" else "VISITED", filled = true)
        LegendItem(if (lang == AppLanguage.KO) "저장함" else "SAVED", outlined = true)
        LegendItem(if (lang == AppLanguage.KO) "미탐색" else "UNEXPLORED")
        LegendItem(
            if (lang == AppLanguage.KO) "내 위치" else "MY LOCATION",
            accent = true,
        )
    }
}

@Composable
private fun LegendItem(
    label: String,
    filled: Boolean = false,
    outlined: Boolean = false,
    accent: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
        val color =
            when {
                accent -> {
                    GallrAccent.interactionFeedback
                }

                filled || outlined -> {
                    MaterialTheme.colorScheme.onBackground
                }

                else -> {
                    lerp(
                        MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        0.62f,
                    )
                }
            }
        Canvas(Modifier.size(12.dp)) {
            if (outlined) {
                drawCircle(color = color, style = Stroke(width = 1.5.dp.toPx()))
            } else {
                drawCircle(color = color)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ScopeSummaryPanel(
    eyebrow: String,
    title: String,
    aggregate: ScopeAggregate,
    lang: AppLanguage,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(GallrSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(eyebrow, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    when (lang) {
                        AppLanguage.KO -> {
                            "${aggregate.visitedExhibitionCount} 방문함   " +
                                "${aggregate.savedUnvisitedCount} 저장함   " +
                                "${aggregate.unexploredCount} 미탐색"
                        }

                        AppLanguage.EN -> {
                            "${aggregate.visitedExhibitionCount} VISITED   " +
                                "${aggregate.savedUnvisitedCount} SAVED   " +
                                "${aggregate.unexploredCount} UNEXPLORED"
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAction,
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun BrowseMapResults(
    childSummaries: List<MapChildSummary>,
    exhibitions: List<Exhibition>,
    lang: AppLanguage,
    onScopeTap: (MapScope) -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (childSummaries.isNotEmpty()) {
            items(childSummaries, key = { it.scope.id.value }) { summary ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onScopeTap(summary.scope) }
                            .semantics { role = Role.Button }
                            .padding(vertical = GallrSpacing.md),
                ) {
                    Text(
                        if (lang == AppLanguage.KO) summary.scope.labelKo else summary.scope.labelEn,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${summary.aggregate.activeExhibitionCount} " +
                            if (lang == AppLanguage.KO) "전시" else "EXHIBITIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        } else {
            items(exhibitions, key = { it.id }) { exhibition ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onExhibitionTap(exhibition) }
                            .semantics { role = Role.Button }
                            .padding(vertical = GallrSpacing.md),
                ) {
                    Text(
                        exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        exhibition.localizedVenueName(lang).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (exhibition.latitude == null || exhibition.longitude == null) {
                        Text(
                            if (lang == AppLanguage.KO) "위치 정보 없음" else "LOCATION UNAVAILABLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun BrowseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun NearbyExhibitionsSection(
    exhibitions: List<NearbyExhibition>,
    today: LocalDate,
    lang: AppLanguage,
    onResetToDeviceLocation: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "선택 위치 주변" else "NEAR SELECTED LOCATION",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .height(44.dp)
                        .clickable(onClick = onResetToDeviceLocation)
                        .semantics { role = Role.Button }
                        .padding(horizontal = GallrSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (lang == AppLanguage.KO) "내 위치로" else "MY LOCATION",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (exhibitions.isEmpty()) {
            Text(
                text = if (lang == AppLanguage.KO) "주변 전시를 찾을 수 없습니다" else "NO EXHIBITIONS NEARBY",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = GallrSpacing.md),
            )
        } else {
            exhibitions.take(2).forEach { nearby ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onExhibitionTap(nearby.exhibition) }
                            .semantics { role = Role.Button },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = nearby.exhibition.localizedVenueName(lang),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatDistance(nearby.distanceKm),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = closingLabel(nearby.exhibition.closingDate, today, lang),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(88.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun formatDistance(distanceKm: Double): String = "${round(distanceKm * 10) / 10} KM"

private fun closingLabel(
    closingDate: LocalDate,
    today: LocalDate,
    lang: AppLanguage,
): String =
    when {
        closingDate == today -> if (lang == AppLanguage.KO) "오늘 마감" else "ENDS TODAY"
        lang == AppLanguage.KO -> "${closingDate.month.ordinal + 1}월 ${closingDate.day}일까지"
        else -> "UNTIL ${englishMonth(closingDate.month.ordinal + 1)} ${closingDate.day}"
    }

private fun englishMonth(month: Int): String =
    listOf(
        "JAN",
        "FEB",
        "MAR",
        "APR",
        "MAY",
        "JUN",
        "JUL",
        "AUG",
        "SEP",
        "OCT",
        "NOV",
        "DEC",
    )[month - 1]
