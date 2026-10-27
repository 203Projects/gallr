package com.gallr.app.ui.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.analytics.ExhibitionExposureSession
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.analytics.RecommendationDisplayAnalyticsGate
import com.gallr.app.analytics.halfVisibleStableKeys
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.HeroExhibitionCard
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.components.SkeletonCard
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsScreen(
    state: RecommendationUiState,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onBookmarkToggle: (Exhibition) -> Unit,
    onExhibitionTap: (Exhibition, Int) -> Unit,
    onImpressions: (List<RankedExhibitionExposure>) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRecommendationsShown: (runId: Long, resultCount: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    /** Exhibitions the visitor hid with 덜 보기; they stay, dimmed, until the list is read again (spec 101 US3). */
    hiddenIds: Set<String> = emptySet(),
    onLessLikeThis: (Exhibition) -> Unit = {},
    onUndoLessLikeThis: (String) -> Unit = {},
    onShare: (Exhibition) -> Unit = {},
    /** Opens the taste sheet to change the picks; the taste tags and their 편집 chip call it (spec 101 FR-005). */
    onEditTaste: (() -> Unit)? = null,
) {
    val copy = recommendationScreenCopy(lang)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val screenReaderActive = isReduceMotionOrScreenReaderActive()
    val hideCard = { exhibition: Exhibition ->
        onLessLikeThis(exhibition)
        scope.launch {
            showLessLikeThisSnackbar(snackbarHostState, lang, screenReaderActive) { onUndoLessLikeThis(exhibition.id) }
        }
        Unit
    }
    val ready = state as? RecommendationUiState.Ready
    val presentations = recommendationCardPresentations(ready?.items.orEmpty(), lang)
    val sections = recommendationSections(ready?.items.orEmpty(), lang)
    val listState = rememberLazyListState()
    val exposureSession = remember { ExhibitionExposureSession() }
    val displayAnalyticsGate = remember { RecommendationDisplayAnalyticsGate() }
    val currentImpressionCallback by rememberUpdatedState(onImpressions)
    val currentShownCallback by rememberUpdatedState(onRecommendationsShown)

    LaunchedEffect(state, displayAnalyticsGate) {
        val ready = state as? RecommendationUiState.Ready ?: return@LaunchedEffect
        if (displayAnalyticsGate.shouldRecord()) {
            currentShownCallback(ready.runId, presentations.size)
        }
    }

    LaunchedEffect(listState, state, exposureSession) {
        exposureSession.updateCatalogue(presentations.map { it.exhibition.id })
        snapshotFlow { halfVisibleStableKeys(listState.layoutInfo) }
            .distinctUntilChanged()
            .collect { keys ->
                exposureSession
                    .newlyVisible(keys)
                    .takeIf { it.isNotEmpty() }
                    ?.let(currentImpressionCallback)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
            ) { data -> Snackbar(snackbarData = data, shape = MaterialTheme.shapes.small) }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = copy.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = copy.back,
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
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    start = GallrSpacing.screenMargin,
                    end = GallrSpacing.screenMargin,
                    top = GallrSpacing.sm,
                    bottom = GallrSpacing.xl,
                ),
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            if (ready != null) {
                item(key = "recommendations-basis-header") {
                    RecommendationHeader(
                        basisLabel = recommendationBasisLabel(ready.basis, lang),
                        tasteTitle = copy.tasteTitle,
                        tasteTags = ready.tasteTerms.map { it.localizedName(lang) },
                        editLabel = copy.editTaste,
                        editActionLabel = copy.editTasteAction,
                        onEditTaste =
                            onEditTaste.takeIf {
                                ready.tasteTerms.isNotEmpty() || ready.basis.pickedCount > 0
                            },
                    )
                }
            }

            when (state) {
                RecommendationUiState.Loading -> {
                    item(key = "recommendations-loading") {
                        // Skeleton rows instead of a line of text (spec 101 US7); one polite line for a screen reader.
                        Column(
                            modifier =
                                Modifier.clearAndSetSemantics {
                                    contentDescription = copy.loading
                                    liveRegion = LiveRegionMode.Polite
                                },
                        ) {
                            repeat(LOADING_SKELETON_ROWS) {
                                SkeletonCard(modifier = Modifier.padding(bottom = GallrSpacing.md))
                            }
                        }
                    }
                }

                RecommendationUiState.Empty -> {
                    item(key = "recommendations-empty") {
                        GallrEmptyState(
                            message = "${copy.emptyTitle}\n${copy.emptyBody}",
                            actionLabel = copy.browseFeatured,
                            onAction = onBack,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                RecommendationUiState.Error -> {
                    item(key = "recommendations-error") {
                        RecommendationMessage(
                            title = copy.errorTitle,
                            body = copy.errorBody,
                            actionLabel = copy.retry,
                            onAction = onRetry,
                            isError = true,
                        )
                    }
                }

                is RecommendationUiState.Ready -> {
                    if (presentations.isEmpty()) {
                        item(key = "recommendations-ready-empty") {
                            GallrEmptyState(
                                message = "${copy.emptyTitle}\n${copy.emptyBody}",
                                actionLabel = copy.browseFeatured,
                                onAction = onBack,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        sections.forEach { section ->
                            item(key = "section-${section.section.name}") {
                                SectionHeader(
                                    title = section.title,
                                    subtitle = null,
                                    modifier = Modifier.padding(top = GallrSpacing.sm, bottom = GallrSpacing.md),
                                    horizontalPadding = 0.dp,
                                )
                            }
                            section.cards.forEach { card ->
                                item(key = card.exhibition.id) {
                                    val isBookmarked = card.exhibition.id in bookmarkedIds
                                    val menu: (@Composable (Color) -> Unit)? =
                                        if (section.section == RecommendationSection.PERSONAL) {
                                            { tint ->
                                                RecommendationOverflowMenu(
                                                    isBookmarked = isBookmarked,
                                                    language = lang,
                                                    onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                                                    onLessLikeThis = { hideCard(card.exhibition) },
                                                    onShare = { onShare(card.exhibition) },
                                                    tint = tint,
                                                )
                                            }
                                        } else {
                                            null
                                        }
                                    val hidden =
                                        Modifier.hiddenRecommendation(
                                            hidden = card.exhibition.id in hiddenIds,
                                            language = lang,
                                            onUndo = { onUndoLessLikeThis(card.exhibition.id) },
                                        )
                                    if (card.isHero) {
                                        HeroExhibitionCard(
                                            exhibition = card.exhibition,
                                            isBookmarked = isBookmarked,
                                            onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                                            onTap = { onExhibitionTap(card.exhibition, card.rank) },
                                            lang = lang,
                                            eyebrow = card.reason,
                                            trailingAction = menu,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .then(hidden)
                                                    .padding(bottom = GallrSpacing.lg),
                                        )
                                    } else {
                                        ExhibitionCard(
                                            exhibition = card.exhibition,
                                            isBookmarked = isBookmarked,
                                            onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                                            onTap = { onExhibitionTap(card.exhibition, card.rank) },
                                            lang = lang,
                                            eyebrow = card.reason,
                                            // The eyebrow already carries the editorial reason; badges would repeat it.
                                            curationBadges = emptyList(),
                                            trailingAction = menu,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .then(hidden)
                                                    .padding(bottom = GallrSpacing.lg),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationHeader(
    basisLabel: String,
    tasteTitle: String,
    tasteTags: List<String>,
    editLabel: String,
    editActionLabel: String,
    onEditTaste: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = GallrSpacing.sm, bottom = GallrSpacing.md),
    ) {
        Text(
            text = basisLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tasteTags.isNotEmpty() || onEditTaste != null) {
            Spacer(Modifier.height(GallrSpacing.md))
            TasteTagRow(
                title = tasteTitle,
                tags = tasteTags,
                editLabel = editLabel,
                editActionLabel = editActionLabel,
                onEdit = onEditTaste,
            )
        }
        Spacer(Modifier.height(GallrSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The visitor's taste as outline chips (DESIGN.md, Taste tags): quiet labels, no fill, no accent. With [onEdit]
 * every tag and a trailing 편집 chip open the taste sheet, as on Home (spec 101 FR-005).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TasteTagRow(
    title: String,
    tags: List<String>,
    editLabel: String,
    editActionLabel: String,
    onEdit: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val rowSemantics =
        if (onEdit == null) {
            Modifier.semantics(mergeDescendants = true) { contentDescription = "$title: ${tags.joinToString(", ")}" }
        } else {
            Modifier
        }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        modifier = Modifier.fillMaxWidth().then(rowSemantics),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        tags.forEach { tag ->
            TasteTag(text = tag, color = scheme.onBackground, actionLabel = editActionLabel, onClick = onEdit)
        }
        if (onEdit != null) {
            TasteTag(text = editLabel, color = scheme.onSurfaceVariant, actionLabel = editActionLabel, onClick = onEdit)
        }
    }
}

@Composable
private fun TasteTag(
    text: String,
    color: Color,
    actionLabel: String,
    onClick: (() -> Unit)?,
) {
    // A tappable tag keeps a full-size touch target around a chip drawn at its own size.
    val tappable =
        if (onClick == null) {
            Modifier
        } else {
            Modifier
                .minimumInteractiveComponentSize()
                .clip(GallrShapeTokens.small)
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier =
            tappable
                .clip(GallrShapeTokens.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GallrShapeTokens.small)
                .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
    )
}

@Composable
private fun RecommendationMessage(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    isError: Boolean = false,
) {
    val stateSemantics =
        if (isError) {
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                error(title)
            }
        } else {
            Modifier
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = GallrSpacing.xxl)
                .then(stateSemantics),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        actionLabel?.let {
            Spacer(Modifier.height(GallrSpacing.lg))
            OutlinedButton(
                onClick = onAction,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val LOADING_SKELETON_ROWS = 3
