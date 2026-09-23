package com.gallr.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterPaletteTest {
    private fun rgba(vararg pixels: IntArray): ByteArray =
        pixels.flatMap { pixel -> pixel.map { it.toByte() } }.toByteArray()

    private fun repeat(
        count: Int,
        pixel: IntArray,
    ): Array<IntArray> = Array(count) { pixel }

    private fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    // Expected values are produced by gallery/src/exhibitionQr.ts derivePosterPalette so
    // the app's share card and the portal's printed QR agree for the same poster.
    @Test
    fun `qr colors match the gallery portal for a warm poster`() {
        val pixels =
            rgba(
                *repeat(12, intArrayOf(232, 70, 42, 255)),
                *repeat(5, intArrayOf(246, 184, 31, 255)),
                *repeat(3, intArrayOf(28, 90, 190, 255)),
                intArrayOf(255, 255, 255, 0),
            )

        val palette = PosterPalette.fromRgba(pixels)

        assertEquals(
            listOf("#752315", "#882919", "#6C500E", "#9A2F1C", "#1951AB"),
            palette.qrColors.map(::hex),
        )
    }

    @Test
    fun `sparse poster ignores transparent pixels and adds tonal steps`() {
        val palette =
            PosterPalette.fromRgba(
                rgba(intArrayOf(255, 255, 255, 0), intArrayOf(45, 120, 88, 255), intArrayOf(255, 255, 255, 0)),
            )

        assertEquals(
            listOf("#133124", "#173D2D", "#1B4835", "#1F543D", "#245F46"),
            palette.qrColors.map(::hex),
        )
    }

    @Test
    fun `fully transparent poster falls back to neutral greys`() {
        val palette = PosterPalette.fromRgba(rgba(intArrayOf(0, 0, 0, 0)))

        assertEquals(
            listOf("#000000", "#181818", "#2C2C2C", "#404040", "#555555"),
            palette.qrColors.map(::hex),
        )
        assertEquals(PosterPalette.FALLBACK, palette)
    }

    @Test
    fun `every qr color keeps 7 to 1 contrast on white`() {
        val palette =
            PosterPalette.fromRgba(
                rgba(*repeat(20, intArrayOf(250, 240, 120, 255)), *repeat(4, intArrayOf(180, 220, 255, 255))),
            )

        palette.qrColors.forEach { color ->
            val contrast = 1.05 / (relativeLuminance(color) + 0.05)
            assertTrue(contrast >= 7.0, "${hex(color)} contrast $contrast")
        }
    }

    @Test
    fun `paper tint keeps the dominant hue but stays near white`() {
        val palette = PosterPalette.fromRgba(rgba(*repeat(10, intArrayOf(232, 70, 42, 255))))

        val top = palette.paperTop
        val red = (top shr 16) and 0xFF
        val green = (top shr 8) and 0xFF
        val blue = top and 0xFF
        assertTrue(red > green && red > blue, "tint should lean red: ${hex(top)}")
        assertTrue(relativeLuminance(top) > 0.8, "tint should stay light: ${hex(top)}")
        assertTrue(relativeLuminance(palette.paperBottom) < relativeLuminance(top))
    }

    @Test
    fun `ink is the darkest qr color`() {
        val palette = PosterPalette.fromRgba(rgba(*repeat(10, intArrayOf(28, 90, 190, 255))))

        assertEquals(palette.qrColors.first(), palette.ink)
    }
}
