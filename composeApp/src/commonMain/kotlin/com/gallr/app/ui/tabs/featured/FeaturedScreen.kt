package com.gallr.app.ui.tabs.featured

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.SkeletonCard
import com.gallr.app.ui.theme.GallrEventCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturedScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.featuredState.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvents by viewModel.activeEvents.collectAsState()

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { activeEvents.size })

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

    Box(modifier = modifier.fillMaxSize()) {
        when (val s = state) {
            is ExhibitionListState.Loading -> {
                Column(modifier = Modifier.padding(horizontal = GallrSpacing.md)) {
                    repeat(3) { SkeletonCard(modifier = Modifier.padding(bottom = GallrSpacing.md)) }
                }
            }

            is ExhibitionListState.Error -> {
                GallrEmptyState(
                    message = if (s.message == "network") {
                        if (lang == AppLanguage.KO) "인터넷 연결을 확인해주세요." else "Check your internet connection."
                    } else {
                        if (lang == AppLanguage.KO) "문제가 발생했습니다. 다시 시도해주세요." else "Something went wrong. Please try again."
                    },
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadFeaturedExhibitions() },
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
                        if (activeEvents.isNotEmpty()) {
                            item(key = "event-pager") {
                                if (activeEvents.size == 1) {
                                    EventPromotionCard(
                                        event = activeEvents[0],
                                        lang = lang,
                                        onTap = { onEventTap(activeEvents[0].id) },
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight),
                                    )
                                } else {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight),
                                    ) { page ->
                                        EventPromotionCard(
                                            event = activeEvents[page],
                                            lang = lang,
                                            onTap = { onEventTap(activeEvents[page].id) },
                                            modifier = Modifier.fillMaxSize(),
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
                                    message = if (lang == AppLanguage.KO) "추천 전시가 없습니다." else "No featured exhibitions right now.",
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
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
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
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (active) 18.dp else 6.dp)
                    .background(if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun RevealChip(count: Int, lang: AppLanguage, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (lang == AppLanguage.KO) "${count}개의 아트페어 진행 중" else "$count Art Fairs On Now"
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .background(Color.Black)
            .clickable(onClick = onTap)
            .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↑ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
