package com.gallr.app.ui.tabs.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.components.exhibitionCardAccessibilityLabel
import com.gallr.app.ui.discovery.HIDDEN_CARD_ALPHA
import com.gallr.app.ui.theme.GallrMotion
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.app.ui.theme.bottomScrim
import com.gallr.app.ui.theme.bottomScrimBand
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The leading banner (DESIGN.md "Home visual system v2"): one exhibition at a time on a 3:4 cover about three
 * quarters of the screen wide, the next card peeking, a `01 / 06` pill and the save button on the cover, and the
 * reason, title and venue in white on the scrim. It never auto-advances: the visitor reads it.
 */
@Composable
internal fun BannerPager(
    cards: List<RailCard>,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onTap: (Exhibition, Int) -> Unit,
    onBookmarkToggle: (Exhibition) -> Unit,
    onPageShown: (Exhibition, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Cards the visitor hid with 덜 보기, dimmed until the list is read again (spec 101 US3). */
    hiddenIds: Set<String> = emptySet(),
    /** The ⋯ menu beside the save button; only the 내 취향 banner has one. */
    cardMenu: (@Composable (Exhibition) -> Unit)? = null,
) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val currentPageShown by rememberUpdatedState(onPageShown)
    LaunchedEffect(pagerState, cards) {
        val shown = mutableSetOf<Int>()
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val card = cards.getOrNull(page) ?: return@collect
                if (shown.add(page)) currentPageShown(card.exhibition, page)
            }
    }
    val peek = if (cards.size > 1) BANNER_PEEK else 0.dp
    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(start = GallrSpacing.screenMargin, end = GallrSpacing.screenMargin + peek),
        pageSpacing = GallrSpacing.sm,
        modifier = modifier.fillMaxWidth(),
    ) { page ->
        val card = cards[page]
        BannerCard(
            card = card,
            counter = pagerCounter(page, cards.size),
            lang = lang,
            isBookmarked = card.exhibition.id in bookmarkedIds,
            onTap = { onTap(card.exhibition, page) },
            onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
            menu = cardMenu?.let { menu -> { menu(card.exhibition) } },
            modifier = if (card.exhibition.id in hiddenIds) Modifier.alpha(HIDDEN_CARD_ALPHA) else Modifier,
        )
    }
}

@Composable
private fun BannerCard(
    card: RailCard,
    counter: String,
    lang: AppLanguage,
    isBookmarked: Boolean,
    onTap: () -> Unit,
    onBookmarkToggle: () -> Unit,
    menu: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val exhibition = card.exhibition
    val scheme = MaterialTheme.colorScheme
    // The gesture below is installed once per page slot, so it reads the tap through state: after the picks are
    // ranked again the slot holds another exhibition and the tap must open that one.
    val currentOnTap by rememberUpdatedState(onTap)
    // Press state through detectTapGestures, as the cards do (CMP bug #3417 with collectIsPressedAsState).
    var isPressed by remember { mutableStateOf(false) }
    var imageFailed by remember(exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = exhibition.coverImageUrl?.takeUnless { imageFailed }
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isPressed) HomeVisualTokens.SCRIM_PRESSED_ALPHA else HomeVisualTokens.SCRIM_ALPHA,
        animationSpec = if (reduceMotion) snap() else tween(GallrMotion.PRESS_DURATION_MS),
        label = "bannerScrim",
    )
    var cardHeightPx by remember { mutableStateOf(0) }
    var captionHeightPx by remember { mutableStateOf(0) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(BANNER_ASPECT_RATIO)
                .onSizeChanged { cardHeightPx = it.height }
                .clip(HomeVisualTokens.shape)
                .border(1.dp, scheme.outlineVariant, HomeVisualTokens.shape)
                .background(scheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            val released = tryAwaitRelease()
                            isPressed = false
                            if (released) currentOnTap()
                        },
                    )
                }.semantics {
                    role = Role.Button
                    contentDescription =
                        "$counter, ${exhibitionCardAccessibilityLabel(exhibition, lang, eyebrow = card.eyebrow)}"
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
        Box(Modifier.matchParentSize().bottomScrim(bottomScrimBand(cardHeightPx, captionHeightPx), scrimAlpha))

        Text(
            text = counter,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(GallrSpacing.sm)
                    .background(Color.Black.copy(alpha = HomeVisualTokens.SCRIM_ALPHA), HomeVisualTokens.innerShape)
                    .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs)
                    .clearAndSetSemantics { },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
            modifier = Modifier.align(Alignment.TopEnd).padding(GallrSpacing.sm),
        ) {
            menu?.let { content ->
                CoverButton(content = content)
            }
            CoverButton {
                BookmarkButton(
                    isBookmarked = isBookmarked,
                    onToggle = onBookmarkToggle,
                    language = lang,
                    tintColor = Color.Black,
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .onSizeChanged { captionHeightPx = it.height }
                    .padding(GallrSpacing.md),
        ) {
            card.eyebrow?.let { eyebrow ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(HomeVisualTokens.dotSize).background(Color.White, CircleShape))
                    Spacer(Modifier.size(GallrSpacing.sm))
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(GallrSpacing.xs))
            }
            Text(
                text = exhibition.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = bannerMetaLine(exhibition, lang),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A white circle on the cover holding one control, as the save button has always been drawn. */
@Composable
private fun CoverButton(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(SAVE_BUTTON_SIZE)
                .background(Color.White, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        content()
    }
}

/** The taste terms the picks rest on, as a row of chips under the banner; scrolls sideways when they overflow. */
@Composable
internal fun TasteChips(
    label: String,
    terms: List<String>,
    modifier: Modifier = Modifier,
    /** Label of the trailing edit chip; with [onEdit], every chip opens the taste sheet (spec 101 FR-005). */
    editLabel: String? = null,
    onEdit: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        val chips = if (onEdit != null && editLabel != null) terms + editLabel else terms
        // A tappable chip keeps a full-size touch target around a chip drawn at its own size, so the label stays
        // centred instead of sitting at the top of a stretched outline.
        val tappable =
            if (onEdit == null) {
                Modifier
            } else {
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(HomeVisualTokens.innerShape)
                    .clickable(role = Role.Button, onClick = onEdit)
            }
        chips.forEach { term ->
            Text(
                text = term,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (term === editLabel) scheme.onSurfaceVariant else scheme.onBackground,
                modifier =
                    tappable
                        .border(1.dp, scheme.outlineVariant, HomeVisualTokens.innerShape)
                        .padding(horizontal = GallrSpacing.sm + GallrSpacing.xs, vertical = GallrSpacing.sm),
            )
        }
    }
}

private const val BANNER_ASPECT_RATIO = 3f / 4f

/** With the screen margins this leaves the card about three quarters of a phone's width. */
private val BANNER_PEEK = 64.dp
private val SAVE_BUTTON_SIZE = 44.dp
