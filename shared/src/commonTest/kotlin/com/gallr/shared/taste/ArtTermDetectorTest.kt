package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtTermDetectorTest {
    @Test
    fun `korean and english synonyms map to the catalogue taxonomy`() {
        val detected =
            detectArtTerms(
                nameKo = "손끝에서",
                nameEn = "At the Fingertips",
                descriptionKo = "대형 캔버스에 그린 회화 연작과 드로잉을 선보인다. 기억과 풍경을 다룬다.",
                descriptionEn = "",
            )

        assertEquals(
            listOf("medium:painting", "medium:drawing", "theme:memory", "theme:nature", "mood:monumental"),
            detected.map(ArtTerm::id),
        )
        assertEquals(listOf("회화", "드로잉", "기억", "자연", "기념비적"), detected.map(ArtTerm::nameKo))

        val english =
            detectArtTerms(
                nameKo = "",
                nameEn = "Quiet Rooms",
                descriptionKo = "",
                descriptionEn = "Site-specific installations and single-channel video explore identity.",
            )
        assertEquals(
            listOf("medium:installation", "medium:video", "theme:identity", "mood:quiet-meditative"),
            english.map(ArtTerm::id),
        )
    }

    @Test
    fun `english matches whole words only and matching ignores case`() {
        val none = detectArtTerms("", "", "", "The display replays a replayed scene.")
        assertEquals(emptyList(), none.map(ArtTerm::id))

        val upper = detectArtTerms("", "", "", "PAINTINGS AND SCULPTURES.")
        assertEquals(listOf("medium:painting", "medium:sculpture"), upper.map(ArtTerm::id))
    }

    @Test
    fun `editor terms come first, are never repeated, and each category stays within its cap`() {
        val editorTerm = ArtTerm("mood:playful", ArtTermCategory.MOOD, "유희적", "Playful")
        val exhibition =
            exhibition(
                descriptionKo =
                    "회화, 조각, 사진전, 설치 작품, 비디오, 디지털, 퍼포먼스, 드로잉, 판화, 공예가 유희적으로 뒤섞인다.",
                artTerms = listOf(editorTerm, ArtTerm("medium:craft", ArtTermCategory.MEDIUM, "공예", "Craft")),
            )

        val terms = exhibition.effectiveArtTerms()

        assertEquals(listOf("mood:playful", "medium:craft"), terms.take(2).map(ArtTerm::id))
        assertEquals(terms.size, terms.distinctBy(ArtTerm::id).size)
        assertTrue(terms.count { it.category == ArtTermCategory.MEDIUM } <= 6, terms.toString())
        assertEquals(1, terms.count { it.category == ArtTermCategory.MOOD })
    }

    @Test
    fun `blank text yields nothing`() {
        assertEquals(emptyList(), detectArtTerms("", "", null, null))
        assertEquals(emptyList(), exhibition(descriptionKo = "").effectiveArtTerms())
    }

    private fun exhibition(
        descriptionKo: String,
        artTerms: List<ArtTerm> = emptyList(),
    ) = Exhibition(
        id = "one",
        nameKo = "전시",
        nameEn = "Exhibition",
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
        artTerms = artTerms,
    )
}
