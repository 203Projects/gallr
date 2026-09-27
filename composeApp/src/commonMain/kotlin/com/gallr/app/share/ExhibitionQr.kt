package com.gallr.app.share

import qrcode.internals.QRCodeSquareType
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * Poster-coloured QR modules for a share card, including the quiet zone.
 *
 * Colouring mirrors gallery/src/exhibitionQr.ts `renderExhibitionQrSvg`: structural modules
 * (finders, alignment, timing, format and version info) use the darkest tone; data modules
 * pick a palette tone from a hash of the URL so the pattern is stable per exhibition.
 */
class ExhibitionQr private constructor(
    /** Side length in modules, quiet zone included. */
    val size: Int,
    private val colors: IntArray,
    private val dataModules: BooleanArray,
) {
    /** Opaque ARGB for a dark module, or null for a light one. */
    fun colorAt(
        row: Int,
        col: Int,
    ): Int? = colors[row * size + col].takeIf { it != LIGHT }

    fun isDataModule(
        row: Int,
        col: Int,
    ): Boolean = dataModules[row * size + col]

    companion object {
        const val QUIET_ZONE = 4
        private const val LIGHT = 0

        fun encode(
            url: String,
            palette: List<Int>,
        ): ExhibitionQr {
            require(palette.isNotEmpty()) { "palette must not be empty" }
            // qrcode-kotlin's VERY_HIGH is QR level H (30%), matching the portal's `ecc: "H"`.
            val raw = QRCodeProcessor(url, ErrorCorrectionLevel.VERY_HIGH).encode()
            val core = raw.size
            val size = core + QUIET_ZONE * 2
            val version = (core - 17) / 4
            val hash = exhibitionQrSeedHash(url)
            val colors = IntArray(size * size) { LIGHT }
            val dataModules = BooleanArray(size * size)

            for (r in 0 until core) {
                for (c in 0 until core) {
                    val square = raw[r][c]
                    val row = r + QUIET_ZONE
                    val col = c + QUIET_ZONE
                    val isData =
                        square.squareInfo.type == QRCodeSquareType.DEFAULT &&
                            !isFormatOrVersionInfo(r, c, core, version)
                    dataModules[row * size + col] = isData
                    if (!square.dark) continue
                    colors[row * size + col] =
                        if (isData) {
                            palette[((hash + (row + 1) * 31L + (col + 1) * 17L) % palette.size).toInt()]
                        } else {
                            palette.first()
                        }
                }
            }
            return ExhibitionQr(size, colors, dataModules)
        }

        private fun isFormatOrVersionInfo(
            row: Int,
            col: Int,
            core: Int,
            version: Int,
        ): Boolean {
            val format =
                (row == 8 && (col <= 8 || col >= core - 8)) ||
                    (col == 8 && (row <= 8 || row >= core - 8))
            val versionInfo =
                version >= 7 &&
                    ((row < 6 && col in core - 11 until core - 8) || (col < 6 && row in core - 11 until core - 8))
            return format || versionInfo
        }
    }
}

/** 32-bit FNV-1a over UTF-16 code units, as gallery/src/exhibitionQr.ts `seedHash`. */
fun exhibitionQrSeedHash(value: String): Long {
    var hash = 2166136261L.toInt()
    for (char in value) {
        hash = hash xor char.code
        hash *= 16777619
    }
    return hash.toLong() and 0xFFFFFFFFL
}
