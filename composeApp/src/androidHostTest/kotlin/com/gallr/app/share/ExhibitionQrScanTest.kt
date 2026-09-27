package com.gallr.app.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

class ExhibitionQrScanTest {
    private fun decode(qr: ExhibitionQr): com.google.zxing.Result {
        val scale = 6
        val side = qr.size * scale
        val pixels =
            IntArray(side * side) { index ->
                val row = index / side / scale
                val col = index % side / scale
                qr.colorAt(row, col) ?: 0xFFFFFFFF.toInt()
            }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
    }

    @Test
    fun `poster coloured qr decodes to the exhibition url at level H`() {
        val url = "https://gallrmap.com/exhibitions/風徑無住-between-the-feathers-soyoon-lee-36b1/"
        val warmPixel = byteArrayOf(232.toByte(), 70, 42, 255.toByte())
        val palette = PosterPalette.fromRgba(ByteArray(4 * 10) { i -> warmPixel[i % 4] })

        val result = decode(ExhibitionQr.encode(url, palette.qrColors))

        assertEquals(url, result.text)
        assertEquals("H", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun `fallback grey qr decodes`() {
        val url = "https://gallrmap.com/exhibitions/afterimage-choi-ean-1308/"

        assertEquals(url, decode(ExhibitionQr.encode(url, PosterPalette.FALLBACK.qrColors)).text)
    }
}
