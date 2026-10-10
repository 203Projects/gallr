package com.gallr.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.theme.GallrMotion
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl

/**
 * The For You top pick (DESIGN.md, Hero card): the cover in full colour above a text block whose title
 * steps up to `titleLarge`. Everything else matches [ExhibitionCard], so the hero reads as the same
 * card given more room rather than a different component.
 */
@Composable
fun HeroExhibitionCard(
    exhibition: Exhibition,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onTap: () -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    // Press state — detectTapGestures, NOT collectIsPressedAsState (CMP bug #3417), as in ExhibitionCard.
    var isPressed by remember { mutableStateOf(false) }
    var imageFailed by remember(exhibition.coverImageUrl) { mutableStateOf(false) }
    val coverUrl = exhibition.coverImageUrl?.takeUnless { imageFailed }

    // Press feedback inverts the text block like a no-image card and washes the cover with the background.
    val scheme = MaterialTheme.colorScheme
    val blockColor =
        pressColor(isPressed, resting = scheme.background, pressed = scheme.onBackground, label = "heroBlock")
    val contentColor =
        pressColor(isPressed, resting = scheme.onBackground, pressed = scheme.background, label = "heroContent")
    val secondaryColor =
        pressColor(isPressed, resting = scheme.onSurfaceVariant, pressed = scheme.background, label = "heroSecondary")
    val dividerColor =
        pressColor(isPressed, resting = scheme.outlineVariant, pressed = scheme.background, label = "heroDivider")
    val coverWash =
        pressColor(
            isPressed,
            resting = scheme.background.copy(alpha = 0f),
            pressed = scheme.background.copy(alpha = COVER_PRESS_WASH_ALPHA),
            label = "heroCoverWash",
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .border(1.dp, scheme.outline, RectangleShape)
                .background(blockColor)
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
                    contentDescription = exhibitionCardAccessibilityLabel(exhibition, lang, eyebrow = eyebrow)
                    onClick {
                        onTap()
                        true
                    }
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(COVER_ASPECT_RATIO)
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
            Box(Modifier.matchParentSize().background(coverWash))
        }

        Column(modifier = Modifier.padding(GallrSpacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
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
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleLarge,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))
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
                    tintColor = contentColor,
                )
            }
            Spacer(Modifier.height(GallrSpacing.sm))
            HorizontalDivider(thickness = 1.dp, color = dividerColor)
            Spacer(Modifier.height(GallrSpacing.sm))
            ExhibitionDateRow(exhibition = exhibition, lang = lang, contentColor = contentColor)
        }
    }
}

@Composable
private fun pressColor(
    isPressed: Boolean,
    resting: Color,
    pressed: Color,
    label: String,
): Color {
    val color by animateColorAsState(
        targetValue = if (isPressed) pressed else resting,
        animationSpec = tween(GallrMotion.PRESS_DURATION_MS),
        label = label,
    )
    return color
}

private const val COVER_ASPECT_RATIO = 4f / 3f
private const val COVER_PRESS_WASH_ALPHA = 0.5f
