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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.app.ui.theme.topScrimBand
import com.gallr.shared.curatornote.CuratorEyeCard
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 큐레이터의 시선 (DESIGN.md, Home tab; spec 090 US2): approved notes as story cards, newest approval first, a card
 * centred with its neighbours peeking and dots under the carousel. A card carries the reviewing editor's name, the
 * hook and one insight at the top of the cover, and the exhibition's name as a chip at the bottom; a tap opens the
 * exhibition with the full note in view.
 */
@Composable
internal fun CuratorEyePager(
    cards: List<CuratorEyeCard>,
    title: String,
    subtitle: String,
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
    val side = if (cards.size > 1) STORY_SIDE_PEEK else GallrSpacing.screenMargin
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = subtitle)
        Spacer(Modifier.height(GallrSpacing.md))
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = GallrSpacing.sm,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val card = cards[page]
            StoryCard(
                card = card,
                description = curatorEyeCardDescription(card, page, cards.size, lang),
                lang = lang,
                onTap = { onTap(card, page) },
            )
        }
        if (showsRailDots(cards.size)) {
            Spacer(Modifier.height(GallrSpacing.md))
            StoryDots(
                count = cards.size,
                current = pagerState.settledPage,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun StoryCard(
    card: CuratorEyeCard,
    description: String,
    lang: AppLanguage,
    onTap: () -> Unit,
) {
    var imageFailed by remember(card.exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = card.exhibition.coverImageUrl?.takeUnless { imageFailed }
    val scheme = MaterialTheme.colorScheme
    var cardHeightPx by remember { mutableStateOf(0) }
    var captionHeightPx by remember { mutableStateOf(0) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(STORY_ASPECT_RATIO)
                .onSizeChanged { cardHeightPx = it.height }
                .clip(HomeVisualTokens.shape)
                .border(1.dp, scheme.outlineVariant, HomeVisualTokens.shape)
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
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .topScrim(topScrimBand(cardHeightPx, captionHeightPx), HomeVisualTokens.SCRIM_ALPHA),
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .onSizeChanged { captionHeightPx = it.height }
                    .padding(GallrSpacing.md),
        ) {
            Text(
                text = card.note.hook(lang),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text = card.note.firstInsight(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = card.exhibition.localizedName(lang),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(GallrSpacing.md)
                    .background(Color.White, HomeVisualTokens.innerShape)
                    .padding(horizontal = GallrSpacing.sm + GallrSpacing.xs, vertical = GallrSpacing.sm),
        )
    }
}

private const val STORY_ASPECT_RATIO = 3f / 4f

/** The card sits centred with a slice of each neighbour showing. */
private val STORY_SIDE_PEEK = 28.dp
