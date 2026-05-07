package com.gallr.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Decode raw image bytes into an [ImageBitmap] for display in the crop UI. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/**
 * Crop the image from [rawBytes] at the specified region,
 * resize to fit within [maxSize] px, and return JPEG-compressed bytes.
 */
expect fun cropAndCompress(
    rawBytes: ByteArray,
    offset: IntOffset,
    size: IntSize,
    maxSize: Int = 512,
    quality: Int = 80,
): ByteArray?
