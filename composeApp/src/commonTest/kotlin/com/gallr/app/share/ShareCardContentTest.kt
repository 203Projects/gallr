package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val content =
            ExhibitionStoryShareContent.from(
                exhibition(nameEn = "", venueNameEn = ""),
                AppLanguage.EN,
            )

        assertEquals("전시 제목", content.title)
        assertEquals("갤러리", content.venue)
        assertEquals("Jun 1 – Jun 30, 2026", content.dateRange)
    }

    @Test
    fun `story card dimensions match Instagram story format`() {
        assertEquals(1080, ExhibitionStoryShareConfig.CARD_WIDTH_PX)
        assertEquals(1920, ExhibitionStoryShareConfig.CARD_HEIGHT_PX)
        assertEquals(56, ExhibitionStoryShareConfig.SIDE_MARGIN_PX)
        assertEquals(96, ExhibitionStoryShareConfig.SAFE_TOP_PX)
        assertEquals(88, ExhibitionStoryShareConfig.SAFE_BOTTOM_PX)
    }

    @Test
    fun `share descriptor names localized exhibition image`() {
        assertEquals("\"전시 제목\" 이미지", ExhibitionStoryShareContent.from(exhibition(), AppLanguage.KO).shareDescriptor)
        assertEquals(
            "\"Show Title\" image",
            ExhibitionStoryShareContent.from(exhibition(), AppLanguage.EN).shareDescriptor,
        )
    }

    @Test
    fun `brand group layout centers mark gap and wordmark as one unit`() {
        val startX =
            brandGroupStartX(
                cardWidth = 1080,
                markSize = 40f,
                gap = 16f,
                textWidth = 84f,
            )
        val groupWidth = 40f + 16f + 84f

        assertEquals(470f, startX)
        assertEquals(1080f - startX, startX + groupWidth)
    }

    @Test
    fun `Korean title without spaces wraps and ellipsizes by measured width`() {
        val lines =
            wrapMeasuredText(
                text = "국립현대미술관서울관특별기획전시",
                maxWidth = 7f,
                maxLines = 2,
                measureWidth = { it.length.toFloat() },
            )

        assertEquals(listOf("국립현대미술관", "서울관특별기…"), lines)
        assertTrue(lines.all { it.length <= 7 })
    }

    @Test
    fun `title wrapping prefers a word boundary when one fits`() {
        val lines =
            wrapMeasuredText(
                text = "A thoughtful exhibition title",
                maxWidth = 13f,
                maxLines = 2,
                measureWidth = { it.length.toFloat() },
            )

        assertEquals(listOf("A thoughtful", "exhibition t…"), lines)
    }

    @Test
    fun `short text is preserved without an ellipsis`() {
        assertEquals(
            listOf("전시 제목"),
            wrapMeasuredText(
                text = "전시 제목",
                maxWidth = 20f,
                maxLines = 2,
                measureWidth = { it.length.toFloat() },
            ),
        )
        assertEquals(
            "GALLERY",
            ellipsizeMeasuredText(
                text = "GALLERY",
                maxWidth = 20f,
                measureWidth = { it.length.toFloat() },
            ),
        )
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
