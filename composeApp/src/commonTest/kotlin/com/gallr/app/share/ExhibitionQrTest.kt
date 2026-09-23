package com.gallr.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExhibitionQrTest {
    private val url = "https://gallrmap.com/exhibitions/void-forms-abcd/"
    private val palette =
        listOf(0xFF133124, 0xFF173D2D, 0xFF1B4835, 0xFF1F543D, 0xFF245F46).map { it.toInt() }

    @Test
    fun `seed hash matches the gallery portal FNV-1a`() {
        // Node: gallery/src/exhibitionQr.ts seedHash("https://gallrmap.com/exhibitions/void-forms-abcd/")
        assertEquals(1666445549L, exhibitionQrSeedHash(url))
    }

    @Test
    fun `quiet zone is four light modules on every side`() {
        val qr = ExhibitionQr.encode(url, palette)

        for (i in 0 until qr.size) {
            for (edge in 0 until ExhibitionQr.QUIET_ZONE) {
                assertNull(qr.colorAt(edge, i))
                assertNull(qr.colorAt(qr.size - 1 - edge, i))
                assertNull(qr.colorAt(i, edge))
                assertNull(qr.colorAt(i, qr.size - 1 - edge))
            }
        }
    }

    @Test
    fun `finder patterns always use the darkest tone`() {
        val qr = ExhibitionQr.encode(url, palette)
        val z = ExhibitionQr.QUIET_ZONE

        for (row in 0 until 7) {
            for (col in 0 until 7) {
                qr.colorAt(z + row, z + col)?.let { assertEquals(palette.first(), it) }
            }
        }
        // Top-left finder's outer ring is dark.
        assertEquals(palette.first(), qr.colorAt(z, z))
    }

    @Test
    fun `data modules spread across the whole palette`() {
        val qr = ExhibitionQr.encode(url, palette)
        val used = mutableSetOf<Int>()
        for (row in 0 until qr.size) for (col in 0 until qr.size) qr.colorAt(row, col)?.let(used::add)

        assertEquals(palette.toSet(), used)
    }

    @Test
    fun `data module colour follows the portal's seeded formula`() {
        val qr = ExhibitionQr.encode(url, palette)
        val hash = exhibitionQrSeedHash(url)
        var checked = 0
        for (row in 0 until qr.size) {
            for (col in 0 until qr.size) {
                val color = qr.colorAt(row, col) ?: continue
                if (!qr.isDataModule(row, col)) continue
                val expected = palette[((hash + (row + 1) * 31L + (col + 1) * 17L) % palette.size).toInt()]
                assertEquals(expected, color, "module $row,$col")
                checked++
            }
        }
        assertTrue(checked > 100)
    }
}
