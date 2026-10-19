package com.gallr.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A scrim under a Home caption (DESIGN.md "Home visual system v2"): black over the cover so white text reads on any
 * picture, drawn over the content it modifies so it darkens a placeholder as well as a loaded cover. This one is
 * transparent down to the band's fade edge and [alpha] from the caption's top edge to the bottom.
 */
internal fun Modifier.bottomScrim(
    band: ScrimBand,
    alpha: Float,
): Modifier =
    drawWithContent {
        drawContent()
        val full = Color.Black.copy(alpha = alpha)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                band.fadeEdge to Color.Transparent,
                band.captionEdge.coerceAtLeast(band.fadeEdge) to full,
                1f to full,
            ),
        )
    }

/** [alpha] from the top to the caption's bottom edge, transparent from the band's fade edge down. */
internal fun Modifier.topScrim(
    band: ScrimBand,
    alpha: Float,
): Modifier =
    drawWithContent {
        drawContent()
        val full = Color.Black.copy(alpha = alpha)
        drawRect(
            Brush.verticalGradient(
                0f to full,
                band.captionEdge.coerceAtMost(band.fadeEdge) to full,
                band.fadeEdge to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
    }

/** One strength over the whole tile, for tiles with text at both edges. */
internal fun Modifier.flatScrim(alpha: Float): Modifier =
    drawWithContent {
        drawContent()
        drawRect(Color.Black.copy(alpha = alpha))
    }
