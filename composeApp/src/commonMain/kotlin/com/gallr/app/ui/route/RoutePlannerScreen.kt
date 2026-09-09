package com.gallr.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.discovery.recommendationContextLabel
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.RouteUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RoutePlanningRequest
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    state: RouteUiState,
    lang: AppLanguage,
    onModeChange: (RouteCurationMode) -> Unit,
    onStopCountChange: (Int) -> Unit,
    onBuild: () -> Unit,
    onReduceStops: () -> Unit,
    onStartRoute: (Exhibition) -> Unit,
    onOpenStop: (Exhibition) -> Unit,
    onExhibitionTap: (Exhibition, Int) -> Unit,
    onBack: () -> Unit,
    mapOpenError: String? = null,
    modifier: Modifier = Modifier,
) {
    val request = state.requestOrNull()
    val isPlanning = state is RouteUiState.Planning

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            RouteTopBar(
                language = lang,
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = GallrSpacing.screenMargin,
                    end = GallrSpacing.screenMargin,
                    bottom = GallrSpacing.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
        ) {
            if (request != null) {
                item(key = "route-controls") {
                    RouteControls(
                        request = request,
                        language = lang,
                        enabled = !isPlanning,
                        buildLabel =
                            if (state is RouteUiState.Ready) {
                                if (lang == AppLanguage.KO) "경로 다시 만들기" else "REBUILD ROUTE"
                            } else {
                                if (lang == AppLanguage.KO) "경로 만들기" else "BUILD ROUTE"
                            },
                        onModeChange = onModeChange,
                        onStopCountChange = onStopCountChange,
                        onBuild = onBuild,
                    )
                }
            }

            when (state) {
                RouteUiState.Idle -> {
                    item(key = "route-idle") {
                        RouteLoadingMessage(
                            message = if (lang == AppLanguage.KO) "경로를 준비하고 있습니다" else "PREPARING ROUTE",
                            showProgress = false,
                        )
                    }
                }

                is RouteUiState.Editing -> {
                    // Controls above are the complete editing state.
                }

                is RouteUiState.Planning -> {
                    item(key = "route-planning") {
                        RouteLoadingMessage(
                            message = if (lang == AppLanguage.KO) "경로를 계산하고 있습니다" else "BUILDING ROUTE",
                            showProgress = true,
                        )
                    }
                }

                is RouteUiState.Ready -> {
                    item(key = "route-ready") {
                        ReadyRouteContent(
                            route = state.estimate,
                            language = lang,
                            mapOpenError = mapOpenError,
                            onStartRoute = onStartRoute,
                            onOpenStop = onOpenStop,
                            onExhibitionTap = onExhibitionTap,
                        )
                    }
                }

                is RouteUiState.Insufficient -> {
                    item(key = "route-insufficient") {
                        val canReduce =
                            state.available >= MINIMUM_STOP_COUNT &&
                                state.request.stopCount > MINIMUM_STOP_COUNT
                        RouteInsufficientContent(
                            message =
                                insufficientRouteMessage(
                                    requested = state.request.stopCount,
                                    available = state.available,
                                    language = lang,
                                ),
                            canReduce = canReduce,
                            language = lang,
                            onAction = onReduceStops,
                        )
                    }
                }

                is RouteUiState.Error -> {
                    item(key = "route-error") {
                        GallrErrorMessage(
                            message = if (lang == AppLanguage.KO) "경로를 만들지 못했습니다." else "Couldn’t build this route.",
                            actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Try again",
                            onAction = onBuild,
                            modifier =
                                Modifier
                                    .padding(vertical = GallrSpacing.md)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteInsufficientContent(
    message: String,
    canReduce: Boolean,
    language: AppLanguage,
    onAction: () -> Unit,
) {
    GallrEmptyState(
        message = message,
        actionLabel =
            if (canReduce) {
                if (language == AppLanguage.KO) "정류장 수 줄이기" else "REDUCE STOPS"
            } else {
                null
            },
        onAction = onAction,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun RouteControls(
    request: RoutePlanningRequest,
    language: AppLanguage,
    enabled: Boolean,
    buildLabel: String,
    onModeChange: (RouteCurationMode) -> Unit,
    onStopCountChange: (Int) -> Unit,
    onBuild: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (language == AppLanguage.KO) "경로 방식" else "CURATION MODE",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = GallrSpacing.lg, bottom = GallrSpacing.sm),
        )
        Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            RouteCurationMode.entries.forEachIndexed { index, mode ->
                RouteModeRow(
                    mode = mode,
                    selected = request.mode == mode,
                    enabled = enabled,
                    language = language,
                    onClick = { onModeChange(mode) },
                )
                if (index < RouteCurationMode.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Text(
            text = if (language == AppLanguage.KO) "정류장 수" else "STOPS",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = GallrSpacing.xl, bottom = GallrSpacing.sm),
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            (MINIMUM_STOP_COUNT..MAXIMUM_STOP_COUNT).forEach { count ->
                StopCountChoice(
                    count = count,
                    selected = request.stopCount == count,
                    enabled = enabled,
                    onClick = { onStopCountChange(count) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text =
                if (language == AppLanguage.KO) {
                    "지도 중심에서 5 KM 이내 · 전시당 45분"
                } else {
                    "WITHIN 5 KM OF MAP CENTER · 45 MIN / STOP"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GallrSpacing.sm),
        )

        Button(
            onClick = onBuild,
            enabled = enabled,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier = Modifier.fillMaxWidth().padding(top = GallrSpacing.lg).heightIn(min = 44.dp),
        ) {
            Text(buildLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RouteModeRow(
    mode: RouteCurationMode,
    selected: Boolean,
    enabled: Boolean,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.localizedLabel(language),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = mode.localizedDescription(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                HorizontalDivider(
                    color = GallrAccent.activeIndicator,
                    thickness = 2.dp,
                    modifier = Modifier.width(24.dp).clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun StopCountChoice(
    count: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = count.toString(), style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        HorizontalDivider(
            color = if (selected) GallrAccent.activeIndicator else Color.Transparent,
            thickness = 2.dp,
            modifier = Modifier.width(24.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun ReadyRouteContent(
    route: ExhibitionRouteEstimate,
    language: AppLanguage,
    mapOpenError: String?,
    onStartRoute: (Exhibition) -> Unit,
    onOpenStop: (Exhibition) -> Unit,
    onExhibitionTap: (Exhibition, Int) -> Unit,
) {
    val summary = routeSummaryPresentation(route, language)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            text = if (language == AppLanguage.KO) "경로 요약" else "ROUTE SUMMARY",
            style = MaterialTheme.typography.labelLarge,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(GallrSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
        ) {
            Text(summary.distance, style = MaterialTheme.typography.titleMedium)
            Text(summary.travelTime, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary.totalTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        route.warnings.sortedBy { it.ordinal }.forEach { warning ->
            Text(
                text = "! ${warning.localizedLabel(language)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        route.stops.firstOrNull()?.let { firstStop ->
            Button(
                onClick = { onStartRoute(firstStop) },
                shape = RectangleShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .semantics {
                            contentDescription =
                                if (language == AppLanguage.KO) {
                                    "첫 번째 정류장을 지도에서 열어 경로 시작"
                                } else {
                                    "Start route by opening the first stop in Maps"
                                }
                        },
            ) {
                Text(if (language == AppLanguage.KO) "경로 시작" else "START ROUTE")
            }
        }

        if (mapOpenError != null) {
            GallrErrorMessage(
                message = mapOpenError,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Text(
            text = if (language == AppLanguage.KO) "정류장" else "STOPS",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = GallrSpacing.sm),
        )
        route.stops.forEachIndexed { index, exhibition ->
            val leg = route.legs[index]
            val whyThisLabel =
                route.recommendationEvidenceByExhibitionId[exhibition.id]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { recommendationContextLabel(it, language) }
            RouteStopCard(
                index = index,
                stopCount = route.stops.size,
                exhibition = exhibition,
                leg = leg,
                language = language,
                whyThisLabel = whyThisLabel,
                onOpenMap = { onOpenStop(exhibition) },
                onOpenDetail = { onExhibitionTap(exhibition, index) },
            )
        }
    }
}

@Composable
private fun RouteStopCard(
    index: Int,
    stopCount: Int,
    exhibition: Exhibition,
    leg: com.gallr.shared.map.EstimatedRouteLeg,
    language: AppLanguage,
    whyThisLabel: String?,
    onOpenMap: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val semanticsLabel =
        routeStopSemanticsLabel(
            stopIndex = index,
            stopCount = stopCount,
            exhibition = exhibition,
            leg = leg,
            language = language,
            whyThisLabel = whyThisLabel,
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClick = onOpenDetail)
                    .semantics(mergeDescendants = true) { contentDescription = semanticsLabel }
                    .padding(GallrSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(40.dp).clearAndSetSemantics { },
            )
            Column(modifier = Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(
                    text = exhibition.localizedName(language),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = exhibition.localizedVenueName(language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                whyThisLabel?.let { label ->
                    Spacer(Modifier.height(GallrSpacing.xs))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = routeLegLabel(index, leg, language),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = routeHoursLabel(exhibition.hours, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedButton(
            onClick = onOpenMap,
            shape = RectangleShape,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics {
                        contentDescription = routeStopMapContentDescription(index, stopCount, language)
                    },
        ) {
            Text(
                text = if (language == AppLanguage.KO) "지도에서 열기" else "OPEN IN MAPS",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RouteLoadingMessage(
    message: String,
    showProgress: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onBackground,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteTopBar(
    language: AppLanguage,
    onBack: () -> Unit,
) {
    val backLabel = if (language == AppLanguage.KO) "뒤로" else "Back"
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = if (language == AppLanguage.KO) "전시 동선" else "ROUTE",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backLabel },
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = null,
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
    )
}

private fun RouteUiState.requestOrNull(): RoutePlanningRequest? =
    when (this) {
        RouteUiState.Idle -> null
        is RouteUiState.Editing -> request
        is RouteUiState.Planning -> request
        is RouteUiState.Ready -> request
        is RouteUiState.Insufficient -> request
        is RouteUiState.Error -> request
    }

private const val MINIMUM_STOP_COUNT = 2
private const val MAXIMUM_STOP_COUNT = 5
