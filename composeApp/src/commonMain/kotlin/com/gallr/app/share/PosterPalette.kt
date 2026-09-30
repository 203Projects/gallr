package com.gallr.app.share

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colours sampled from an exhibition poster, shared by both native story-card renderers.
 *
 * [qrColors] mirrors gallery/src/exhibitionQr.ts `derivePosterPalette` exactly, so the app's
 * share card and the portal's printed QR use the same tones for the same poster. Every QR
 * colour keeps at least 7:1 contrast on white (DESIGN.md artwork-derived export exception).
 * All colours are opaque ARGB.
 */
data class PosterPalette(
    /** Five scan-safe tones, darkest first. */
    val qrColors: List<Int>,
    /** Light paper at the top of the card: the dominant poster colour washed toward white. */
    val paperTop: Int,
    /** Slightly deeper paper at the bottom of the card. */
    val paperBottom: Int,
    /** The poster's most characteristic colour before any darkening or washing. */
    val dominant: Int,
) {
    /** Darkest tone; used for type, the status label and QR function patterns. */
    val ink: Int get() = qrColors.first()

    companion object {
        /** Side of the square the poster is downscaled to before sampling. */
        const val SAMPLE_SIZE = 48

        private const val PALETTE_SIZE = 5
        private const val MAX_DARK_MODULE_LUMINANCE = 0.09
        private const val MIN_COLOR_DISTANCE = 42.0
        private const val PAPER_TOP_WASH = 0.90
        private const val PAPER_BOTTOM_WASH = 0.80
        private val TONAL_STEPS = listOf(0.88, 0.76, 0.64, 0.52, 0.4, 0.3)
        private val FALLBACK_QR =
            listOf(0xFF000000, 0xFF181818, 0xFF2C2C2C, 0xFF404040, 0xFF555555).map { it.toInt() }

        val FALLBACK =
            PosterPalette(
                qrColors = FALLBACK_QR,
                paperTop = 0xFFFFFFFF.toInt(),
                paperBottom = 0xFFF2F2F2.toInt(),
                dominant = 0xFFFFFFFF.toInt(),
            )

        /** [rgba] is tightly packed 8-bit RGBA, typically a [SAMPLE_SIZE]² downscale of the poster. */
        fun fromRgba(rgba: ByteArray): PosterPalette {
            val candidates = paletteCandidates(rgba)
            if (candidates.isEmpty()) return FALLBACK

            val safeColors = candidates.map(::scanSafe)
            val hexes = LinkedHashSet(safeColors.map(::toArgb))
            val dominant = safeColors.first()
            for (step in TONAL_STEPS) {
                if (hexes.size >= PALETTE_SIZE) break
                hexes += toArgb(dominant.scaled(step))
            }
            for (fallback in FALLBACK_QR) {
                if (hexes.size >= PALETTE_SIZE) break
                hexes += fallback
            }
            val qrColors = hexes.sortedBy(::relativeLuminance).take(PALETTE_SIZE)

            val raw = candidates.first()
            return PosterPalette(
                qrColors = qrColors,
                paperTop = toArgb(raw.mix(WHITE, PAPER_TOP_WASH)),
                paperBottom = toArgb(raw.mix(WHITE, PAPER_BOTTOM_WASH)),
                dominant = toArgb(raw),
            )
        }

        private fun paletteCandidates(rgba: ByteArray): List<Rgb> {
            val buckets = LinkedHashMap<Int, Bucket>()
            var index = 0
            while (index + 3 < rgba.size) {
                val alpha = rgba[index + 3].toInt() and 0xFF
                if (alpha >= 128) {
                    val red = rgba[index].toInt() and 0xFF
                    val green = rgba[index + 1].toInt() and 0xFF
                    val blue = rgba[index + 2].toInt() and 0xFF
                    val key = (red shr 5) shl 10 or ((green shr 5) shl 5) or (blue shr 5)
                    buckets.getOrPut(key) { Bucket() }.add(red, green, blue)
                }
                index += 4
            }
            val selected = mutableListOf<Rgb>()
            buckets.values
                .map { it.average() to it.count }
                .sortedByDescending { (color, count) -> count * (0.25 + color.saturation() * 1.75) }
                .forEach { (color, _) ->
                    if (selected.size < PALETTE_SIZE && selected.all { it.distanceTo(color) >= MIN_COLOR_DISTANCE }) {
                        selected += color
                    }
                }
            return selected
        }

        private fun scanSafe(color: Rgb): Rgb {
            if (color.luminance() <= MAX_DARK_MODULE_LUMINANCE) return color
            var safeScale = 0.0
            var unsafeScale = 1.0
            repeat(16) {
                val candidate = (safeScale + unsafeScale) / 2
                if (color.scaled(candidate).luminance() <= MAX_DARK_MODULE_LUMINANCE) {
                    safeScale = candidate
                } else {
                    unsafeScale = candidate
                }
            }
            return color.scaled(safeScale)
        }

        private val WHITE = Rgb(255.0, 255.0, 255.0)
    }
}

/** WCAG relative luminance of an opaque ARGB colour. */
fun relativeLuminance(argb: Int): Double =
    Rgb(
        ((argb shr 16) and 0xFF).toDouble(),
        ((argb shr 8) and 0xFF).toDouble(),
        (argb and 0xFF).toDouble(),
    ).luminance()

private class Bucket {
    var count = 0
    private var red = 0.0
    private var green = 0.0
    private var blue = 0.0

    fun add(
        r: Int,
        g: Int,
        b: Int,
    ) {
        count++
        red += r
        green += g
        blue += b
    }

    fun average() = Rgb(red / count, green / count, blue / count)
}

private data class Rgb(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    fun luminance(): Double =
        channelToLinear(red) * 0.2126 + channelToLinear(green) * 0.7152 + channelToLinear(blue) * 0.0722

    fun scaled(scale: Double) = Rgb(red * scale, green * scale, blue * scale)

    fun mix(
        other: Rgb,
        amount: Double,
    ) = Rgb(
        red + (other.red - red) * amount,
        green + (other.green - green) * amount,
        blue + (other.blue - blue) * amount,
    )

    fun saturation(): Double {
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        return if (maximum == 0.0) 0.0 else (maximum - minimum) / maximum
    }

    fun distanceTo(other: Rgb): Double = hypot(hypot(red - other.red, green - other.green), blue - other.blue)
}

private fun channelToLinear(channel: Double): Double {
    val value = channel / 255
    return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

/** Rounds like JavaScript's Math.round so hex output matches the web implementation. */
private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

private fun toArgb(color: Rgb): Int =
    (0xFF shl 24) or
        (jsRound(color.red).coerceIn(0, 255) shl 16) or
        (jsRound(color.green).coerceIn(0, 255) shl 8) or
        jsRound(color.blue).coerceIn(0, 255)

/** Linear blend of two opaque ARGB colours; [amount] 0 keeps [from], 1 gives [to]. */
fun mixArgb(
    from: Int,
    to: Int,
    amount: Double,
): Int {
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return jsRound(a + (b - a) * amount).coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
