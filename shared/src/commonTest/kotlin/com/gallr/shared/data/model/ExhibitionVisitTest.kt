package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ExhibitionVisitTest {
    @Test
    fun `snapshot preserves exhibition display fields`() {
        val exhibition = exhibition("ex-1")

        val snapshot = ExhibitionVisitSnapshot.from(exhibition)

        assertEquals("전시 ex-1", snapshot.nameKo)
        assertEquals("Exhibition ex-1", snapshot.nameEn)
        assertEquals("갤러리", snapshot.venueNameKo)
        assertEquals("Gallery", snapshot.venueNameEn)
        assertEquals(exhibition.openingDate, snapshot.openingDate)
        assertEquals(exhibition.closingDate, snapshot.closingDate)
        assertEquals("https://example.com/ex-1.jpg", snapshot.coverImageUrl)
    }

    @Test
    fun `snapshot uses bilingual fallback helpers`() {
        val snapshot =
            ExhibitionVisitSnapshot.from(
                exhibition("fallback").copy(nameEn = "", venueNameEn = ""),
            )

        assertEquals("전시 fallback", snapshot.localizedName(AppLanguage.EN))
        assertEquals("갤러리", snapshot.localizedVenueName(AppLanguage.EN))
    }

    @Test
    fun `visit rejects blank identifiers`() {
        val snapshot = ExhibitionVisitSnapshot.from(exhibition("ex-1"))

        assertFailsWith<IllegalArgumentException> {
            ExhibitionVisit(
                clientRecordId = " ",
                exhibitionId = "ex-1",
                snapshot = snapshot,
                createdAt = Instant.parse("2026-08-13T00:00:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExhibitionVisit(
                clientRecordId = "record-1",
                exhibitionId = " ",
                snapshot = snapshot,
                createdAt = Instant.parse("2026-08-13T00:00:00Z"),
            )
        }
    }

    private fun exhibition(id: String) =
        Exhibition(
            id = id,
            nameKo = "전시 $id",
            nameEn = "Exhibition $id",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 8, 1),
            closingDate = LocalDate(2026, 8, 31),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = "https://example.com/$id.jpg",
        )
}
