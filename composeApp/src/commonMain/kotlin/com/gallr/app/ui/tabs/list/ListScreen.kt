package com.gallr.app.ui.tabs.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.analytics.ExhibitionExposureSession
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.analytics.halfVisibleStableKeys
import com.gallr.app.ui.components.CatalogLoadingState
import com.gallr.app.ui.components.CatalogUnavailableState
import com.gallr.app.ui.components.EventListBanner
import com.gallr.app.ui.components.EventTreatment
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.rememberCyclingIndex
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.GalleryCandidate
import com.gallr.app.viewmodel.GallerySearchResult
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.app.viewmodel.gallerySearchResults
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.RegionWithCount
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.util.parseHexColor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    onEditorsChipTap: () -> Unit,
    onPromotedExhibitionTap: (Exhibition) -> Unit = onExhibitionTap,
    onBookmarkToggle: (Exhibition) -> Unit = { exhibition ->
        viewModel.toggleBookmark(exhibition.id)
    },
    onExhibitionImpressions: (List<RankedExhibitionExposure>) -> Unit = {},
    visitedExhibitionIds: Set<String> = emptySet(),
    followedGalleryKeys: Set<String> = emptySet(),
    followedGalleryIds: Set<String> = emptySet(),
    onGalleryTap: (GalleryCandidate) -> Unit = {},
    onFollowGallery: (GalleryCandidate) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.listScreenState.collectAsState()
    val filter = uiState.filter
    val state = uiState.exhibitions
    val bookmarkedIds = uiState.bookmarkedIds
    val lang = uiState.language
    val selectedCity = uiState.selectedCity
    val distinctCities = uiState.cities
    val distinctRegions = uiState.regions
    val tabExhibitionCount = uiState.tabExhibitionCount
    val showMyListOnly = uiState.showMyListOnly
    val searchQuery = uiState.searchQuery
    val isRefreshing = uiState.isRefreshing
    val activeEvents = uiState.activeEvents
    val promotedExhibition = uiState.promotedExhibition
    val catalogueState by viewModel.allExhibitions.collectAsState()
    val galleryResults =
        (catalogueState as? ExhibitionListState.Success)
            ?.exhibitions
            .orEmpty()
            .gallerySearchResults(
                query = searchQuery,
                language = lang,
                visitedExhibitionIds = visitedExhibitionIds,
            )

    val hasActiveFilters = filter != FilterState() || selectedCity != null

    val selectedTabIndex = if (showMyListOnly) 1 else 0

    val focusManager = LocalFocusManager.current
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // ── Scroll-direction tracking for collapsible filters ────────────────
    val listState = rememberLazyListState()
    val exposureSession =
        remember(showMyListOnly, searchQuery, filter, selectedCity) {
            ExhibitionExposureSession()
        }
    val currentImpressionCallback by rememberUpdatedState(onExhibitionImpressions)
    var filtersVisible by remember { mutableStateOf(true) }
    LaunchedEffect(listState, state, exposureSession) {
        val exhibitionIds = (state as? ExhibitionListState.Success)?.exhibitions.orEmpty().map { it.id }
        exposureSession.updateCatalogue(exhibitionIds)
        snapshotFlow { halfVisibleStableKeys(listState.layoutInfo) }
            .distinctUntilChanged()
            .collect { keys ->
                exposureSession.newlyVisible(keys).takeIf { it.isNotEmpty() }?.let(currentImpressionCallback)
            }
    }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    val filterScrollConnection =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Only a real drag may change the header. The previous implementation
                    // inferred direction from LazyListState, so the header's own size
                    // animation looked like an upward scroll and reopened it near the end.
                    if (source == NestedScrollSource.UserInput) {
                        filtersVisible =
                            filterVisibilityAfterUserScroll(
                                currentlyVisible = filtersVisible,
                                scrollDeltaY = available.y,
                                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            )
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    filtersVisible =
                        filterVisibilityAfterUserScroll(
                            currentlyVisible = filtersVisible,
                            scrollDeltaY = available.y,
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        )
                    return Velocity.Zero
                }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .nestedScroll(filterScrollConnection)
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        // ── Cycling event banner — shows all active events in one 36dp slot ──
        if (activeEvents.isNotEmpty()) {
            CyclingEventBanner(
                events = activeEvents,
                lang = lang,
                onEventTap = onEventTap,
            )
        }

        // ── Tab toggle: All Exhibitions / My List ─────────────────────────
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                    color = GallrAccent.activeIndicator,
                )
            },
            divider = {},
        ) {
            Tab(
                selected = !showMyListOnly,
                onClick = { viewModel.setShowMyListOnly(false) },
                text = {
                    Text(
                        text = if (lang == AppLanguage.KO) "전체 전시" else "All Exhibitions",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            Tab(
                selected = showMyListOnly,
                onClick = { viewModel.setShowMyListOnly(true) },
                text = {
                    Text(
                        text = if (lang == AppLanguage.KO) "내 전시" else "My List",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }

        // ── Collapsible filter section (hides on scroll down) ────────────
        AnimatedVisibility(
            visible = filtersVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                // ── Compact search bar with magnifier icon ──────────────────────
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = {
                        Text(
                            text = if (lang == AppLanguage.KO) "전시 검색..." else "Search exhibitions...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.setSearchQuery("") },
                            ) {
                                Text(
                                    text = "✕",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text = "⌕",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = GallrSpacing.sm),
                            )
                        }
                    },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    textStyle = MaterialTheme.typography.labelLarge,
                    shape = RectangleShape,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            cursorColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 48.dp)
                            .padding(horizontal = GallrSpacing.screenMargin),
                )

                // ── Country + city chips (single scrollable row) ─────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.xs),
                ) {
                    CountryDropdown(lang = lang)
                    Spacer(Modifier.width(GallrSpacing.sm))
                    GallrFilterChip(
                        selected = selectedCity == null,
                        onClick = { viewModel.setCity(null) },
                        label = "${if (lang == AppLanguage.KO) "전체" else "All"} ($tabExhibitionCount)",
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    distinctCities.forEach { city ->
                        GallrFilterChip(
                            selected = selectedCity == city.cityKo,
                            onClick = { viewModel.setCity(city.cityKo) },
                            label = "${if (lang == AppLanguage.KO) {
                                city.cityKo
                            } else {
                                city.cityEn.ifEmpty {
                                    city.cityKo
                                }
                            }} (${city.count})",
                        )
                        Spacer(Modifier.width(GallrSpacing.sm))
                    }
                }

                // ── Region sub-filter chips (visible when city selected) ────────
                if (distinctRegions.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = GallrSpacing.screenMargin),
                    ) {
                        GallrFilterChip(
                            selected = filter.regions.isEmpty(),
                            onClick = { viewModel.clearRegions() },
                            label = if (lang == AppLanguage.KO) "전체" else "All",
                            small = true,
                        )
                        Spacer(Modifier.width(GallrSpacing.sm))
                        distinctRegions.forEach { region ->
                            GallrFilterChip(
                                selected = region.regionKo in filter.regions,
                                onClick = { viewModel.toggleRegion(region.regionKo) },
                                label = "${if (lang == AppLanguage.KO) {
                                    region.regionKo
                                } else {
                                    region.regionEn.ifEmpty {
                                        region.regionKo
                                    }
                                }} (${region.count})",
                                small = true,
                            )
                            Spacer(Modifier.width(GallrSpacing.sm))
                        }
                    }
                }

                // ── Filter chips (single horizontally scrollable row) ────────────
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = GallrSpacing.screenMargin),
                ) {
                    GallrFilterChip(
                        selected = false,
                        onClick = onEditorsChipTap,
                        label = if (lang == AppLanguage.KO) "에디터 ›" else "EDITORS ›",
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    activeEvents.forEach { event ->
                        val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                        GallrEventFilterChip(
                            selected = filter.selectedEventId == event.id,
                            onClick = { viewModel.toggleEventFilter(event.id) },
                            label = event.localizedName(lang),
                            brandColor = brand,
                        )
                        Spacer(Modifier.width(GallrSpacing.sm))
                    }
                    GallrFilterChip(
                        selected = filter.showFeatured,
                        onClick = { viewModel.updateFilter { copy(showFeatured = !showFeatured) } },
                        label = if (lang == AppLanguage.KO) "추천" else "FEATURED",
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    GallrFilterChip(
                        selected = filter.openingThisWeek,
                        onClick = { viewModel.updateFilter { copy(openingThisWeek = !openingThisWeek) } },
                        label = if (lang == AppLanguage.KO) "이번 주 오픈" else "OPENING THIS WEEK",
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    GallrFilterChip(
                        selected = filter.closingThisWeek,
                        onClick = { viewModel.updateFilter { copy(closingThisWeek = !closingThisWeek) } },
                        label = if (lang == AppLanguage.KO) "이번 주 종료" else "CLOSING THIS WEEK",
                    )
                }

                // ── Action buttons ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
                ) {
                    if (hasActiveFilters) {
                        TextButton(onClick = { viewModel.clearAllFilters() }) {
                            Text(
                                text = if (lang == AppLanguage.KO) "필터 초기화" else "Clear Filters",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (showMyListOnly && bookmarkedIds.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearAllBookmarks() }) {
                            Text(
                                text = if (lang == AppLanguage.KO) "내 전시 비우기" else "Clear My List",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!hasActiveFilters && !(showMyListOnly && bookmarkedIds.isNotEmpty())) {
                    Spacer(Modifier.height(GallrSpacing.xs))
                }
            } // end Column inside AnimatedVisibility
        } // end AnimatedVisibility

        // ── Exhibition list ───────────────────────────────────────────────
        when (val s = state) {
            is ExhibitionListState.Loading -> {
                CatalogLoadingState(lang = lang)
            }

            is ExhibitionListState.Error -> {
                CatalogUnavailableState(
                    isNetworkError = s.message == "network",
                    lang = lang,
                    onRetry = viewModel::loadAllExhibitions,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is ExhibitionListState.Success -> {
                if (s.exhibitions.isEmpty() && galleryResults.isEmpty()) {
                    val cityName =
                        selectedCity?.let { city ->
                            if (lang == AppLanguage.KO) {
                                city
                            } else {
                                distinctCities.firstOrNull { it.cityKo == city }?.cityEn?.ifEmpty { city } ?: city
                            }
                        }
                    GallrEmptyState(
                        message =
                            when {
                                searchQuery.isNotBlank() -> {
                                    if (lang == AppLanguage.KO) "검색 결과가 없습니다." else "No results found."
                                }

                                showMyListOnly && bookmarkedIds.isEmpty() -> {
                                    if (lang == AppLanguage.KO) {
                                        "저장한 전시가 없습니다.\n전시를 북마크하면 여기에 표시됩니다."
                                    } else {
                                        "No saved exhibitions yet.\nBookmark exhibitions to see them here."
                                    }
                                }

                                showMyListOnly -> {
                                    if (lang == AppLanguage.KO) {
                                        "필터에 맞는 저장 전시가 없습니다."
                                    } else {
                                        "No saved exhibitions match the current filters."
                                    }
                                }

                                filter.selectedEventId != null && activeEvents.isNotEmpty() -> {
                                    if (lang == AppLanguage.KO) {
                                        "선택한 이벤트에 참여하는 전시가 없습니다."
                                    } else {
                                        "No exhibitions in the selected event."
                                    }
                                }

                                cityName != null -> {
                                    if (lang == AppLanguage.KO) {
                                        "${cityName}에 전시가 없습니다."
                                    } else {
                                        "No exhibitions in $cityName."
                                    }
                                }

                                else -> {
                                    if (lang == AppLanguage.KO) {
                                        "필터에 맞는 전시가 없습니다."
                                    } else {
                                        "No exhibitions match the current filters."
                                    }
                                }
                            },
                        actionLabel =
                            if (showMyListOnly && bookmarkedIds.isEmpty()) {
                                null
                            } else if (lang == AppLanguage.KO) {
                                "필터 초기화"
                            } else {
                                "Clear Filters"
                            },
                        onAction = {
                            viewModel.clearAllFilters()
                            viewModel.setSearchQuery("")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = listScreenContentPadding(navBarInset),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (galleryResults.isNotEmpty()) {
                                item(key = "gallery-search-header") {
                                    Text(
                                        text = if (lang == AppLanguage.KO) "갤러리" else "GALLERIES",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(bottom = GallrSpacing.sm),
                                    )
                                }
                                items(
                                    galleryResults,
                                    key = {
                                        "gallery:${it.candidate.galleryId ?: it.candidate.galleryKey}"
                                    },
                                ) { result ->
                                    val candidate = result.candidate
                                    val isFollowing =
                                        candidate.galleryKey in followedGalleryKeys ||
                                            (candidate.galleryId != null && candidate.galleryId in followedGalleryIds)
                                    GallerySearchResultRow(
                                        result = result,
                                        lang = lang,
                                        isFollowing = isFollowing,
                                        onOpen = { onGalleryTap(candidate) },
                                        onFollow = { onFollowGallery(candidate) },
                                        modifier = Modifier.padding(bottom = GallrSpacing.md),
                                    )
                                }
                                if (s.exhibitions.isNotEmpty()) {
                                    item(key = "exhibition-search-header") {
                                        Text(
                                            text = if (lang == AppLanguage.KO) "전시" else "EXHIBITIONS",
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(bottom = GallrSpacing.sm),
                                        )
                                    }
                                }
                            }
                            promotedExhibition
                                ?.takeIf { !showMyListOnly && selectedCity != null }
                                ?.let { promotion ->
                                    viewModel
                                        .findExhibitionById(promotion.exhibitionId)
                                        ?.let { canonicalExhibition ->
                                            item(key = "paid-promotion:${promotion.promotionId}") {
                                                PromotedExhibitionBand(
                                                    promotion = promotion,
                                                    exhibition = canonicalExhibition,
                                                    lang = lang,
                                                    onOpen = { onPromotedExhibitionTap(canonicalExhibition) },
                                                    modifier = Modifier.padding(bottom = GallrSpacing.md),
                                                )
                                            }
                                        }
                                }
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                val treatment =
                                    remember(activeEvents, exhibition.eventId, lang) {
                                        activeEvents
                                            .firstOrNull { exhibition.eventId == it.id }
                                            ?.let { event ->
                                                val brand =
                                                    parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                                                EventTreatment(
                                                    brandColor = brand,
                                                    label = event.ribbonLabel(lang),
                                                )
                                            }
                                    }
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { onBookmarkToggle(exhibition) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = GallrSpacing.md),
                                    eventTreatment = treatment,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GallerySearchResultRow(
    result: GallerySearchResult,
    lang: AppLanguage,
    isFollowing: Boolean,
    onOpen: () -> Unit,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidate = result.candidate
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .clickable(onClick = onOpen)
                .padding(GallrSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.snapshot.localizedName(lang).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = candidate.snapshot.localizedLocation(lang).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.visitedCount > 0) {
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text =
                        if (lang == AppLanguage.KO) {
                            "${result.visitedCount}개 전시 방문"
                        } else {
                            "${result.visitedCount} EXHIBITIONS VISITED"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(GallrSpacing.sm))
        OutlinedButton(
            onClick = onFollow,
            enabled = !isFollowing,
            shape = RectangleShape,
            modifier = Modifier.height(40.dp),
        ) {
            Text(
                text =
                    if (isFollowing) {
                        if (lang == AppLanguage.KO) "팔로잉" else "FOLLOWING"
                    } else {
                        if (lang == AppLanguage.KO) "팔로우" else "FOLLOW"
                    },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
