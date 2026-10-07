package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RecommendationEvidenceAnchor
import com.gallr.shared.recommendation.RecommendationSignalSource
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationsEntryPresentationTest {
    @Test
    fun `entry previews the top pick and how many picks follow it`() {
        val anchor = exhibition("saved").let(RecommendationEvidenceAnchor::from)
        val top =
            ExhibitionRecommendation(
                exhibition = exhibition("top").copy(coverImageUrl = "https://cdn.example/top.jpg"),
                scoreBasisPoints = 9_000,
                evidence = listOf(RecommendationEvidence.TextSimilarity(RecommendationSignalSource.SAVED, anchor)),
            )
        val second =
            ExhibitionRecommendation(
                exhibition = exhibition("second").copy(coverImageUrl = "https://cdn.example/second.jpg"),
                scoreBasisPoints = 8_000,
                evidence = listOf(RecommendationEvidence.Featured),
            )
        val third = second.copy(exhibition = exhibition("third"), scoreBasisPoints = 7_000)
        val ready =
            RecommendationUiState.Ready(
                runId = 1,
                items = listOf(top, second, third),
                basis = RecommendationBasis(savedCount = 1, visitedCount = 0, followedCount = 0),
            )

        assertEquals(
            RecommendationsEntryPresentation(
                title = "내 취향 추천",
                teaser = "전시 top 외 2개",
                frames =
                    listOf(
                        RecommendationsEntryFrame(
                            reason = "저장한 “전시 saved”와 비슷한 전시",
                            name = "전시 top",
                            coverImageUrl = "https://cdn.example/top.jpg",
                        ),
                        RecommendationsEntryFrame(
                            reason = "추천 전시",
                            name = "전시 second",
                            coverImageUrl = "https://cdn.example/second.jpg",
                        ),
                        RecommendationsEntryFrame(reason = "추천 전시", name = "전시 third", coverImageUrl = null),
                    ),
                coverImageUrl = "https://cdn.example/top.jpg",
            ),
            recommendationsEntryPresentation(ready, AppLanguage.KO),
        )
        assertEquals(
            RecommendationsEntryPresentation(
                title = "FOR YOU",
                teaser = "Exhibition top and 2 more",
                frames =
                    listOf(
                        RecommendationsEntryFrame(
                            reason = "BECAUSE YOU SAVED “Exhibition saved” · SIMILAR EXHIBITION",
                            name = "Exhibition top",
                            coverImageUrl = "https://cdn.example/top.jpg",
                        ),
                        RecommendationsEntryFrame(
                            reason = "FEATURED",
                            name = "Exhibition second",
                            coverImageUrl = "https://cdn.example/second.jpg",
                        ),
                        RecommendationsEntryFrame(
                            reason = "FEATURED",
                            name = "Exhibition third",
                            coverImageUrl = null,
                        ),
                    ),
                coverImageUrl = "https://cdn.example/top.jpg",
            ),
            recommendationsEntryPresentation(ready, AppLanguage.EN),
        )

        val single = ready.copy(items = listOf(top))
        assertEquals("전시 top", recommendationsEntryPresentation(single, AppLanguage.KO).teaser)
        assertEquals("Exhibition top", recommendationsEntryPresentation(single, AppLanguage.EN).teaser)
        assertEquals(1, recommendationsEntryPresentation(single, AppLanguage.KO).frames.size)

        // The cycle shows at most three picks even when the list holds six.
        val many = ready.copy(items = listOf(top, second, third, third.copy(exhibition = exhibition("fourth"))))
        assertEquals(3, recommendationsEntryPresentation(many, AppLanguage.KO).frames.size)
    }

    @Test
    fun `entry nudges on a cold start and stays a plain label until picks are ready`() {
        val editorial =
            ExhibitionRecommendation(
                exhibition = exhibition("editorial").copy(coverImageUrl = "https://cdn.example/editorial.jpg"),
                scoreBasisPoints = 7_000,
                evidence = listOf(RecommendationEvidence.Featured),
            )
        val coldStart =
            RecommendationUiState.Ready(
                runId = 1,
                items = listOf(editorial),
                basis = RecommendationBasis(savedCount = 0, visitedCount = 0, followedCount = 0),
            )

        assertEquals(
            RecommendationsEntryPresentation(
                title = "내 취향 추천",
                teaser = "전시를 저장하면 취향에 맞춰 추천해요",
                frames = emptyList(),
                // A cold start still has a cover to show: the first editorial pick's.
                coverImageUrl = "https://cdn.example/editorial.jpg",
            ),
            recommendationsEntryPresentation(coldStart, AppLanguage.KO),
        )
        assertEquals(
            RecommendationsEntryPresentation(
                title = "FOR YOU",
                teaser = "Save exhibitions to tune these picks",
                frames = emptyList(),
                coverImageUrl = "https://cdn.example/editorial.jpg",
            ),
            recommendationsEntryPresentation(coldStart, AppLanguage.EN),
        )

        val pending = listOf(RecommendationUiState.Loading, RecommendationUiState.Empty, RecommendationUiState.Error)
        pending.forEach { state ->
            assertEquals(
                RecommendationsEntryPresentation(
                    title = "내 취향 추천",
                    teaser = null,
                    frames = emptyList(),
                    coverImageUrl = null,
                ),
                recommendationsEntryPresentation(state, AppLanguage.KO),
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
            closingDate = LocalDate(2026, 9, 30),
            isFeatured = false,
            latitude = 37.57,
            longitude = 126.98,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
