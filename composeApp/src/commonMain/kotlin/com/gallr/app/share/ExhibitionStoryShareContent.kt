package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

object ExhibitionStoryShareConfig {
    const val CARD_WIDTH_PX = 1080
    const val CARD_HEIGHT_PX = 1920
    const val SAFE_TOP_PX = 96
    const val SAFE_BOTTOM_PX = 88
    const val SIDE_MARGIN_PX = 56
    const val STATUS_TOP_PX = SAFE_TOP_PX + 30
    const val STATUS_HEIGHT_PX = 56
    const val STATUS_FONT_SIZE_PX = 28
    const val STATUS_PADDING_PX = 24
    const val STATUS_DOT_RADIUS_PX = 8
    const val STATUS_DOT_GAP_PX = 12
    const val IMAGE_SIZE_PX = CARD_WIDTH_PX - SIDE_MARGIN_PX * 2
    const val IMAGE_TOP_PX = SAFE_TOP_PX + 140
    const val IMAGE_BOTTOM_PX = IMAGE_TOP_PX + IMAGE_SIZE_PX
    const val IMAGE_SHADOW_BLUR_PX = 40
    const val IMAGE_SHADOW_OFFSET_Y_PX = 16
    const val TITLE_TOP_PX = IMAGE_BOTTOM_PX + 64
    const val TITLE_FONT_SIZE_PX = 44
    const val TITLE_LINE_HEIGHT_PX = 56
    const val TITLE_MAX_LINES = 2
    const val TITLE_HEIGHT_PX = TITLE_LINE_HEIGHT_PX * TITLE_MAX_LINES
    const val VENUE_TOP_PX = IMAGE_BOTTOM_PX + 178
    const val VENUE_FONT_SIZE_PX = 28
    const val VENUE_HEIGHT_PX = 36
    const val DIVIDER_TOP_PX = IMAGE_BOTTOM_PX + 226
    const val DIVIDER_WIDTH_PX = 360
    const val DATE_TOP_PX = IMAGE_BOTTOM_PX + 268
    const val DATE_FONT_SIZE_PX = 28
    const val DATE_HEIGHT_PX = 36
    const val DETAIL_TOP_PX = IMAGE_BOTTOM_PX + 312
    const val DETAIL_FONT_SIZE_PX = 26
    const val DETAIL_HEIGHT_PX = 36
    const val SWATCH_SIZE_PX = 26
    const val SWATCH_GAP_PX = 8
    const val QR_MAX_PX = 240
    const val QR_MIN_MODULE_PX = 3
    const val QR_CORNER_RADIUS_PX = 18
    const val QR_TOP_PX = CARD_HEIGHT_PX - SAFE_BOTTOM_PX - QR_MAX_PX
    const val BRAND_FONT_SIZE_PX = 34
    const val BRAND_MARK_SIZE_PX = 40
    const val BRAND_GAP_PX = 16
    const val BRAND_HEIGHT_PX = 48
    const val BRAND_TOP_PX = QR_TOP_PX + 60
    const val CAPTION_TOP_PX = BRAND_TOP_PX + 72
    const val CAPTION_FONT_SIZE_PX = 24
    const val CAPTION_LINE_HEIGHT_PX = 36
    const val CAPTION_URL_TEXT = "gallrmap.com"
}

/** Pixels per QR module: whole pixels for crisp edges, never below [ExhibitionStoryShareConfig.QR_MIN_MODULE_PX]. */
fun qrModulePx(modules: Int): Int =
    (ExhibitionStoryShareConfig.QR_MAX_PX / modules).coerceAtLeast(ExhibitionStoryShareConfig.QR_MIN_MODULE_PX)

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
                text = content.venueLine,
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

data class ExhibitionStoryShareContent(
    val title: String,
    val venue: String,
    val dateRange: String,
    val coverImageUrl: String?,
    val shareDescriptor: String,
    val region: String = "",
    val status: ShareCardStatus? = null,
    val detailLine: String? = null,
    val webUrl: String? = null,
    val qrCaption: String = "",
) {
    /** Venue and 동네 on one line, e.g. "학고재갤러리  ·  종로구". */
    val venueLine: String get() = listOf(venue, region).filter { it.isNotBlank() }.joinToString("  ·  ")

    companion object {
        fun from(
            exhibition: Exhibition,
            lang: AppLanguage,
            today: LocalDate = Clock.System.todayIn(TimeZone.of("Asia/Seoul")),
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
                region = exhibition.localizedRegion(lang).trim().uppercase(),
                status = shareCardStatus(exhibition.openingDate, exhibition.closingDate, today, lang),
                detailLine =
                    shareCardDetailLine(
                        receptionDate = exhibition.receptionDate,
                        openingTime = exhibition.openingTime,
                        hours = exhibition.hours,
                        today = today,
                        lang = lang,
                    ),
                webUrl = exhibitionWebUrl(exhibition.nameEn, exhibition.nameKo, exhibition.id),
                qrCaption = if (lang == AppLanguage.KO) "스캔해서 전시 정보·지도 보기" else "Scan for details and map",
            )
        }
    }
}
