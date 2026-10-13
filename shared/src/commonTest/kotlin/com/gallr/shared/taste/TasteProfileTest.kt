package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TasteProfileTest {
    @Test
    fun `terms that recur across saved and visited exhibitions rank first`() {
        val anchors =
            listOf(
                exhibition("a", "추상 회화와 기억에 대한 고요한 전시"),
                exhibition("b", "추상 조각과 기억"),
                exhibition("c", "회화 연작"),
            )

        val terms = tasteTerms(anchors, limit = 4)

        // 회화, 추상 and 기억 each appear twice; the single mentions follow in category order.
        assertEquals(
            listOf("medium:painting", "style:abstract", "theme:memory", "medium:sculpture"),
            terms.map(ArtTerm::id),
        )
    }

    @Test
    fun `no category takes more than two of the first four and the total limit holds`() {
        val anchors =
            listOf(
                exhibition("a", "회화 드로잉 조각 사진전 판화 추상"),
                exhibition("b", "회화 드로잉 조각 사진전 판화"),
            )

        val terms = tasteTerms(anchors, limit = 4)

        // Two media lead by vocabulary order, the lone style breaks the medium run, then the next medium fills.
        assertEquals(
            listOf("medium:painting", "medium:sculpture", "style:abstract", "medium:photography"),
            terms.map(ArtTerm::id),
        )
        assertEquals(4, terms.size)
    }

    @Test
    fun `no history or no detectable text yields no tags`() {
        assertEquals(emptyList(), tasteTerms(emptyList(), limit = 4))
        assertEquals(emptyList(), tasteTerms(listOf(exhibition("a", "")), limit = 4))
    }

    private fun exhibition(
        id: String,
        descriptionKo: String,
    ) = Exhibition(
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
        closingDate = LocalDate(2026, 9, 30),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = descriptionKo,
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
