package com.gallr.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class GallrColorsAccessibilityTest {
    @Test
    fun primary_cta_content_meets_wcag_aa_contrast() {
        val ratio = contrastRatio(GallrAccent.ctaContent, GallrAccent.ctaPrimary)

        assertTrue(ratio >= 4.5, "Primary CTA contrast was $ratio:1")
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = max(foreground.relativeLuminance(), background.relativeLuminance())
        val darker = min(foreground.relativeLuminance(), background.relativeLuminance())
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Color.relativeLuminance(): Double =
        0.2126 * red.linearized() +
            0.7152 * green.linearized() +
            0.0722 * blue.linearized()

    private fun Float.linearized(): Double {
        val component = toDouble()
        return if (component <= 0.04045) {
            component / 12.92
        } else {
            ((component + 0.055) / 1.055).pow(2.4)
        }
    }
}
