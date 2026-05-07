package com.gallr.shared.util

/**
 * Parses a hex color string to an ARGB long (alpha forced to 0xFF).
 * Accepts "#RRGGBB" or "RRGGBB", case-insensitive. Returns null on any
 * malformed input — callers should fall back to a safe default.
 */
fun parseHexColor(input: String?): Long? {
    if (input.isNullOrBlank()) return null
    val cleaned = input.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(radix = 16) ?: return null
    return 0xFF000000L or rgb
}
