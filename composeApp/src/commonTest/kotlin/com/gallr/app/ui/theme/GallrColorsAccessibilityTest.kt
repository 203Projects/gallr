package com.gallr.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.gallr.app.ui.components.filterChipSelectedLabelColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GallrColorsAccessibilityTest {
    @Test
    fun primary_cta_content_meets_wcag_aa_contrast() {
        val ratio = contrastRatio(GallrAccent.ctaContent, GallrAccent.ctaPrimary)

        assertTrue(ratio >= 4.5, "Primary CTA contrast was $ratio:1")
    }

    @Test
    fun a_selected_filter_chip_on_the_accent_reads_at_aa_in_both_themes() {
        for (scheme in listOf(gallrColorScheme(), gallrDarkColorScheme())) {
            val label = filterChipSelectedLabelColor(GallrAccent.activeIndicator, scheme)
            val ratio = contrastRatio(label, GallrAccent.activeIndicator)

            assertTrue(ratio >= 4.5, "Selected chip label on the accent was $ratio:1")
        }
    }

    @Test
    fun a_selected_monochrome_chip_keeps_the_inverted_label() {
        for (scheme in listOf(gallrColorScheme(), gallrDarkColorScheme())) {
            val label = filterChipSelectedLabelColor(scheme.onBackground, scheme)

            assertEquals(scheme.background, label)
            assertTrue(contrastRatio(label, scheme.onBackground) >= 4.5)
        }
    }

    @Test
    fun dialogs_menus_and_sheets_take_the_screen_background_not_a_material_tint() {
        for (scheme in listOf(gallrColorScheme(), gallrDarkColorScheme())) {
            val containers = scheme.containerRoles()

            assertEquals(List(containers.size) { scheme.background }, containers)
        }
    }

    private fun ColorScheme.containerRoles(): List<Color> =
        listOf(
            surfaceContainerLowest,
            surfaceContainerLow,
            surfaceContainer,
            surfaceContainerHigh,
            surfaceContainerHighest,
            surfaceBright,
            surfaceDim,
        )

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
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
