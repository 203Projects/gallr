package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareCardContentTest {

    @Test
    fun `exhibition story content localizes Korean text`() {
        val content = ExhibitionStoryShareContent.from(exhibition(), AppLanguage.KO)

        assertEquals("전시 제목", content.title)
        assertEquals("갤러리", content.venue)
        assertEquals("2026.06.01 – 2026.06.30", content.dateRange)
    }

    @Test
    fun `exhibition story content falls back to Korean when English is blank`() {
        val content = ExhibitionStoryShareContent.from(
            exhibition(nameEn = "", venueNameEn = ""),
            AppLanguage.EN,
        )

        assertEquals("전시 제목", content.title)
        assertEquals("갤러리", content.venue)
        assertEquals("Jun 1 – Jun 30, 2026", content.dateRange)
    }

    @Test
    fun `story card dimensions match Instagram story format`() {
        assertEquals(1080, ExhibitionStoryShareConfig.cardWidthPx)
        assertEquals(1920, ExhibitionStoryShareConfig.cardHeightPx)
        assertEquals(56, ExhibitionStoryShareConfig.sideMarginPx)
        assertEquals(96, ExhibitionStoryShareConfig.safeTopPx)
        assertEquals(88, ExhibitionStoryShareConfig.safeBottomPx)
    }

    @Test
    fun `share descriptor names localized exhibition image`() {
        assertEquals("\"전시 제목\" 이미지", ExhibitionStoryShareContent.from(exhibition(), AppLanguage.KO).shareDescriptor)
        assertEquals("\"Show Title\" image", ExhibitionStoryShareContent.from(exhibition(), AppLanguage.EN).shareDescriptor)
    }

    @Test
    fun `preview action labels are localized`() {
        assertEquals("미리보기", ExhibitionStorySharePreviewText.title(AppLanguage.KO))
        assertEquals("취소", ExhibitionStorySharePreviewText.cancel(AppLanguage.KO))
        assertEquals("공유", ExhibitionStorySharePreviewText.share(AppLanguage.KO))
        assertEquals("보내기", ExhibitionStorySharePreviewText.send(AppLanguage.KO))
        assertEquals("Preview", ExhibitionStorySharePreviewText.title(AppLanguage.EN))
        assertEquals("Cancel", ExhibitionStorySharePreviewText.cancel(AppLanguage.EN))
        assertEquals("Share", ExhibitionStorySharePreviewText.share(AppLanguage.EN))
        assertEquals("Send", ExhibitionStorySharePreviewText.send(AppLanguage.EN))
    }

    private fun exhibition(
        nameEn: String = "Show Title",
        venueNameEn: String = "Gallery",
    ) = Exhibition(
        id = "ex1",
        nameKo = "전시 제목",
        nameEn = nameEn,
        venueNameKo = "갤러리",
        venueNameEn = venueNameEn,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로",
        regionEn = "Jongno",
        openingDate = LocalDate(2026, 6, 1),
        closingDate = LocalDate(2026, 6, 30),
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = "https://example.com/cover.jpg",
    )
}
