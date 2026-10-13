package com.gallr.app.ui.theme

import androidx.compose.ui.unit.dp
import com.gallr.app.share.relativeLuminance
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 091 revision 2: the Home visual tokens hold the promises DESIGN.md makes for them. */
class HomeVisualTokensTest {
    @Test
    fun theScrimKeepsWhiteCaptionsReadableOnTheWorstCover() {
        // A black scrim at the token alpha over a pure white cover, under white text.
        val scrimmed = blend(cover = 0xFFFFFF, wash = 0x000000, alpha = HomeVisualTokens.SCRIM_ALPHA)
        val contrast = contrast(scrimmed, 0xFFFFFF)

        assertTrue(contrast >= 4.5, "white text over a scrimmed white cover: $contrast")
        assertTrue(HomeVisualTokens.SCRIM_PRESSED_ALPHA > HomeVisualTokens.SCRIM_ALPHA, "pressing only darkens")
    }

    @Test
    fun aBottomScrimIsFullFromTheCaptionsTopEdgeAndFadesInAboveIt() {
        // A caption 20% tall: full strength from 80% down, fading in from the default start.
        assertEquals(ScrimBand(fadeEdge = 0.44f, captionEdge = 0.8f), bottomScrimBand(1000, 200))
        // A caption 70% tall reaches above the default start, so the fade begins a lead above it instead.
        val tall = bottomScrimBand(coverHeightPx = 1000, captionHeightPx = 700)
        assertEquals(0.3f, tall.captionEdge, 0.001f)
        assertEquals(0f, tall.fadeEdge, 0.001f)
        // Before the caption is measured the scrim covers the default band.
        assertEquals(ScrimBand(fadeEdge = 0.44f, captionEdge = 0.74f), bottomScrimBand(0, 0))
    }

    @Test
    fun aTopScrimMirrorsIt() {
        val band = topScrimBand(coverHeightPx = 1000, captionHeightPx = 400)
        assertEquals(0.4f, band.captionEdge, 0.001f)
        assertEquals(0.7f, band.fadeEdge, 0.001f)
        // A short caption still gets the default depth.
        assertEquals(0.56f, topScrimBand(coverHeightPx = 1000, captionHeightPx = 100).fadeEdge, 0.001f)
        // A caption as tall as the cover cannot push the fade past the bottom edge.
        assertEquals(1f, topScrimBand(coverHeightPx = 1000, captionHeightPx = 1000).fadeEdge, 0.001f)
    }

    @Test
    fun theRadiiAndDotLimitAreTheDocumentedOnes() {
        assertEquals(16.dp, HomeVisualTokens.cornerRadius)
        assertEquals(12.dp, HomeVisualTokens.innerRadius)
        assertEquals(10, HomeVisualTokens.DOT_LIMIT)
    }

    /** Compose blends in sRGB: each channel is (1 - alpha) * cover + alpha * wash. */
    private fun blend(
        cover: Int,
        wash: Int,
        alpha: Float,
    ): Int {
        fun channel(shift: Int): Int {
            val c = (cover shr shift) and 0xFF
            val w = (wash shr shift) and 0xFF
            return ((1 - alpha) * c + alpha * w).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = relativeLuminance(a or (0xFF shl 24))
        val lb = relativeLuminance(b or (0xFF shl 24))
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
