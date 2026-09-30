package com.gallr.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryCardColorsTest {
    private val warm = PosterPalette.fromRgba(ByteArray(40) { i -> intArrayOf(232, 70, 42, 255)[i % 4].toByte() })
    private val pale = PosterPalette.fromRgba(ByteArray(40) { i -> intArrayOf(250, 240, 200, 255)[i % 4].toByte() })

    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val (light, dark) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `light card uses poster paper and poster ink`() {
        val colors = storyCardColors(ExhibitionStoryCardPalette.LIGHT, warm)

        assertEquals(warm.paperTop, colors.paperTop)
        assertEquals(warm.paperBottom, colors.paperBottom)
        assertEquals(warm.ink, colors.title)
    }

    @Test
    fun `dark card keeps a dark paper with only a hint of the poster`() {
        val colors = storyCardColors(ExhibitionStoryCardPalette.DARK, warm)

        assertTrue(relativeLuminance(colors.paperTop) < 0.05)
        assertTrue(relativeLuminance(colors.paperBottom) < 0.05)
        val red = (colors.paperTop shr 16) and 0xFF
        val blue = colors.paperTop and 0xFF
        assertTrue(red > blue, "dark paper should still lean toward the poster hue")
        assertEquals(ExhibitionStoryCardPalette.DARK.title, colors.title)
    }

    @Test
    fun `dark fallback is the plain theme background`() {
        val colors = storyCardColors(ExhibitionStoryCardPalette.DARK, PosterPalette.FALLBACK)

        assertEquals(ExhibitionStoryCardPalette.DARK.background, colors.paperTop)
        assertEquals(ExhibitionStoryCardPalette.DARK.background, colors.paperBottom)
    }

    @Test
    fun `text stays readable on paper in both themes`() {
        for (theme in listOf(ExhibitionStoryCardPalette.LIGHT, ExhibitionStoryCardPalette.DARK)) {
            for (poster in listOf(warm, pale, PosterPalette.FALLBACK)) {
                val colors = storyCardColors(theme, poster)
                assertTrue(contrast(colors.title, colors.paperBottom) >= 4.5, "title $theme $poster")
                assertTrue(contrast(colors.secondary, colors.paperBottom) >= 4.5, "secondary $theme $poster")
            }
        }
    }

    @Test
    fun `qr always sits on an opaque white tile`() {
        assertEquals(0xFFFFFFFF.toInt(), storyCardColors(ExhibitionStoryCardPalette.DARK, warm).qrTile)
        assertEquals(0xFFFFFFFF.toInt(), storyCardColors(ExhibitionStoryCardPalette.LIGHT, warm).qrTile)
    }

    @Test
    fun `status dot uses the accent only when emphasized`() {
        val colors = storyCardColors(ExhibitionStoryCardPalette.LIGHT, warm)

        assertEquals(0xFFFF5400.toInt(), colors.statusDot(emphasized = true))
        assertEquals(colors.title, colors.statusDot(emphasized = false))
    }
}
