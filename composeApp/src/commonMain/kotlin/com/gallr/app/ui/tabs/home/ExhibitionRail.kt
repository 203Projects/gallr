package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.analytics.ExhibitionExposureSession
import com.gallr.app.analytics.RankedExhibitionExposure
import com.gallr.app.analytics.halfVisibleStableKeys
import com.gallr.app.ui.components.BookmarkButton
import com.gallr.app.ui.components.exhibitionCardAccessibilityLabel
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.exhibitionStatus
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** One card on a rail: the exhibition and, when the section has a reason for it, that reason as the eyebrow. */
internal data class RailCard(
    val exhibition: Exhibition,
    val eyebrow: String? = null,
)

/**
 * A horizontal rail (DESIGN.md, Home tab): a section header, then portrait cards the visitor scrolls sideways,
 * the next card always peeking so the rail reads as a row rather than a page.
 */
@Composable
internal fun ExhibitionRail(
    title: String,
    subtitle: String?,
    cards: List<RailCard>,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onTap: (Exhibition, Int) -> Unit,
    onBookmarkToggle: (Exhibition) -> Unit,
    onImpressions: (List<RankedExhibitionExposure>) -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val exposureSession = remember { ExhibitionExposureSession() }
    val currentImpressions by rememberUpdatedState(onImpressions)
    LaunchedEffect(listState, cards, exposureSession) {
        exposureSession.updateCatalogue(cards.map { it.exhibition.id })
        snapshotFlow { halfVisibleStableKeys(listState.layoutInfo) }
            .distinctUntilChanged()
            .collect { keys -> exposureSession.newlyVisible(keys).takeIf { it.isNotEmpty() }?.let(currentImpressions) }
    }

    // A rail reserves the eyebrow line only when it has reasons to show, so the editors' cards start with the title.
    val showEyebrow = cards.any { it.eyebrow != null }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = subtitle, action = action, onAction = onAction)
        Spacer(Modifier.height(GallrSpacing.md))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = GallrSpacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        ) {
            items(cards.withIndex().toList(), key = { it.value.exhibition.id }) { (index, card) ->
                RailCardView(
                    card = card,
                    showEyebrow = showEyebrow,
                    lang = lang,
                    isBookmarked = card.exhibition.id in bookmarkedIds,
                    onTap = { onTap(card.exhibition, index) },
                    onBookmarkToggle = { onBookmarkToggle(card.exhibition) },
                )
            }
        }
    }
}

@Composable
private fun RailCardView(
    card: RailCard,
    showEyebrow: Boolean,
    lang: AppLanguage,
    isBookmarked: Boolean,
    onTap: () -> Unit,
    onBookmarkToggle: () -> Unit,
) {
    val exhibition = card.exhibition
    val scheme = MaterialTheme.colorScheme
    var imageFailed by remember(exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = exhibition.coverImageUrl?.takeUnless { imageFailed }
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val statusLabel = exhibitionStatus(exhibition.openingDate, exhibition.closingDate, today).label(lang)
    // Larger type gets a wider card, so a title still shows two useful lines and the dates stay whole.
    val cardWidth = RAIL_CARD_WIDTH * LocalDensity.current.fontScale.coerceIn(1f, RAIL_CARD_MAX_SCALE)

    Column(
        modifier =
            Modifier
                .width(cardWidth)
                .border(1.dp, scheme.outline, RectangleShape)
                .background(scheme.background)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics {
                    contentDescription =
                        exhibitionCardAccessibilityLabel(exhibition, lang, eyebrow = card.eyebrow)
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(RAIL_COVER_ASPECT_RATIO)
                    .background(scheme.surfaceVariant),
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
        }
        Column(modifier = Modifier.padding(GallrSpacing.sm)) {
            if (showEyebrow) {
                Text(
                    text = card.eyebrow.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = exhibition.localizedName(lang),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onBackground,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BookmarkButton(
                    isBookmarked = isBookmarked,
                    onToggle = onBookmarkToggle,
                    language = lang,
                    tintColor = scheme.onBackground,
                )
            }
            Text(
                text = exhibition.localizedVenueName(lang).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            // The status label replaces the dates when the run is about to start or end: that is the fact that matters.
            Text(
                text = statusLabel ?: exhibition.localizedDateRange(lang),
                style = MaterialTheme.typography.labelSmall,
                color = if (statusLabel != null) GallrAccent.activeIndicator else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val RAIL_CARD_WIDTH = 184.dp

/** At 1.6× the card still leaves the next one peeking on a 360dp-wide phone. */
private const val RAIL_CARD_MAX_SCALE = 1.6f
private const val RAIL_COVER_ASPECT_RATIO = 3f / 4f
