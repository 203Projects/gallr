package com.gallr.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.theme.GallrMotion
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionCurationBadge
import com.gallr.shared.data.model.curationBadges
import com.gallr.shared.data.network.nativeSupabaseImageUrl

/**
 * Visual treatment applied to an ExhibitionCard when it belongs to the current
 * active city-wide event. Null for regular exhibitions.
 */
data class EventTreatment(
    val brandColor: Color,
    /** Pre-resolved compact event identifier for the corner label — admin short_label, else localized name truncated to ≤ 12 chars + ellipsis (see Event.ribbonLabel). */
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
    contextLabel: String? = null,
    curationBadges: List<ExhibitionCurationBadge> = exhibition.curationBadges(),
    eyebrow: String? = null,
    /** Drawn beside the save button, e.g. the 내 취향 card menu (spec 101). */
    trailingAction: (@Composable () -> Unit)? = null,
) {
    // ── Press state — detectTapGestures, NOT collectIsPressedAsState (CMP bug #3417) ──
    var isPressed by remember { mutableStateOf(false) }

    // ── Image load state ──
    var imageLoaded by remember { mutableStateOf(false) }
    val hasImage = exhibition.coverImageUrl != null && imageLoaded

    // ── Scrim alpha animation (image cards: normal → pressed). The text spans the whole card, so the scrim is
    // Home's even black one in both themes, with white text over it. ──
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    val scrimAlpha by animateFloatAsState(
        targetValue =
            when {
                !hasImage -> 0f
                isPressed -> HomeVisualTokens.SCRIM_PRESSED_ALPHA
                else -> HomeVisualTokens.SCRIM_ALPHA
            },
        animationSpec = if (reduceMotion) snap() else tween(GallrMotion.PRESS_DURATION_MS),
        label = "scrimAlpha",
    )

    // ── Colors for non-image cards (existing invert animation) ──
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isPressed) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = "cardBackground",
    )
    val noImageContentColor by animateColorAsState(
        targetValue =
            if (isPressed) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = "noImageContent",
    )
    val noImageSecondaryColor by animateColorAsState(
        targetValue =
            if (isPressed) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = "noImageSecondary",
    )
    val noImageDividerColor by animateColorAsState(
        targetValue =
            if (isPressed) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = "noImageDivider",
    )

    // ── Resolve colors based on image presence ──
    val contentColor =
        if (hasImage) {
            Color.White
        } else {
            noImageContentColor
        }

    val secondaryColor =
        if (hasImage) {
            Color.White
        } else {
            noImageSecondaryColor
        }

    val dividerColor =
        if (hasImage) {
            Color.White.copy(alpha = 0.25f)
        } else {
            noImageDividerColor
        }

    val bookmarkTintColor =
        if (hasImage) {
            Color.White
        } else {
            contentColor
        }

    // ── Card container ──
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(GallrShapeTokens.card)
                .border(1.dp, MaterialTheme.colorScheme.outline, GallrShapeTokens.card)
                .then(
                    if (!hasImage) {
                        Modifier.background(backgroundColor)
                    } else {
                        Modifier
                    },
                ).pointerInput(Unit) {
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
                    contentDescription = exhibitionCardAccessibilityLabel(exhibition, lang, contextLabel, eyebrow)
                    onClick {
                        onTap()
                        true
                    }
                },
    ) {
        // ── Layer 1: Background image (image cards only) ──
        if (exhibition.coverImageUrl != null) {
            AsyncImage(
                model = nativeSupabaseImageUrl(exhibition.coverImageUrl),
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
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }

        // ── Layer 2b: Event treatment (Phase 2b) — left edge + top-right corner label ──
        if (eventTreatment != null) {
            Box(
                modifier =
                    Modifier
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
                modifier =
                    Modifier
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
                    // ── Eyebrow: the reason this card is here, read before the name ──
                    eyebrow?.takeIf(String::isNotBlank)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(GallrSpacing.xs))
                    }
                    // ── Exhibition name ──────────────────────────────
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ExhibitionCurationBadges(
                        badges = curationBadges,
                        language = lang,
                        color = contentColor,
                        modifier = Modifier.padding(top = GallrSpacing.sm),
                    )
                    contextLabel?.takeIf(String::isNotBlank)?.let { label ->
                        Spacer(Modifier.height(GallrSpacing.sm))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                        )
                    }
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
                    language = lang,
                    tintColor = bookmarkTintColor,
                )
                trailingAction?.invoke()
            }

            Spacer(Modifier.height(GallrSpacing.sm))

            // ── Full-width divider ───────────────────────────────────
            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Spacer(Modifier.height(GallrSpacing.sm))

            // ── Date range + status label (full width) ──────────────
            ExhibitionDateRow(exhibition = exhibition, lang = lang, contentColor = contentColor)
        }
    }
}

internal fun exhibitionCardAccessibilityLabel(
    exhibition: Exhibition,
    language: AppLanguage,
    contextLabel: String? = null,
    eyebrow: String? = null,
): String =
    listOf(
        eyebrow.orEmpty(),
        exhibition.localizedName(language),
        exhibition.localizedVenueName(language),
        exhibition.localizedDateRange(language),
        contextLabel.orEmpty(),
    ).filter(String::isNotBlank).joinToString(", ")
