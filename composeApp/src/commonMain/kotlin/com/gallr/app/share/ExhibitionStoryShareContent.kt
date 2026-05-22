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
    val shareDescriptor: String,
) {
    companion object {
        fun from(exhibition: Exhibition, lang: AppLanguage): ExhibitionStoryShareContent {
            val title = exhibition.localizedName(lang)
            return ExhibitionStoryShareContent(
                title = title,
                venue = exhibition.localizedVenueName(lang).uppercase(),
                dateRange = exhibition.localizedDateRange(lang),
                coverImageUrl = exhibition.coverImageUrl?.takeIf { it.isNotBlank() },
                shareDescriptor = if (lang == AppLanguage.KO) {
                    "\"$title\" 이미지"
                } else {
                    "\"$title\" image"
                },
            )
        }
    }
}

object ExhibitionStorySharePreviewText {
    fun title(lang: AppLanguage): String = if (lang == AppLanguage.KO) "미리보기" else "Preview"
    fun cancel(lang: AppLanguage): String = if (lang == AppLanguage.KO) "취소" else "Cancel"
    fun share(lang: AppLanguage): String = if (lang == AppLanguage.KO) "공유" else "Share"
    fun send(lang: AppLanguage): String = if (lang == AppLanguage.KO) "보내기" else "Send"
    fun loading(lang: AppLanguage): String = if (lang == AppLanguage.KO) "이미지 준비 중" else "Preparing image"
}
