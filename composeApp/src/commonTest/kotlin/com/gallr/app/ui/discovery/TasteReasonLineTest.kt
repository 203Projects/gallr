package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 101 FR-011: an exhibition opened from 내 취향 shows the card's reason under its title. */
class TasteReasonLineTest {
    private val minimal = ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")
    private val picked =
        ExhibitionRecommendation(
            exhibition("a"),
            5_000,
            listOf(RecommendationEvidence.ChosenTerm(minimal), RecommendationEvidence.Featured),
        )
    private val ready = RecommendationUiState.Ready(1, listOf(picked), RecommendationBasis(0, 0, 0, pickedCount = 3))

    @Test
    fun `the reasons of the card that was tapped are carried to the page`() {
        assertEquals(picked.evidence, tasteReasonsFor(ready, "a"))
    }

    @Test
    fun `an exhibition that is not in the list or a list not ready carries nothing`() {
        assertEquals(emptyList(), tasteReasonsFor(ready, "elsewhere"))
        assertEquals(emptyList(), tasteReasonsFor(RecommendationUiState.Loading, "a"))
    }

    @Test
    fun `the line drops the implied Featured reason and reads like the card`() {
        assertEquals("내가 고른 스타일: 미니멀", detailTasteReasonLine(picked.evidence, AppLanguage.KO))
        assertEquals("YOUR PICK · STYLE: MINIMALIST", detailTasteReasonLine(picked.evidence, AppLanguage.EN))
    }

    @Test
    fun `no reasons or Featured alone show no line`() {
        assertNull(detailTasteReasonLine(emptyList(), AppLanguage.KO))
        assertNull(detailTasteReasonLine(listOf(RecommendationEvidence.Featured), AppLanguage.KO))
    }

    @Test
    fun `an action from a card menu is attributed to the card's rank`() {
        assertEquals(0, recommendationRank(ready, "a"))
        assertEquals(0, recommendationRank(ready, "elsewhere"))
    }

    private fun exhibition(id: String) =
        Exhibition(
            id = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 8, 1),
            closingDate = LocalDate(2026, 9, 30),
            isFeatured = true,
            latitude = 37.57,
            longitude = 126.98,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
