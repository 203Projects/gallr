package com.gallr.shared.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FollowedGalleryTest {
    @Test
    fun `gallery key normalizes whitespace and case in both names`() {
        assertEquals(
            galleryKey(nameKo = "  국제  갤러리 ", nameEn = "KUKJE   GALLERY"),
            galleryKey(nameKo = "국제 갤러리", nameEn = "kukje gallery"),
        )
    }

    @Test
    fun `gallery snapshot localizes with Korean fallback`() {
        val snapshot =
            FollowedGallerySnapshot(
                nameKo = "국제갤러리",
                nameEn = "",
                cityKo = "서울",
                cityEn = "",
                regionKo = "종로구",
                regionEn = "",
            )

        assertEquals("국제갤러리", snapshot.localizedName(AppLanguage.EN))
        assertEquals("서울 · 종로구", snapshot.localizedLocation(AppLanguage.EN))
    }
}
