package com.gallr.app.ui.tabs.featured

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.analytics.ExhibitionExposureSession
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.analytics.halfVisibleStableKeys
import com.gallr.app.ui.components.CatalogLoadingState
import com.gallr.app.ui.components.CatalogUnavailableState
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrEventCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturedScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    onRecommendationsTap: () -> Unit = {},
    onBookmarkToggle: (Exhibition) -> Unit = { exhibition ->
        viewModel.toggleBookmark(exhibition.id)
    },
    onExhibitionImpressions: (List<RankedExhibitionExposure>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.featuredState.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvents by viewModel.activeEvents.collectAsState()

    val listState = rememberLazyListState()
    val exposureSession = remember { ExhibitionExposureSession() }
    val currentImpressionCallback by rememberUpdatedState(onExhibitionImpressions)
    val pagerState = rememberPagerState(pageCount = { activeEvents.size })

    // A single card wraps its content (below). The pager can't wrap, so it measures
    // the tallest card's natural height and pins itself to that — matching the
    // wrapping single-card look instead of a fixed tall box. Seeded with the token
    // to avoid a first-frame jump before the measurement lands.
    val density = LocalDensity.current
    var maxCardHeightPx by remember(activeEvents) { mutableIntStateOf(0) }
    val pagerCardHeight =
        if (maxCardHeightPx > 0) {
            with(density) { maxCardHeightPx.toDp() }
        } else {
            GallrEventCard.pagerHeight
        }

    // 4s auto-advance — re-arms on settle, skips while dragging, pauses off-screen.
    val autoCycle = !isReduceMotionOrScreenReaderActive()
    LaunchedEffect(pagerState, autoCycle) {
        if (!autoCycle) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collectLatest {
            if (pagerState.pageCount <= 1) return@collectLatest
            delay(4000)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }

    // Reveal chip visible once the pager has scrolled out of view (2+ events only).
    val showChip by remember {
        derivedStateOf { activeEvents.size >= 2 && listState.firstVisibleItemIndex > 0 }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState, state, exposureSession) {
        exposureSession.updateCatalogue((state as? ExhibitionListState.Success)?.exhibitions.orEmpty().map { it.id })
        snapshotFlow { halfVisibleStableKeys(listState.layoutInfo) }
            .distinctUntilChanged()
            .collect { keys ->
                exposureSession.newlyVisible(keys).takeIf { it.isNotEmpty() }?.let(currentImpressionCallback)
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val s = state) {
            is ExhibitionListState.Loading -> {
                CatalogLoadingState(lang = lang)
            }

            is ExhibitionListState.Error -> {
                CatalogUnavailableState(
                    isNetworkError = s.message == "network",
                    lang = lang,
                    onRetry = viewModel::loadFeaturedExhibitions,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is ExhibitionListState.Success -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(GallrSpacing.md),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (activeEvents.size > 1) {
                            // Zero-height offstage pass: each card is laid out at its natural
                            // height (so onSizeChanged reports it) but the row clips to 0px and
                            // draws nothing, so it adds no visible space. Feeds pagerCardHeight.
                            item(key = "event-pager-measure") {
                                Box(Modifier.height(0.dp).clipToBounds()) {
                                    activeEvents.forEach { ev ->
                                        EventPromotionCard(
                                            event = ev,
                                            lang = lang,
                                            onTap = {},
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                                    .onSizeChanged { size ->
                                                        if (size.height > maxCardHeightPx) {
                                                            maxCardHeightPx = size.height
                                                        }
                                                    },
                                        )
                                    }
                                }
                            }
                        }
                        if (activeEvents.isNotEmpty()) {
                            item(key = "event-pager") {
                                if (activeEvents.size == 1) {
                                    // Single event: let the card wrap its content height,
                                    // exactly like the original (no fixed height, no pager).
                                    EventPromotionCard(
                                        event = activeEvents[0],
                                        lang = lang,
                                        onTap = { onEventTap(activeEvents[0].id) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    // A HorizontalPager can't wrap content — it measures every
                                    // page to one bounded height. Pin it to the tallest card's
                                    // natural height (measured once below) so it reads like the
                                    // wrapping single card instead of a fixed tall box.
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxWidth().height(pagerCardHeight),
                                        verticalAlignment = Alignment.Top,
                                    ) { page ->
                                        EventPromotionCard(
                                            event = activeEvents[page],
                                            lang = lang,
                                            onTap = { onEventTap(activeEvents[page].id) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            if (activeEvents.size > 1) {
                                item(key = "event-pager-dots") {
                                    PagerDots(
                                        count = activeEvents.size,
                                        current = pagerState.currentPage,
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.dotsHeight),
                                    )
                                }
                            }
                        }

                        item(key = "local-recommendations-entry") {
                            LocalRecommendationsEntry(
                                lang = lang,
                                onTap = onRecommendationsTap,
                                modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.md),
                            )
                        }

                        item(key = "featured-header") {
                            Text(
                                text = if (lang == AppLanguage.KO) "추천" else "FEATURED",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(vertical = GallrSpacing.sm),
                            )
                        }

                        if (s.exhibitions.isEmpty()) {
                            item(key = "featured-empty") {
                                GallrEmptyState(
                                    message =
                                        if (lang ==
                                            AppLanguage.KO
                                        ) {
                                            "추천 전시가 없습니다."
                                        } else {
                                            "No featured exhibitions right now."
                                        },
                                    actionLabel = if (lang == AppLanguage.KO) "새로고침" else "Refresh",
                                    onAction = { viewModel.loadFeaturedExhibitions() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { onBookmarkToggle(exhibition) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showChip) {
            RevealChip(
                count = activeEvents.size,
                lang = lang,
                onTap = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = GallrSpacing.sm),
            )
        }
    }
}

@Composable
private fun LocalRecommendationsEntry(
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            modifier
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .clickable(role = Role.Button, onClick = onTap)
                .heightIn(min = 52.dp)
                .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (lang == AppLanguage.KO) "내 취향 추천" else "FOR YOU",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text =
                    if (lang == AppLanguage.KO) {
                        "기기에서만 계산"
                    } else {
                        "COMPUTED ON THIS DEVICE"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun PagerDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            val indicatorColor =
                if (active) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (active) 18.dp else 6.dp)
                        .background(indicatorColor),
            )
        }
    }
}

@Composable
private fun RevealChip(
    count: Int,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (lang == AppLanguage.KO) "${count}개의 이벤트 진행 중" else "$count Events On Now"
    androidx.compose.foundation.layout.Row(
        modifier =
            modifier
                .background(Color.Black)
                .clickable(onClick = onTap)
                .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↑ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
