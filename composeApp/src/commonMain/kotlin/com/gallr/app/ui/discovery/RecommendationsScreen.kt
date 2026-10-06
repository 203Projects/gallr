package com.gallr.app.ui.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gallr.app.analytics.ExhibitionExposureSession
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.analytics.RecommendationDisplayAnalyticsGate
import com.gallr.app.analytics.halfVisibleStableKeys
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.HeroExhibitionCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    val copy = recommendationScreenCopy(lang)
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
                    RecommendationHeader(recommendationBasisLabel(ready.basis, lang))
                }
            }

            when (state) {
                RecommendationUiState.Loading -> {
                    item(key = "recommendations-loading") {
                        RecommendationMessage(
                            title = copy.loading,
                            liveRegion = true,
                        )
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
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier =
                                        Modifier
                                            .padding(top = GallrSpacing.sm, bottom = GallrSpacing.md)
                                            .semantics { heading() },
                                )
                            }
                            section.cards.forEach { card ->
                                item(key = card.exhibition.id) {
                                    if (card.isHero) {
                                        HeroExhibitionCard(
                                            exhibition = card.exhibition,
                                            isBookmarked = card.exhibition.id in bookmarkedIds,
                                            onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                                            onTap = { onExhibitionTap(card.exhibition, card.rank) },
                                            lang = lang,
                                            eyebrow = card.reason,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.lg),
                                        )
                                    } else {
                                        ExhibitionCard(
                                            exhibition = card.exhibition,
                                            isBookmarked = card.exhibition.id in bookmarkedIds,
                                            onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                                            onTap = { onExhibitionTap(card.exhibition, card.rank) },
                                            lang = lang,
                                            eyebrow = card.reason,
                                            // The eyebrow already carries the editorial reason; badges would repeat it.
                                            curationBadges = emptyList(),
                                            modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.lg),
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
private fun RecommendationHeader(basisLabel: String) {
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
        Spacer(Modifier.height(GallrSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RecommendationMessage(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    isError: Boolean = false,
    liveRegion: Boolean = false,
) {
    val stateSemantics =
        if (isError || liveRegion) {
            Modifier.semantics {
                this.liveRegion = LiveRegionMode.Polite
                if (isError) error(title)
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
                shape = RectangleShape,
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
