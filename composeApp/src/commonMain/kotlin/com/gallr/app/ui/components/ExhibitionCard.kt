package com.gallr.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrMotion
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.exhibitionStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Visual treatment applied to an ExhibitionCard when it belongs to the current
 * active city-wide event. Null for regular exhibitions.
 */
data class EventTreatment(
    val brandColor: Color,
    /** Pre-localized, pre-truncated (≤ 20 chars + ellipsis) event name for the corner label. */
    val label: String,
)

@Composable
fun ExhibitionCard(
    exhibition: Exhibition,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onTap: () -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    eventTreatment: EventTreatment? = null,
) {
    // ── Press state — detectTapGestures, NOT collectIsPressedAsState (CMP bug #3417) ──
    var isPressed by remember { mutableStateOf(false) }

    // ── Image load state ──
    var imageLoaded by remember { mutableStateOf(false) }
    val hasImage = exhibition.coverImageUrl != null && imageLoaded
    val isDark = isSystemInDarkTheme()

    // ── Scrim alpha animation (image cards: normal → pressed) ──
    val scrimAlpha by animateFloatAsState(
        targetValue = when {
            !hasImage -> 0f
            isPressed && isDark -> 0.68f
            isPressed && !isDark -> 0.72f
            isDark -> 0.45f
            else -> 0.50f
        },
        animationSpec = tween(GallrMotion.pressDurationMs),
        label = "scrimAlpha",
    )
    val scrimColor = if (isDark) Color.Black.copy(alpha = scrimAlpha)
    else Color.White.copy(alpha = scrimAlpha)

    // ── Colors for non-image cards (existing invert animation) ──
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.onBackground
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(GallrMotion.pressDurationMs),
        label = "cardBackground",
    )
    val noImageContentColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onBackground,
        animationSpec = tween(GallrMotion.pressDurationMs),
        label = "noImageContent",
    )
    val noImageSecondaryColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(GallrMotion.pressDurationMs),
        label = "noImageSecondary",
    )
    val noImageDividerColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(GallrMotion.pressDurationMs),
        label = "noImageDivider",
    )

    // ── Resolve colors based on image presence ──
    val contentColor = if (hasImage) {
        if (isDark) Color.White else Color.Black
    } else noImageContentColor

    val secondaryColor = if (hasImage) {
        if (isDark) Color.White.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.65f)
    } else noImageSecondaryColor

    val dividerColor = if (hasImage) {
        if (isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.20f)
    } else noImageDividerColor

    val bookmarkTintColor = if (hasImage) {
        if (isDark) Color.White.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.30f)
    } else contentColor

    // ── Card container ──
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .then(
                if (!hasImage) Modifier.background(backgroundColor)
                else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        val released = tryAwaitRelease()
                        isPressed = false
                        if (released) onTap()
                    },
                )
            },
    ) {
        // ── Layer 1: Background image (image cards only) ──
        if (exhibition.coverImageUrl != null) {
            AsyncImage(
                model = exhibition.coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = { imageLoaded = false },
                modifier = Modifier.matchParentSize(),
            )
        }

        // ── Layer 2: Scrim overlay (image cards only) ──
        if (hasImage) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(scrimColor),
            )
        }

        // ── Layer 2b: Event treatment (Phase 2b) — left edge + top-right corner label ──
        if (eventTreatment != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(eventTreatment.brandColor),
            )
            Text(
                text = eventTreatment.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(eventTreatment.brandColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // ── Layer 3: Content ──
        Column(modifier = Modifier.padding(GallrSpacing.md)) {
            // ── Top row: text + heart (top-aligned) ──────────────────
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    // ── Exhibition name ──────────────────────────────
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))

                    // ── Venue & city ─────────────────────────────────
                    Text(
                        text = exhibition.localizedVenueName(lang).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = exhibition.localizedCity(lang).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                    )
                }

                BookmarkButton(
                    isBookmarked = isBookmarked,
                    onToggle = onBookmarkToggle,
                    tintColor = bookmarkTintColor,
                )
            }

            Spacer(Modifier.height(GallrSpacing.sm))

            // ── Full-width divider ───────────────────────────────────
            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Spacer(Modifier.height(GallrSpacing.sm))

            // ── Date range + status label (full width) ──────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exhibition.localizedDateRange(lang),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                )
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val statusLabel = exhibitionStatus(
                    exhibition.openingDate, exhibition.closingDate, today,
                ).label(lang)
                if (statusLabel != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = GallrAccent.activeIndicator,
                    )
                }
            }
        }
    }
}
