package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExhibitionCurationBadgeTest {
    @Test
    fun `featured and house editor badges are stable and localized`() {
        val badges = exhibition(isFeatured = true, editorId = "gallr-editors").curationBadges()

        assertEquals(
            listOf(ExhibitionCurationBadge.FEATURED, ExhibitionCurationBadge.EDITORS_PICK),
            badges,
        )
        assertEquals(listOf("FEATURED", "EDITOR'S PICK"), badges.map { it.label(AppLanguage.EN) })
        assertEquals(listOf("추천", "에디터 추천"), badges.map { it.label(AppLanguage.KO) })
    }

    @Test
    fun `guest editor identity does not receive the house editor badge`() {
        assertTrue(exhibition(isFeatured = false, editorId = "guest-curator").curationBadges().isEmpty())
    }

    @Test
    fun `each independent state produces only its matching badge`() {
        assertEquals(
            listOf(ExhibitionCurationBadge.FEATURED),
            exhibition(isFeatured = true, editorId = null).curationBadges(),
        )
        assertEquals(
            listOf(ExhibitionCurationBadge.EDITORS_PICK),
            exhibition(isFeatured = false, editorId = "gallr-editors").curationBadges(),
        )
    }

    private fun exhibition(
        isFeatured: Boolean,
        editorId: String?,
    ) = Exhibition(
        id = "exhibition-one",
        nameKo = "전시",
        nameEn = "Exhibition",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = isFeatured,
        latitude = 37.5665,
        longitude = 126.9780,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
    )
}
