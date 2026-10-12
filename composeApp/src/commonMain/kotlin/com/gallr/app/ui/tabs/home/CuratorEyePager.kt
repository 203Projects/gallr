package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.graphics.GrainWashSpec
import com.gallr.app.ui.graphics.grainWash
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.curatornote.CuratorEyeCard
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 큐레이터의 시선 (DESIGN.md, Home tab; spec 090 US2): approved notes as large cards, newest approval first, the
 * next card peeking and a `01 / 06` counter. A card carries the cover, the hook, one insight line and the
 * reviewing editor's name; a tap opens the exhibition with the full note in view.
 */
@Composable
internal fun CuratorEyePager(
    cards: List<CuratorEyeCard>,
    title: String,
    lang: AppLanguage,
    onTap: (CuratorEyeCard, Int) -> Unit,
    onPageShown: (CuratorEyeCard, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val currentPageShown by rememberUpdatedState(onPageShown)
    LaunchedEffect(pagerState, cards) {
        val shown = mutableSetOf<Int>()
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val card = cards.getOrNull(page) ?: return@collect
                if (shown.add(page)) currentPageShown(card, page)
            }
    }
    val peek = if (cards.size > 1) CURATOR_EYE_PEEK else 0.dp
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = null)
        Spacer(Modifier.height(GallrSpacing.md))
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(start = GallrSpacing.screenMargin, end = GallrSpacing.screenMargin + peek),
            pageSpacing = GallrSpacing.sm,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val card = cards[page]
            CuratorEyeCardView(
                card = card,
                counter = pagerCounter(page, cards.size),
                description = curatorEyeCardDescription(card, page, cards.size, lang),
                lang = lang,
                onTap = { onTap(card, page) },
            )
        }
    }
}

@Composable
private fun CuratorEyeCardView(
    card: CuratorEyeCard,
    counter: String,
    description: String,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var imageFailed by remember(card.exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = card.exhibition.coverImageUrl?.takeUnless { imageFailed }
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(CURATOR_EYE_ASPECT_RATIO)
                .clip(RectangleShape)
                .border(1.dp, scheme.outline, RectangleShape)
                .background(scheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }.semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick {
                        onTap()
                        true
                    }
                },
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = nativeSupabaseImageUrl(coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier =
                    Modifier
                        .matchParentSize()
                        .grainWash(
                            GrainWashSpec(
                                color = scheme.background,
                                start = CURATOR_EYE_WASH_START,
                                end = CURATOR_EYE_WASH_END,
                                strength = CURATOR_EYE_WASH_STRENGTH,
                                grain = CURATOR_EYE_WASH_GRAIN,
                            ),
                        ),
            )
        }

        Text(
            text = counter,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onBackground,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(GallrSpacing.sm)
                    .background(scheme.background)
                    .border(1.dp, scheme.outline)
                    .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs)
                    .clearAndSetSemantics { },
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(GallrSpacing.md),
        ) {
            Text(
                text = card.note.hook(lang),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text = card.note.firstInsight(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text = card.exhibition.localizedName(lang).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = curatorEyeReviewerLine(card.note.reviewerName(lang), lang),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private const val CURATOR_EYE_ASPECT_RATIO = 4f / 5f
private val CURATOR_EYE_PEEK = 32.dp

/** The wash covers the lower two thirds so a three-line hook and the insight stay readable over any cover. */
private const val CURATOR_EYE_WASH_START = 0.2f
private const val CURATOR_EYE_WASH_END = 0.55f
private const val CURATOR_EYE_WASH_STRENGTH = 0.94f
private const val CURATOR_EYE_WASH_GRAIN = 0.18f
