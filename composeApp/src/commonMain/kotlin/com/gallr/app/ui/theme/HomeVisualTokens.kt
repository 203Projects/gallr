package com.gallr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Home tab visual system v2 (Option A, owner decisions 2026-10-11 and 2026-10-12; spec 091). These values apply to
 * the Home tab only: every other tab keeps 0dp shapes (DESIGN.md "Home visual system v2"). A value changes here and
 * in DESIGN.md together.
 */
object HomeVisualTokens {
    /** Corner radius of every Home card and tile. */
    val cornerRadius: Dp = 16.dp

    /** Corner radius of the small pieces on a card: counter pill, chips, stop covers. */
    val innerRadius: Dp = 12.dp

    /**
     * Alpha of the black scrim under a caption, reached at the caption's edge and held under it. A floor, not a
     * scale: it keeps white text at 4.5:1 over a pure white cover ([HomeVisualTokensTest]).
     */
    const val SCRIM_ALPHA = 0.55f

    /** The scrim while the card is pressed. */
    const val SCRIM_PRESSED_ALPHA = 0.70f

    /** How far beyond the caption's edge the scrim fades out, as a fraction of the cover height. */
    const val SCRIM_LEAD = 0.30f

    /** Where a bottom scrim starts before the caption is measured, as a fraction of the cover height. */
    const val SCRIM_START = 0.44f

    /** Position dots: diameter, gap, and the width of the current story's pill. */
    val dotSize: Dp = 6.dp
    val dotSpacing: Dp = 6.dp
    val activeDotWidth: Dp = 18.dp

    /** A rail or carousel with more cards than this shows no dots. */
    const val DOT_LIMIT = 10

    /** The shape every Home card and tile uses, and the shape of the small pieces on them. */
    val shape: Shape get() = RoundedCornerShape(cornerRadius)
    val innerShape: Shape get() = RoundedCornerShape(innerRadius)
}

/**
 * Where a caption's scrim runs, as fractions of the cover height from the top: transparent up to [fadeEdge], full
 * strength from [captionEdge] to the cover's edge behind the caption.
 */
data class ScrimBand(
    val fadeEdge: Float,
    val captionEdge: Float,
)

/**
 * The scrim for a caption at the bottom of a cover. It is at full strength from the caption's top edge down and
 * fades in over [HomeVisualTokens.SCRIM_LEAD] above it, starting no lower than [HomeVisualTokens.SCRIM_START], so a
 * caption that grows with the font scale still sits on the scrim.
 */
fun bottomScrimBand(
    coverHeightPx: Int,
    captionHeightPx: Int,
): ScrimBand {
    if (coverHeightPx <= 0 || captionHeightPx <= 0) {
        return ScrimBand(HomeVisualTokens.SCRIM_START, HomeVisualTokens.SCRIM_START + HomeVisualTokens.SCRIM_LEAD)
    }
    val captionTop = (1f - captionHeightPx.toFloat() / coverHeightPx).coerceIn(0f, 1f)
    val fadeEdge = minOf(HomeVisualTokens.SCRIM_START, captionTop - HomeVisualTokens.SCRIM_LEAD).coerceAtLeast(0f)
    return ScrimBand(fadeEdge = fadeEdge, captionEdge = captionTop)
}

/** The mirror for a caption at the top of a cover: full strength down to the caption's bottom edge, then fading. */
fun topScrimBand(
    coverHeightPx: Int,
    captionHeightPx: Int,
): ScrimBand {
    val default = 1f - HomeVisualTokens.SCRIM_START
    if (coverHeightPx <= 0 || captionHeightPx <= 0) {
        return ScrimBand(fadeEdge = default, captionEdge = default - HomeVisualTokens.SCRIM_LEAD)
    }
    val captionBottom = (captionHeightPx.toFloat() / coverHeightPx).coerceIn(0f, 1f)
    val fadeEdge = maxOf(default, captionBottom + HomeVisualTokens.SCRIM_LEAD).coerceAtMost(1f)
    return ScrimBand(fadeEdge = fadeEdge, captionEdge = captionBottom)
}
