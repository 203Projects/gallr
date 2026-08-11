package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl

object ExhibitionStoryShareConfig {
    const val CARD_WIDTH_PX = 1080
    const val CARD_HEIGHT_PX = 1920
    const val SAFE_TOP_PX = 96
    const val SAFE_BOTTOM_PX = 88
    const val SIDE_MARGIN_PX = 56
    const val IMAGE_SIZE_PX = CARD_WIDTH_PX - SIDE_MARGIN_PX * 2
    const val IMAGE_TOP_PX = SAFE_TOP_PX + 140
    const val IMAGE_BOTTOM_PX = IMAGE_TOP_PX + IMAGE_SIZE_PX
    const val TITLE_TOP_PX = IMAGE_BOTTOM_PX + 72
    const val TITLE_FONT_SIZE_PX = 44
    const val TITLE_LINE_HEIGHT_PX = 56
    const val TITLE_MAX_LINES = 2
    const val TITLE_HEIGHT_PX = TITLE_LINE_HEIGHT_PX * TITLE_MAX_LINES
    const val VENUE_TOP_PX = IMAGE_BOTTOM_PX + 194
    const val VENUE_FONT_SIZE_PX = 28
    const val VENUE_HEIGHT_PX = 36
    const val DIVIDER_TOP_PX = IMAGE_BOTTOM_PX + 244
    const val DIVIDER_WIDTH_PX = 360
    const val DATE_TOP_PX = IMAGE_BOTTOM_PX + 292
    const val DATE_FONT_SIZE_PX = 26
    const val DATE_HEIGHT_PX = 36
    const val BRAND_FONT_SIZE_PX = 34
    const val BRAND_MARK_SIZE_PX = 40
    const val BRAND_GAP_PX = 16
    const val BRAND_HEIGHT_PX = 48
    const val BRAND_TOP_PX = CARD_HEIGHT_PX - SAFE_BOTTOM_PX - 86
}

data class ExhibitionStoryTextLayout(
    val titleLines: List<String>,
    val venue: String,
)

fun exhibitionStoryTextLayout(
    content: ExhibitionStoryShareContent,
    measureTitle: (String) -> Float,
    measureVenue: (String) -> Float,
): ExhibitionStoryTextLayout =
    ExhibitionStoryTextLayout(
        titleLines =
            wrapMeasuredText(
                text = content.title,
                maxWidth = ExhibitionStoryShareConfig.IMAGE_SIZE_PX.toFloat(),
                maxLines = ExhibitionStoryShareConfig.TITLE_MAX_LINES,
                measureWidth = measureTitle,
            ),
        venue =
            ellipsizeMeasuredText(
                text = content.venue,
                maxWidth = ExhibitionStoryShareConfig.IMAGE_SIZE_PX.toFloat(),
                measureWidth = measureVenue,
            ),
    )

fun wrapMeasuredText(
    text: String,
    maxWidth: Float,
    maxLines: Int,
    measureWidth: (String) -> Float,
): List<String> {
    require(maxWidth > 0f) { "maxWidth must be positive" }
    require(maxLines > 0) { "maxLines must be positive" }

    var remaining = text.trim().replace(Regex("\\s+"), " ")
    if (remaining.isEmpty()) return emptyList()

    val lines = mutableListOf<String>()
    repeat(maxLines) { lineIndex ->
        if (measureWidth(remaining) <= maxWidth) {
            lines += remaining
            return lines
        }

        val isLastLine = lineIndex == maxLines - 1
        if (isLastLine) {
            lines += ellipsizeMeasuredText(remaining, maxWidth, measureWidth)
            return lines
        }

        val measuredEnd = largestMeasuredPrefix(remaining, maxWidth, measureWidth)
        val whitespaceEnd = remaining.lastIndexOf(' ', startIndex = (measuredEnd - 1).coerceAtLeast(0))
        val lineEnd = whitespaceEnd.takeIf { it > 0 } ?: measuredEnd
        lines += remaining.substring(0, lineEnd).trimEnd()
        remaining = remaining.substring(lineEnd).trimStart()
    }
    return lines
}

fun ellipsizeMeasuredText(
    text: String,
    maxWidth: Float,
    measureWidth: (String) -> Float,
): String {
    require(maxWidth > 0f) { "maxWidth must be positive" }

    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.isEmpty() || measureWidth(normalized) <= maxWidth) return normalized

    val ellipsis = "…"
    var end = largestMeasuredPrefix(normalized, maxWidth, measureWidth)
    while (end > 0 && measureWidth(normalized.substring(0, end).trimEnd() + ellipsis) > maxWidth) {
        end--
    }
    return normalized.substring(0, end).trimEnd() + ellipsis
}

private fun largestMeasuredPrefix(
    text: String,
    maxWidth: Float,
    measureWidth: (String) -> Float,
): Int {
    var end = 1
    while (end <= text.length && measureWidth(text.substring(0, end)) <= maxWidth) {
        end++
    }
    return (end - 1).coerceAtLeast(1)
}

fun brandGroupStartX(
    cardWidth: Int,
    markSize: Float,
    gap: Float,
    textWidth: Float,
): Float = (cardWidth - (markSize + gap + textWidth)) / 2f

data class ExhibitionStoryShareContent(
    val title: String,
    val venue: String,
    val dateRange: String,
    val coverImageUrl: String?,
    val shareDescriptor: String,
) {
    companion object {
        fun from(
            exhibition: Exhibition,
            lang: AppLanguage,
        ): ExhibitionStoryShareContent {
            val title = exhibition.localizedName(lang)
            return ExhibitionStoryShareContent(
                title = title,
                venue = exhibition.localizedVenueName(lang).uppercase(),
                dateRange = exhibition.localizedDateRange(lang),
                // Story cards crop and size natively on Android/iOS. Keep this
                // on the public object URL to avoid Supabase transformation quota.
                coverImageUrl =
                    exhibition.coverImageUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { nativeSupabaseImageUrl(it) },
                shareDescriptor =
                    if (lang == AppLanguage.KO) {
                        "\"$title\" 이미지"
                    } else {
                        "\"$title\" image"
                    },
            )
        }
    }
}
