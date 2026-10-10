package com.gallr.app.ui.tabs.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.components.ExhibitionDateRow
import com.gallr.app.ui.components.exhibitionCardAccessibilityLabel
import com.gallr.app.ui.graphics.GrainWashSpec
import com.gallr.app.ui.graphics.grainWash
import com.gallr.app.ui.theme.GallrMotion
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The hero (DESIGN.md, Home tab): one featured exhibition at a time on a 4:5 cover, the next card peeking at the
 * trailing edge, and a `01 / 06` counter so the visitor knows where they stand. It never auto-advances: the
 * visitor reads it.
 */
@Composable
internal fun HeroPager(
    exhibitions: List<Exhibition>,
    label: String,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onTap: (Exhibition, Int) -> Unit,
    onBookmarkToggle: (Exhibition) -> Unit,
    onPageShown: (Exhibition, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { exhibitions.size })
    val currentPageShown by rememberUpdatedState(onPageShown)
    LaunchedEffect(pagerState, exhibitions) {
        val shown = mutableSetOf<Int>()
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val exhibition = exhibitions.getOrNull(page) ?: return@collect
                if (shown.add(page)) currentPageShown(exhibition, page)
            }
    }
    val peek = if (exhibitions.size > 1) HERO_PEEK else 0.dp
    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(start = GallrSpacing.screenMargin, end = GallrSpacing.screenMargin + peek),
        pageSpacing = GallrSpacing.sm,
        modifier = modifier.fillMaxWidth(),
    ) { page ->
        val exhibition = exhibitions[page]
        HeroCard(
            exhibition = exhibition,
            label = label,
            counter = pagerCounter(page, exhibitions.size),
            lang = lang,
            isBookmarked = exhibition.id in bookmarkedIds,
            onTap = { onTap(exhibition, page) },
            onBookmarkToggle = { onBookmarkToggle(exhibition) },
        )
    }
}

@Composable
private fun HeroCard(
    exhibition: Exhibition,
    label: String,
    counter: String,
    lang: AppLanguage,
    isBookmarked: Boolean,
    onTap: () -> Unit,
    onBookmarkToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Press state through detectTapGestures, as the cards do (CMP bug #3417 with collectIsPressedAsState).
    var isPressed by remember { mutableStateOf(false) }
    var imageFailed by remember(exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = exhibition.coverImageUrl?.takeUnless { imageFailed }
    val scheme = MaterialTheme.colorScheme
    val washStrength by animateFloatAsState(
        targetValue = if (isPressed) HERO_WASH_PRESSED else HERO_WASH_STRENGTH,
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = "heroWash",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(HERO_ASPECT_RATIO)
                .clip(RectangleShape)
                .border(1.dp, scheme.outline, RectangleShape)
                .background(scheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            val released = tryAwaitRelease()
                            isPressed = false
                            if (released) onTap()
                        },
                    )
                }.semantics {
                    role = Role.Button
                    contentDescription =
                        "$counter, ${exhibitionCardAccessibilityLabel(exhibition, lang, eyebrow = label)}"
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
                                start = HERO_WASH_START,
                                strength = washStrength,
                                grain = HERO_WASH_GRAIN,
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
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))
                    Text(
                        text = exhibition.localizedVenueName(lang).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BookmarkButton(
                    isBookmarked = isBookmarked,
                    onToggle = onBookmarkToggle,
                    language = lang,
                    tintColor = scheme.onBackground,
                )
            }
            Spacer(Modifier.height(GallrSpacing.sm))
            ExhibitionDateRow(exhibition = exhibition, lang = lang, contentColor = scheme.onBackground)
        }
    }
}

private const val HERO_ASPECT_RATIO = 4f / 5f
private val HERO_PEEK = 32.dp

/** The wash starts a third of the way down and is almost opaque at the bottom edge, where the caption sits. */
private const val HERO_WASH_START = 0.32f
private const val HERO_WASH_STRENGTH = 0.94f
private const val HERO_WASH_PRESSED = 1f
private const val HERO_WASH_GRAIN = 0.18f
