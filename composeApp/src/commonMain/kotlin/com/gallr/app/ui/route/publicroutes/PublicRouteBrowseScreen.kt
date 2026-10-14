package com.gallr.app.ui.route.publicroutes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.components.GallrFilterChip
import com.gallr.app.ui.route.composer.SkeletonRow
import com.gallr.app.ui.route.composer.createPersonalRouteLabel
import com.gallr.app.ui.route.composer.myRoutesRetryLabel
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.BrowseListState
import com.gallr.app.viewmodel.PublicRouteBrowseUiState
import com.gallr.app.viewmodel.PublicRouteBrowseViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.PublicRouteSummary
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource

/** 동선 둘러보기 (spec 092): every listed route, sorted, filtered by district and read twenty at a time. */
@Composable
fun PublicRouteBrowseRoute(
    viewModel: PublicRouteBrowseViewModel,
    language: AppLanguage,
    onOpenRoute: (PublicRouteSummary) -> Unit,
    onMakeRoute: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PublicRouteBrowseScreen(
        state = state,
        language = language,
        onOpenRoute = onOpenRoute,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onRetryMore = viewModel::retryMore,
        onSelectSort = viewModel::selectSort,
        onSelectDistrict = viewModel::selectDistrict,
        onClearDistrict = viewModel::clearDistrict,
        onMakeRoute = onMakeRoute,
        onScrollAnchor = viewModel::saveScrollAnchor,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublicRouteBrowseScreen(
    state: PublicRouteBrowseUiState,
    language: AppLanguage,
    onOpenRoute: (PublicRouteSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryMore: () -> Unit,
    onSelectSort: (PublicRouteSort) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onClearDistrict: () -> Unit,
    onMakeRoute: () -> Unit,
    onScrollAnchor: (index: Int, offset: Int) -> Unit,
    onBack: () -> Unit,
) {
    // A new query starts at the top; coming back from a preview starts where the reader left the list.
    val listState =
        remember(state.query) {
            LazyListState(state.scrollAnchor.index, state.scrollAnchor.offset)
        }
    val saveAnchor by rememberUpdatedState(onScrollAnchor)
    DisposableEffect(listState) {
        onDispose { saveAnchor(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    val list = state.list
    val canLoadMore = list is BrowseListState.Loaded && list.hasMore && !list.appending && !list.appendFailed
    LoadMoreNearTheEnd(listState, canLoadMore, onLoadMore)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BrowseTopBar(language, onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag(BROWSE_LIST_TAG),
            contentPadding = PaddingValues(bottom = GallrSpacing.lg),
        ) {
            item(key = "controls") { BrowseControls(state, language, onSelectSort, onSelectDistrict, onClearDistrict) }
            browseRows(state, language, onOpenRoute, onRetry, onRetryMore, onClearDistrict, onMakeRoute)
        }
    }
}

private fun LazyListScope.browseRows(
    state: PublicRouteBrowseUiState,
    language: AppLanguage,
    onOpenRoute: (PublicRouteSummary) -> Unit,
    onRetry: () -> Unit,
    onRetryMore: () -> Unit,
    onClearDistrict: () -> Unit,
    onMakeRoute: () -> Unit,
) {
    when (val list = state.list) {
        BrowseListState.Loading -> {
            items(SKELETON_ROWS) { Inset { SkeletonRow() } }
        }

        BrowseListState.FirstPageError -> {
            item(key = "error") {
                Inset {
                    GallrErrorMessage(
                        message = publicRoutesLoadFailedMessage(language),
                        actionLabel = myRoutesRetryLabel(language),
                        onAction = onRetry,
                        modifier = Modifier.padding(vertical = GallrSpacing.md),
                    )
                }
            }
        }

        is BrowseListState.Empty -> {
            item(key = "empty") {
                val district =
                    state.query.districtKo?.let { selected -> state.districts.firstOrNull { it.nameKo == selected } }
                if (list.filtered) {
                    BrowseEmptyState(
                        message =
                            district?.let { publicRouteDistrictEmptyMessage(it, language) }
                                ?: publicRoutesNoneYetMessage(language),
                        actionLabel = publicRouteClearFilterLabel(language),
                        onAction = onClearDistrict,
                    )
                } else {
                    BrowseEmptyState(
                        message = publicRoutesNoneYetMessage(language),
                        actionLabel = createPersonalRouteLabel(language),
                        onAction = onMakeRoute,
                    )
                }
            }
        }

        is BrowseListState.Loaded -> {
            items(list.routes, key = { it.summary.id }) { route ->
                BrowseRow(route, state.today ?: route.summary.firstSharedDay, language) { onOpenRoute(route.summary) }
            }
            when {
                list.appending -> {
                    item(key = "appending") { Inset { SkeletonRow() } }
                }

                list.appendFailed -> {
                    item(key = "append-error") {
                        Inset {
                            GallrErrorMessage(
                                message = publicRouteLoadMoreFailedMessage(language),
                                actionLabel = myRoutesRetryLabel(language),
                                onAction = onRetryMore,
                                modifier = Modifier.padding(vertical = GallrSpacing.md),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asks for the next page when the last rows are in view and another page exists, and again when a page arrives that
 * still leaves the end in view, so a short page fills the screen.
 */
@Composable
private fun LoadMoreNearTheEnd(
    listState: LazyListState,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    val enabled by rememberUpdatedState(canLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
            (enabled && nearEnd) to info.totalItemsCount
        }.distinctUntilChanged()
            .filter { (wanted, _) -> wanted }
            .collect { loadMore() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(
    language: AppLanguage,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = publicRouteBrowseTitle(language),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            val backLabel = if (language == AppLanguage.KO) "뒤로" else "Back"
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Icon(painter = painterResource(Res.drawable.ic_arrow_back), contentDescription = null)
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

/** The sort chips, then the district chips beginning with 전체 (US2, US3). */
@Composable
private fun BrowseControls(
    state: PublicRouteBrowseUiState,
    language: AppLanguage,
    onSelectSort: (PublicRouteSort) -> Unit,
    onSelectDistrict: (String) -> Unit,
    onClearDistrict: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.sm)) {
        Row(
            modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            PublicRouteSort.entries.forEach { sort ->
                GallrFilterChip(
                    selected = state.query.sort == sort,
                    onClick = { onSelectSort(sort) },
                    label = publicRouteSortLabel(sort, language),
                )
            }
        }
        if (state.districts.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = GallrSpacing.screenMargin),
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                modifier = Modifier.testTag(DISTRICT_ROW_TAG),
            ) {
                item(key = "all") {
                    GallrFilterChip(
                        selected = state.query.districtKo == null,
                        onClick = onClearDistrict,
                        label = publicRouteAllDistrictsLabel(language),
                    )
                }
                items(state.districts, key = { it.nameKo }) { district ->
                    GallrFilterChip(
                        selected = state.query.districtKo == district.nameKo,
                        onClick = { onSelectDistrict(district.nameKo) },
                        label = district.localizedName(language),
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = GallrSpacing.sm),
        )
    }
}

/** One listed route: name, the 추천 동선 line, then up to three covers; read as one button (FR-004, FR-016). */
@Composable
private fun BrowseRow(
    route: BrowsedPublicRoute,
    today: LocalDate,
    language: AppLanguage,
    onOpen: () -> Unit,
) {
    val description = publicRouteBrowseRowDescription(route.summary, today, language)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(role = Role.Button, onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                }.padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md),
    ) {
        Text(
            text = route.summary.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = MAX_NAME_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = publicRouteRowLine(route.summary, today, language),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = MAX_NAME_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        val covers = browseRowCovers(route)
        if (covers.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = GallrSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
            ) {
                covers.forEach { cover ->
                    AsyncImage(
                        model = nativeSupabaseImageUrl(cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(COVER_SIZE).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The empty list: a line saying why, then one standard black button (no accent in public routes, DESIGN.md). */
@Composable
private fun BrowseEmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.xl),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Button(
            onClick = onAction,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            modifier = Modifier.fillMaxWidth().padding(top = GallrSpacing.md).heightIn(min = ACTION_MIN_HEIGHT),
        ) {
            Text(actionLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Inset(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) { content() }
}

internal const val BROWSE_LIST_TAG = "public-route-browse-list"
internal const val DISTRICT_ROW_TAG = "public-route-browse-districts"
private const val SKELETON_ROWS = 5
private const val LOAD_MORE_THRESHOLD = 5
private const val MAX_NAME_LINES = 2
private val ROW_MIN_HEIGHT = 48.dp
private val COVER_SIZE = 48.dp
private val ACTION_MIN_HEIGHT = 52.dp
