package com.gallr.app.ui.util

import androidx.compose.ui.graphics.Color
import com.gallr.shared.util.parseHexColor

/**
 * Parses a "#RRGGBB" or "RRGGBB" hex string into a Compose [Color], or null if
 * the input is malformed. Uses the [Color] component factory (red/green/blue
 * Ints) so the result has a properly initialized sRGB ColorSpace on both
 * Android (JVM) and iOS (Skia) — passing a packed Long or ULong directly into
 * [Color]'s long-arity constructors yields garbage ColorSpace bits and crashes
 * with ArrayIndexOutOfBoundsException during text-span layout.
 */
fun composeColorOrNull(hex: String?): Color? {
    val argb = parseHexColor(hex) ?: return null
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    return Color(red = r, green = g, blue = b, alpha = 255)
}
