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
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.HomeUiState
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.analytics.DiscoveryKind
import com.gallr.shared.analytics.positionBucket
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.coroutines.launch

/** Where on the home tab an exhibition was shown or tapped, and how analytics classifies that discovery. */
enum class HomeSection(
    val discoveryKind: DiscoveryKind,
) {
    HERO(DiscoveryKind.FEATURED),
    FOR_YOU(DiscoveryKind.RECOMMENDATION),
    EDITOR_PICKS(DiscoveryKind.EDITOR),
    FOLLOWED_GALLERIES(DiscoveryKind.GALLERY),
    CURATOR_EYE(DiscoveryKind.CURATOR_NOTE),
}

data class HomeOrigin(
    val section: HomeSection,
    val index: Int,
)

/**
 * The home tab (DESIGN.md, Home tab): the city-wide event when one runs, the greeting, then the leading banner
 * (the visitor's picks when they rest on their own history, otherwise the featured list), the curator's eye, the
 * routes getting attention, rails for the remaining lists, the curation grid and the neighbourhood and theme rail.
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
    onRouteTap: (PublicRouteSummary) -> Unit,
    onSeeAllRoutes: () -> Unit,
    modifier: Modifier = Modifier,
    /** The recommended-update banner above everything else (spec 098 US3); null hides it. */
    updateNudge: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val eventPagerState = rememberPagerState(pageCount = { activeEvents.size })
    val scope = rememberCoroutineScope()
    val activeEventCount = activeEvents.size
    val showEventChip by
        remember(activeEventCount) {
            derivedStateOf { activeEventCount >= 2 && listState.firstVisibleItemIndex > 0 }
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
                val lead = homeLead(recommendations)
                val shownElsewhere =
                    (state.feed.hero + state.feed.editorPicks)
                        .map { it.id }
                        .toSet()
                val forYouCards =
                    remember(recommendations, lang, shownElsewhere) {
                        forYouRailCards(recommendations, shownElsewhere, lang)
                    }
                val featuredCards =
                    remember(state.feed.hero, copy.heroLabel) {
                        state.feed.hero.map { RailCard(it, eyebrow = copy.heroLabel) }
                    }
                val tasteTerms =
                    remember(recommendations, lang) {
                        (recommendations as? RecommendationUiState.Ready)
                            ?.tasteTerms
                            .orEmpty()
                            .take(TASTE_CHIP_LIMIT)
                            .map { it.localizedName(lang) }
                    }
                val curations = remember(state.feed.collections) { curationTiles(state.feed.collections) }
                val themes = remember(state.feed.collections) { themeTiles(state.feed.collections) }
                val forYouLeads = lead == HomeLead.FOR_YOU && forYouCards.isNotEmpty()
                val onForYouTap = { exhibition: Exhibition, index: Int ->
                    onExhibitionTap(exhibition, HomeOrigin(HomeSection.FOR_YOU, index))
                }
                val onFeaturedTap = { exhibition: Exhibition, index: Int ->
                    onExhibitionTap(exhibition, HomeOrigin(HomeSection.HERO, index))
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
                        if (updateNudge != null) {
                            item(key = "update-nudge") {
                                updateNudge()
                                Spacer(Modifier.height(GallrSpacing.md))
                            }
                        }
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
                        if (forYouLeads) {
                            item(key = "home-for-you-banner") {
                                SectionHeader(
                                    title = copy.forYouTitle,
                                    subtitle = forYouSubtitle(recommendations, lang),
                                    action = copy.forYouAction,
                                    onAction = onForYouAll,
                                )
                                Spacer(Modifier.height(GallrSpacing.md))
                                BannerPager(
                                    cards = forYouCards,
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = onForYouTap,
                                    onBookmarkToggle = onBookmarkToggle,
                                    onPageShown = { exhibition, index ->
                                        onImpressions(
                                            HomeSection.FOR_YOU,
                                            listOf(RankedExhibitionExposure(exhibition.id, positionBucket(index))),
                                        )
                                    },
                                )
                                if (tasteTerms.isNotEmpty()) {
                                    Spacer(Modifier.height(GallrSpacing.md))
                                    TasteChips(label = copy.tasteLabel, terms = tasteTerms)
                                }
                                Spacer(Modifier.height(GallrSpacing.xl))
                            }
                        } else if (featuredCards.isNotEmpty()) {
                            item(key = "home-hero") {
                                BannerPager(
                                    cards = featuredCards,
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = onFeaturedTap,
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
                        if (state.feed.curatorEye.isNotEmpty()) {
                            item(key = "home-curator-eye") {
                                CuratorEyePager(
                                    cards = state.feed.curatorEye,
                                    title = copy.curatorEyeTitle,
                                    subtitle = copy.curatorEyeSubtitle,
                                    lang = lang,
                                    onTap = { card, index ->
                                        onExhibitionTap(card.exhibition, HomeOrigin(HomeSection.CURATOR_EYE, index))
                                    },
                                    onPageShown = { card, index ->
                                        onImpressions(
                                            HomeSection.CURATOR_EYE,
                                            listOf(RankedExhibitionExposure(card.exhibition.id, positionBucket(index))),
                                        )
                                    },
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (!forYouLeads && forYouCards.isNotEmpty()) {
                            item(key = "home-for-you") {
                                ExhibitionRail(
                                    title = copy.forYouTitle,
                                    subtitle = forYouSubtitle(recommendations, lang),
                                    cards = forYouCards,
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = onForYouTap,
                                    onBookmarkToggle = onBookmarkToggle,
                                    onImpressions = { onImpressions(HomeSection.FOR_YOU, it) },
                                    action = copy.forYouAction,
                                    onAction = onForYouAll,
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (state.feed.routes.isNotEmpty()) {
                            item(key = "home-routes") {
                                RoutesRail(
                                    title = copy.routesTitle,
                                    subtitle = copy.routesSubtitle,
                                    routes = state.feed.routes,
                                    lang = lang,
                                    onRouteTap = onRouteTap,
                                    action = copy.routesAction,
                                    onAction = onSeeAllRoutes,
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (forYouLeads && state.feed.hero.isNotEmpty()) {
                            item(key = "home-featured-rail") {
                                ExhibitionRail(
                                    title = copy.heroLabel,
                                    subtitle = copy.featuredSubtitle,
                                    cards = state.feed.hero.map { RailCard(it) },
                                    lang = lang,
                                    bookmarkedIds = bookmarkedIds,
                                    onTap = onFeaturedTap,
                                    onBookmarkToggle = onBookmarkToggle,
                                    onImpressions = { onImpressions(HomeSection.HERO, it) },
                                    showDots = true,
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
                                    showDots = true,
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
                                    showDots = true,
                                    modifier = Modifier.padding(bottom = GallrSpacing.xl),
                                )
                            }
                        }
                        if (curations.isNotEmpty()) {
                            item(key = "home-curations") {
                                SectionHeader(title = copy.curationTitle, subtitle = copy.curationSubtitle)
                                Spacer(Modifier.height(GallrSpacing.md))
                                CurationGrid(collections = curations, lang = lang, onTap = onCollectionTap)
                                Spacer(Modifier.height(GallrSpacing.xl))
                            }
                        }
                        if (themes.isNotEmpty()) {
                            item(key = "home-themes") {
                                SectionHeader(title = copy.themesTitle, subtitle = copy.themesSubtitle)
                                Spacer(Modifier.height(GallrSpacing.md))
                                ThemeRail(collections = themes, lang = lang, onTap = onCollectionTap)
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

/** A handful of chips reads as a taste; more reads as a filter bar. */
private const val TASTE_CHIP_LIMIT = 5
