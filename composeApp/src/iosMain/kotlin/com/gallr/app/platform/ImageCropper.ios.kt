package com.gallr.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

actual fun cropAndCompress(
    rawBytes: ByteArray,
    offset: IntOffset,
    size: IntSize,
    maxSize: Int,
    quality: Int,
): ByteArray? {
    return try {
        val srcImage = Image.makeFromEncoded(rawBytes)

        // Clamp crop region
        val cx = offset.x.coerceIn(0, srcImage.width - 1)
        val cy = offset.y.coerceIn(0, srcImage.height - 1)
        val cw = size.width.coerceAtMost(srcImage.width - cx)
        val ch = size.height.coerceAtMost(srcImage.height - cy)

        // Resize target
        val scale = minOf(maxSize.toFloat() / cw, maxSize.toFloat() / ch, 1f)
        val finalW = (cw * scale).toInt()
        val finalH = (ch * scale).toInt()

        // Render cropped + resized via Skia surface
        val surface = Surface.makeRasterN32Premul(finalW, finalH)
        surface.canvas.drawImageRect(
            srcImage,
            Rect.makeXYWH(cx.toFloat(), cy.toFloat(), cw.toFloat(), ch.toFloat()),
            Rect.makeWH(finalW.toFloat(), finalH.toFloat()),
        )
        val resultImage = surface.makeImageSnapshot()
        val data = resultImage.encodeToData(EncodedImageFormat.JPEG, quality) ?: return null
        data.bytes
    } catch (_: Exception) {
        null
    }
}
