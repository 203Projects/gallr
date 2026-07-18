package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl

object ExhibitionStoryShareConfig {
    const val cardWidthPx = 1080
    const val cardHeightPx = 1920
    const val safeTopPx = 96
    const val safeBottomPx = 88
    const val sideMarginPx = 56
    const val imageSizePx = cardWidthPx - sideMarginPx * 2
    const val textTopGapPx = 28
}

fun brandGroupStartX(cardWidth: Int, markSize: Float, gap: Float, textWidth: Float): Float =
    (cardWidth - (markSize + gap + textWidth)) / 2f

data class ExhibitionStoryShareContent(
    val title: String,
    val venue: String,
    val dateRange: String,
    val coverImageUrl: String?,
    val shareDescriptor: String,
) {
    companion object {
        fun from(exhibition: Exhibition, lang: AppLanguage): ExhibitionStoryShareContent {
            val title = exhibition.localizedName(lang)
            return ExhibitionStoryShareContent(
                title = title,
                venue = exhibition.localizedVenueName(lang).uppercase(),
                dateRange = exhibition.localizedDateRange(lang),
                // Story cards crop and size natively on Android/iOS. Keep this
                // on the public object URL to avoid Supabase transformation quota.
                coverImageUrl = exhibition.coverImageUrl?.takeIf { it.isNotBlank() }
                    ?.let { nativeSupabaseImageUrl(it) },
                shareDescriptor = if (lang == AppLanguage.KO) {
                    "\"$title\" 이미지"
                } else {
                    "\"$title\" image"
                },
            )
        }
    }
}
