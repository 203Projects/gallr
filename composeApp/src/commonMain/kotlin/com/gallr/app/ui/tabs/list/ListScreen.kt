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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
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
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.RegionWithCount
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import coil3.compose.AsyncImage
import com.gallr.shared.util.parseHexColor
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    onEditorsChipTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter by viewModel.filterState.collectAsState()
    val state by viewModel.filteredExhibitions.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val distinctCities by viewModel.distinctCities.collectAsState()
    val distinctRegions by viewModel.distinctRegions.collectAsState()
    val showMyListOnly by viewModel.showMyListOnly.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvents by viewModel.activeEvents.collectAsState()
    val promotedExhibition by viewModel.promotedExhibition.collectAsState()

    val hasActiveFilters = filter != FilterState() || selectedCity != null

    val selectedTabIndex = if (showMyListOnly) 1 else 0

    val focusManager = LocalFocusManager.current
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // ── Scroll-direction tracking for collapsible filters ────────────────
    val listState = rememberLazyListState()
    var filtersVisible by remember { mutableStateOf(true) }

    val filterScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Only a real drag may change the header. The previous implementation
                // inferred direction from LazyListState, so the header's own size
                // animation looked like an upward scroll and reopened it near the end.
                if (source == NestedScrollSource.UserInput) {
                    filtersVisible = filterVisibilityAfterUserScroll(
                        currentlyVisible = filtersVisible,
                        scrollDeltaY = available.y,
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    )
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                filtersVisible = filterVisibilityAfterUserScroll(
                    currentlyVisible = filtersVisible,
                    scrollDeltaY = available.y,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                )
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = modifier
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
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            indicator = { tabPositions ->
                if (selectedTabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
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
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                focusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 48.dp)
                .padding(horizontal = GallrSpacing.screenMargin),
        )

        // ── Country + city chips (single scrollable row) ─────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.xs),
        ) {
            CountryDropdown(lang = lang)
            Spacer(Modifier.width(GallrSpacing.sm))
            GallrFilterChip(
                selected = selectedCity == null,
                onClick = { viewModel.setCity(null) },
                label = if (lang == AppLanguage.KO) "전체" else "All",
            )
            Spacer(Modifier.width(GallrSpacing.sm))
            distinctCities.forEach { city ->
                GallrFilterChip(
                    selected = selectedCity == city.cityKo,
                    onClick = { viewModel.setCity(city.cityKo) },
                    label = "${if (lang == AppLanguage.KO) city.cityKo else city.cityEn.ifEmpty { city.cityKo }} (${city.count})",
                )
                Spacer(Modifier.width(GallrSpacing.sm))
            }
        }

        // ── Region sub-filter chips (visible when city selected) ────────
        if (distinctRegions.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
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
                        label = "${if (lang == AppLanguage.KO) region.regionKo else region.regionEn.ifEmpty { region.regionKo }} (${region.count})",
                        small = true,
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                }
            }
        }

        // ── Filter chips (single horizontally scrollable row) ────────────
        Row(
            modifier = Modifier
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
                if (s.exhibitions.isEmpty()) {
                    val cityName = selectedCity?.let { city ->
                        if (lang == AppLanguage.KO) city
                        else distinctCities.firstOrNull { it.cityKo == city }?.cityEn?.ifEmpty { city } ?: city
                    }
                    GallrEmptyState(
                        message = when {
                            searchQuery.isNotBlank() ->
                                if (lang == AppLanguage.KO) "검색 결과가 없습니다." else "No results found."
                            showMyListOnly && bookmarkedIds.isEmpty() ->
                                if (lang == AppLanguage.KO) "저장한 전시가 없습니다.\n전시를 북마크하면 여기에 표시됩니다."
                                else "No saved exhibitions yet.\nBookmark exhibitions to see them here."
                            showMyListOnly ->
                                if (lang == AppLanguage.KO) "필터에 맞는 저장 전시가 없습니다."
                                else "No saved exhibitions match the current filters."
                            filter.selectedEventId != null && activeEvents.isNotEmpty() ->
                                if (lang == AppLanguage.KO) "선택한 이벤트에 참여하는 전시가 없습니다."
                                else "No exhibitions in the selected event."
                            cityName != null ->
                                if (lang == AppLanguage.KO) "${cityName}에 전시가 없습니다."
                                else "No exhibitions in $cityName."
                            else ->
                                if (lang == AppLanguage.KO) "필터에 맞는 전시가 없습니다."
                                else "No exhibitions match the current filters."
                        },
                        actionLabel = if (showMyListOnly && bookmarkedIds.isEmpty()) null
                            else if (lang == AppLanguage.KO) "필터 초기화" else "Clear Filters",
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
                            promotedExhibition
                                ?.takeIf { !showMyListOnly && selectedCity != null }
                                ?.let { promotion ->
                                    viewModel.findExhibitionById(promotion.exhibitionId)
                                        ?.let { canonicalExhibition ->
                                            item(key = "paid-promotion:${promotion.promotionId}") {
                                                PromotedExhibitionBand(
                                                    promotion = promotion,
                                                    exhibition = canonicalExhibition,
                                                    lang = lang,
                                                    onOpen = { onExhibitionTap(canonicalExhibition) },
                                                    modifier = Modifier.padding(bottom = GallrSpacing.md),
                                                )
                                            }
                                        }
                                }
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                val treatment = remember(activeEvents, exhibition.eventId, lang) {
                                    activeEvents
                                        .firstOrNull { exhibition.eventId == it.id }
                                        ?.let { event ->
                                            val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                                            EventTreatment(
                                                brandColor = brand,
                                                label = event.ribbonLabel(lang),
                                            )
                                        }
                                }
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier
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
private fun PromotedExhibitionBand(
    promotion: PromotedExhibition,
    exhibition: Exhibition,
    lang: AppLanguage,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var explanationVisible by remember(promotion.promotionId) { mutableStateOf(false) }
    val name = if (lang == AppLanguage.KO) promotion.nameKo else promotion.nameEn.ifEmpty { promotion.nameKo }
    val venue = if (lang == AppLanguage.KO) promotion.venueNameKo else promotion.venueNameEn.ifEmpty { promotion.venueNameKo }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .padding(GallrSpacing.md),
    ) {
        Text(
            text = if (lang == AppLanguage.KO) "내 주변 프로모션" else "PROMOTED NEAR YOU",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(GallrSpacing.xs))
        Text(
            text = if (lang == AppLanguage.KO) "유료 광고 · 하루 한 번만 표시" else "PAID AD · SHOWN AT MOST ONCE A DAY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = nativeSupabaseImageUrl(exhibition.coverImageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .height(136.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(GallrSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = venue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(GallrSpacing.md))
                Button(
                    onClick = onOpen,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        text = if (lang == AppLanguage.KO) "전시 보기" else "VIEW EXHIBITION",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        TextButton(
            onClick = { explanationVisible = !explanationVisible },
            shape = RectangleShape,
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "왜 이 광고가 보이나요?" else "Why am I seeing this?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (explanationVisible) {
            Text(
                text = if (lang == AppLanguage.KO) {
                    "선택한 지역에서 열리는 전시의 유료 광고입니다. 추천 전시와 검색 순위에는 영향을 주지 않습니다."
                } else {
                    "This is a paid placement for an exhibition in your selected area. It does not affect Featured or search ranking."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun listScreenContentPadding(navigationBarInset: Dp): PaddingValues = PaddingValues(
    start = GallrSpacing.md,
    top = GallrSpacing.md,
    end = GallrSpacing.md,
    bottom = GallrSpacing.md + navigationBarInset,
)

internal fun filterVisibilityAfterUserScroll(
    currentlyVisible: Boolean,
    scrollDeltaY: Float,
    firstVisibleItemIndex: Int,
): Boolean = when {
    scrollDeltaY > 0f -> true
    scrollDeltaY < 0f && firstVisibleItemIndex > 0 -> false
    else -> currentlyVisible
}

// ── Country dropdown ─────────────────────────────────────────────────────────

@Composable
private fun CountryDropdown(lang: AppLanguage) {
    var expanded by remember { mutableStateOf(false) }
    val countries = listOf("대한민국" to "South Korea")
    val selected = countries.first()

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = (if (lang == AppLanguage.KO) selected.first else selected.second) + " ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline,
            ),
            shape = RectangleShape,
        ) {
            countries.forEach { (ko, en) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (lang == AppLanguage.KO) ko else en,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

// ── Filter chip ──────────────────────────────────────────────────────────────

@Composable
private fun GallrFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RectangleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = MaterialTheme.colorScheme.onBackground,
            selectedContainerColor = GallrAccent.activeIndicator,
            selectedLabelColor = MaterialTheme.colorScheme.background,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = GallrAccent.activeIndicator,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

// ── Event filter chip (Phase 2b) ─────────────────────────────────────────────

@Composable
private fun GallrEventFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    brandColor: Color,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RectangleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = brandColor,
            selectedContainerColor = brandColor,
            selectedLabelColor = Color.White,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = brandColor,
            selectedBorderColor = brandColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

// ── Cycling event banner ─────────────────────────────────────────────────────

/** Auto-cycle interval for the list banner. Shared by the index timer
 *  ([rememberCyclingIndex]) and the progress-bar tween so they can't drift apart. */
private const val LIST_BANNER_INTERVAL_MS = 3500L

@Composable
private fun CyclingEventBanner(
    events: List<Event>,
    lang: AppLanguage,
    onEventTap: (String) -> Unit,
) {
    val cycling = rememberCyclingIndex(events.size, intervalMillis = LIST_BANNER_INTERVAL_MS)
    val idx = cycling.index
    val current = events[idx]
    val autoCycle = !isReduceMotionOrScreenReaderActive()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(idx, events.size, autoCycle) {
        progress.snapTo(0f)
        if (events.size > 1 && autoCycle) {
            progress.animateTo(1f, animationSpec = tween(durationMillis = LIST_BANNER_INTERVAL_MS.toInt(), easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(events.size) {
                awaitEachGesture {
                    awaitFirstDown()
                    var dx = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        dx += e.changes.sumOf { it.positionChange().x.toDouble() }.toFloat()
                        if (e.changes.none { it.pressed }) break
                    }
                    val threshold = viewConfiguration.touchSlop * 2.5f
                    // A swipe in either direction advances to the next event (the banner
                    // cycles one way) and works even when auto-cycle is gated off for
                    // reduced motion; a tap (no meaningful horizontal travel) opens detail.
                    if (abs(dx) > threshold) cycling.advance() else onEventTap(current.id)
                }
            },
    ) {
        Crossfade(targetState = current, animationSpec = tween(180)) { ev ->
            EventListBanner(event = ev, lang = lang, modifier = Modifier.fillMaxSize())
        }
        if (events.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.value)
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
