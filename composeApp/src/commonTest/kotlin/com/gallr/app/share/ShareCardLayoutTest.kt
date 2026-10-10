package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.gallr.app.share.ExhibitionStoryShareConfig as Config

class ShareCardLayoutTest {
    private val today = LocalDate(2026, 9, 23)

    @Test
    fun `content carries status detail region and web url`() {
        val content = ExhibitionStoryShareContent.from(exhibition(), AppLanguage.KO, today)

        assertEquals("갤러리  ·  종로", content.venueLine)
        assertEquals(ShareCardStatus("지금 진행 중 · D-7 마감", emphasized = true), content.status)
        assertEquals("화–일 10am – 6pm", content.detailLine)
        assertEquals("https://gallrmap.com/exhibitions/show-title-ex12/", content.webUrl)
        assertEquals("스캔해서 전시 정보·지도 보기", content.qrCaption)
    }

    @Test
    fun `english venue line is uppercase and caption is english`() {
        val content = ExhibitionStoryShareContent.from(exhibition(), AppLanguage.EN, today)

        assertEquals("GALLERY  ·  JONGNO", content.venueLine)
        assertEquals("Scan for details and map", content.qrCaption)
    }

    @Test
    fun `venue line omits a blank region`() {
        val content = ExhibitionStoryShareContent.from(exhibition(regionKo = ""), AppLanguage.KO, today)

        assertEquals("갤러리", content.venueLine)
    }

    @Test
    fun `text block clears the qr and the qr sits in the bottom right safe area`() {
        assertTrue(Config.DETAIL_TOP_PX + Config.DETAIL_HEIGHT_PX <= Config.QR_TOP_PX - 24)
        assertEquals(Config.CARD_HEIGHT_PX - Config.SAFE_BOTTOM_PX, Config.QR_TOP_PX + Config.QR_MAX_PX)
        assertTrue(Config.STATUS_TOP_PX + Config.STATUS_HEIGHT_PX < Config.IMAGE_TOP_PX)
        assertTrue(Config.BRAND_TOP_PX >= Config.QR_TOP_PX)
    }

    @Test
    fun `qr modules snap to whole pixels no smaller than three`() {
        assertEquals(8, qrModulePx(29)) // version 1 + quiet zone
        assertEquals(3, qrModulePx(65))
        assertEquals(3, qrModulePx(101)) // very long Korean slug still stays scannable
    }

    private fun exhibition(regionKo: String = "종로") =
        Exhibition(
            id = "ex123",
            nameKo = "전시 제목",
            nameEn = "Show Title",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = regionKo,
            regionEn = "Jongno",
            openingDate = LocalDate(2026, 9, 1),
            closingDate = LocalDate(2026, 9, 30),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
            hours = "10am - 6pm\nTuesday - Sunday",
            receptionDate = LocalDate(2026, 8, 31),
        )
}
