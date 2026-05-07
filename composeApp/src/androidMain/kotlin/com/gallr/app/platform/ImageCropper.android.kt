package com.gallr.app.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayOutputStream

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
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
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

        // Clamp crop region to image bounds
        val x = offset.x.coerceIn(0, original.width - 1)
        val y = offset.y.coerceIn(0, original.height - 1)
        val w = size.width.coerceAtMost(original.width - x)
        val h = size.height.coerceAtMost(original.height - y)

        val cropped = Bitmap.createBitmap(original, x, y, w, h)

        // Resize to maxSize
        val scale = minOf(maxSize.toFloat() / cropped.width, maxSize.toFloat() / cropped.height, 1f)
        val final = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt(),
                (cropped.height * scale).toInt(),
                true,
            ).also { if (it != cropped) cropped.recycle() }
        } else {
            cropped
        }

        val out = ByteArrayOutputStream()
        final.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (final != cropped) final.recycle()
        if (cropped != original) cropped.recycle()
        original.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}
