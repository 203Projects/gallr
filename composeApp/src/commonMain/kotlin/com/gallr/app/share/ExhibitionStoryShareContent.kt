package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

object ExhibitionStoryShareConfig {
    const val cardWidthPx = 1080
    const val cardHeightPx = 1920
    const val safeTopPx = 96
    const val safeBottomPx = 88
    const val sideMarginPx = 56
    const val imageSizePx = cardWidthPx - sideMarginPx * 2
    const val textTopGapPx = 28
}

data class ExhibitionStoryShareContent(
    val title: String,
    val venue: String,
    val dateRange: String,
    val coverImageUrl: String?,
) {
    companion object {
        fun from(exhibition: Exhibition, lang: AppLanguage): ExhibitionStoryShareContent =
            ExhibitionStoryShareContent(
                title = exhibition.localizedName(lang),
                venue = exhibition.localizedVenueName(lang).uppercase(),
                dateRange = exhibition.localizedDateRange(lang),
                coverImageUrl = exhibition.coverImageUrl?.takeIf { it.isNotBlank() },
            )
    }
}
