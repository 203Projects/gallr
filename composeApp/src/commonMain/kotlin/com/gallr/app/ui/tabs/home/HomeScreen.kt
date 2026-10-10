package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.ui.components.CatalogLoadingState
import com.gallr.app.ui.components.CatalogUnavailableState
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.HomeUiState
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.positionBucket
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import kotlinx.coroutines.launch

/** Where on the home tab an exhibition was shown or tapped, and how analytics classifies that discovery. */
enum class HomeSection(
    val discoveryKind: DiscoveryKind,
) {
    HERO(DiscoveryKind.FEATURED),
    FOR_YOU(DiscoveryKind.RECOMMENDATION),
    EDITOR_PICKS(DiscoveryKind.EDITOR),
    FOLLOWED_GALLERIES(DiscoveryKind.GALLERY),
}

data class HomeOrigin(
    val section: HomeSection,
    val index: Int,
)

/**
 * The home tab (DESIGN.md, Home tab): the city-wide event when one runs, the dated greeting, the featured hero,
 * then rails for the visitor's picks, the editors' picks and followed galleries, and the themed collections.
 * Only sections with content appear, so the page is never a run of empty headers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    recommendations: RecommendationUiState,
    activeEvents: List<Event>,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onExhibitionTap: (Exhibition, HomeOrigin) -> Unit,
    onBookmarkToggle: (Exhibition) -> Unit,
    onImpressions: (HomeSection, List<RankedExhibitionExposure>) -> Unit,
    onEventTap: (String) -> Unit,
    onForYouAll: () -> Unit,
    onCollectionTap: (HomeCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val eventPagerState = rememberPagerState(pageCount = { activeEvents.size })
    val scope = rememberCoroutineScope()
    val showEventChip by remember {
        derivedStateOf { activeEvents.size >= 2 && listState.firstVisibleItemIndex > 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is HomeUiState.Loading -> {
                CatalogLoadingState(lang = lang)
            }

            is HomeUiState.Error -> {
                CatalogUnavailableState(
                    isNetworkError = state.message == "network",
                    lang = lang,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is HomeUiState.Ready -> {
                val copy = homeCopy(lang, state.greetingName)
                val shownElsewhere =
                    (state.feed.hero + state.feed.editorPicks)
                        .map { it.id }
                        .toSet()
                val forYouCards =
                    remember(recommendations, lang, shownElsewhere) {
                        forYouRailCards(recommendations, shownElsewhere, lang)
                    }
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = GallrSpacing.md, bottom = GallrSpacing.xl),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (activeEvents.isNotEmpty()) {
                            item(key = "event-pager") {
                                HomeEventPager(
                                    activeEvents = activeEvents,
                                    pagerState = eventPagerState,
                                    lang = lang,
                                    onEventTap = onEventTap,
                                    modifier = Modifier.padding(bottom = GallrSpacing.lg),
                                )
                            }
                        }
                        item(key = "home-header") {
                            HomeHeader(
                                dateLine = homeDateLine(state.today, lang),
                                greeting = homeGreeting(state.greetingName, lang),
                                modifier = Modifier.padding(bottom = GallrSpacing.lg),
                            )
                        }
                        if (state.feed.isEmpty) {
                            item(key = "home-empty") {
                                GallrEmptyState(
                                    message = copy.emptyMessage,
                                    actionLabel = copy.refresh,
                                    onAction = onRefresh,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (state.feed.hero.isNotEmpty()) {
                            item(key = "home-hero") {
                                HeroPager(
                                    exhibitions = state.feed.hero,
                                    label = copy.heroLabel,
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = { exhibition, index ->
                                        onExhibitionTap(exhibition, HomeOrigin(HomeSection.HERO, index))
                                    },
                                    onBookmarkToggle = onBookmarkToggle,
                                    onPageShown = { exhibition, index ->
                                        onImpressions(
                                            HomeSection.HERO,
                                            listOf(RankedExhibitionExposure(exhibition.id, positionBucket(index))),
                                        )
                                    },
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (forYouCards.isNotEmpty()) {
                            item(key = "home-for-you") {
                                ExhibitionRail(
                                    title = copy.forYouTitle,
                                    subtitle = forYouSubtitle(recommendations, lang),
                                    cards = forYouCards,
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = { exhibition, index ->
                                        onExhibitionTap(exhibition, HomeOrigin(HomeSection.FOR_YOU, index))
                                    },
                                    onBookmarkToggle = onBookmarkToggle,
                                    onImpressions = { onImpressions(HomeSection.FOR_YOU, it) },
                                    action = copy.forYouAction,
                                    onAction = onForYouAll,
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (state.feed.editorPicks.isNotEmpty()) {
                            item(key = "home-editor-picks") {
                                ExhibitionRail(
                                    title = copy.editorPicksTitle,
                                    subtitle = copy.editorPicksSubtitle,
                                    cards = state.feed.editorPicks.map { RailCard(it) },
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = { exhibition, index ->
                                        onExhibitionTap(exhibition, HomeOrigin(HomeSection.EDITOR_PICKS, index))
                                    },
                                    onBookmarkToggle = onBookmarkToggle,
                                    onImpressions = { onImpressions(HomeSection.EDITOR_PICKS, it) },
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (state.feed.fromFollowedGalleries.isNotEmpty()) {
                            item(key = "home-followed") {
                                ExhibitionRail(
                                    title = copy.followedTitle,
                                    subtitle = copy.followedSubtitle,
                                    cards = state.feed.fromFollowedGalleries.map { RailCard(it) },
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = { exhibition, index ->
                                        onExhibitionTap(exhibition, HomeOrigin(HomeSection.FOLLOWED_GALLERIES, index))
                                    },
                                    onBookmarkToggle = onBookmarkToggle,
                                    onImpressions = { onImpressions(HomeSection.FOLLOWED_GALLERIES, it) },
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (state.feed.collections.isNotEmpty()) {
                            item(key = "home-collections") {
                                SectionHeader(title = copy.collectionsTitle, subtitle = copy.collectionsSubtitle)
                                Spacer(Modifier.height(GallrSpacing.md))
                                CollectionGrid(
                                    collections = state.feed.collections,
                                    lang = lang,
                                    onTap = onCollectionTap,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showEventChip) {
            EventRevealChip(
                count = activeEvents.size,
                lang = lang,
                onTap = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = GallrSpacing.sm),
            )
        }
    }
}
