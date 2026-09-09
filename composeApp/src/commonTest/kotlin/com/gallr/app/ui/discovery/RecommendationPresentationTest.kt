package com.gallr.app.ui.discovery

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RecommendationEvidenceAnchor
import com.gallr.shared.recommendation.RecommendationSignalSource
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationPresentationTest {
    @Test
    fun `copy is bilingual and explicitly says computation stays on device`() {
        val korean = recommendationScreenCopy(AppLanguage.KO)
        val english = recommendationScreenCopy(AppLanguage.EN)

        assertEquals("내 취향 추천", korean.title)
        assertEquals("이 기기에서 계산됨", korean.deviceOnlyLabel)
        assertTrue("기기 밖으로 보내지 않고" in korean.explanation)
        assertEquals("FOR YOU", english.title)
        assertEquals("COMPUTED ON THIS DEVICE", english.deviceOnlyLabel)
        assertTrue("without sending that history off this device" in english.explanation)
    }

    @Test
    fun `loading empty error and retry copy never implies a hosted service`() {
        AppLanguage.entries.forEach { language ->
            val copy = recommendationScreenCopy(language)
            val allCopy =
                listOf(
                    copy.loading,
                    copy.emptyTitle,
                    copy.emptyBody,
                    copy.errorTitle,
                    copy.errorBody,
                    copy.retry,
                ).joinToString(" ").lowercase()

            assertFalse("server" in allCopy)
            assertFalse(Regex("\\bai\\b").containsMatchIn(allCopy))
            assertFalse("paid" in allCopy)
            assertFalse("upgrade" in allCopy)
        }
    }

    @Test
    fun `presentation caps organic cards and always exposes specific evidence without scores`() {
        val anchor = exhibition("saved").let(RecommendationEvidenceAnchor::from)
        val recommendations =
            (0 until 8).map { index ->
                ExhibitionRecommendation(
                    exhibition = exhibition("exhibition-$index"),
                    scoreBasisPoints = 9_999 - index,
                    evidence =
                        listOf(
                            RecommendationEvidence.ArtistMatch(
                                source = RecommendationSignalSource.SAVED,
                                anchor = anchor,
                                artist = ExhibitionArtist("artist-kimsooja", "김수자", "Kimsooja"),
                            ),
                            RecommendationEvidence.ArtTermMatch(
                                source = RecommendationSignalSource.SAVED,
                                anchor = anchor,
                                term =
                                    ArtTerm(
                                        id = "mood:quiet-meditative",
                                        category = ArtTermCategory.MOOD,
                                        nameKo = "고요함 · 명상적",
                                        nameEn = "Quiet · meditative",
                                    ),
                            ),
                        ),
                )
            }

        val presented = recommendationCardPresentations(recommendations, AppLanguage.EN)

        assertEquals(6, presented.size)
        assertEquals((0 until 6).map { "exhibition-$it" }, presented.map { it.exhibition.id })
        assertEquals(
            "WHY THIS · BECAUSE YOU SAVED “Exhibition saved” · SAME ARTIST: KIMSOOJA · " +
                "BECAUSE YOU SAVED “Exhibition saved” · SHARED MOOD: QUIET · MEDITATIVE",
            presented.first().contextLabel,
        )
        assertEquals(
            RecommendationCardPresentation(
                exhibition = recommendations.first().exhibition,
                contextLabel = presented.first().contextLabel,
            ),
            presented.first(),
        )
    }

    @Test
    fun `artist and tone evidence is specific deterministic and bilingual`() {
        val anchor = exhibition("saved").let(RecommendationEvidenceAnchor::from)
        val artist = ExhibitionArtist("artist-kimsooja", "김수자", "Kimsooja")
        val tone =
            ArtTerm(
                id = "mood:quiet-meditative",
                category = ArtTermCategory.MOOD,
                nameKo = "고요함 · 명상적",
                nameEn = "Quiet · meditative",
            )

        assertEquals(
            "저장한 “전시 saved”와 같은 작가: 김수자",
            localizedRecommendationEvidence(
                RecommendationEvidence.ArtistMatch(RecommendationSignalSource.SAVED, anchor, artist),
                AppLanguage.KO,
            ),
        )
        assertEquals(
            "BECAUSE YOU VISITED “Exhibition saved” · SHARED MOOD: QUIET · MEDITATIVE",
            localizedRecommendationEvidence(
                RecommendationEvidence.ArtTermMatch(RecommendationSignalSource.VISITED, anchor, tone),
                AppLanguage.EN,
            ),
        )
    }

    @Test
    fun `generic evidence remains truthful and bilingual`() {
        assertEquals(
            "WHY THIS · FROM A GALLERY YOU FOLLOW · CLOSING SOON",
            recommendationContextLabel(
                evidence = listOf(RecommendationEvidence.FollowedGallery, RecommendationEvidence.ClosingSoon),
                language = AppLanguage.EN,
            ),
        )
        assertEquals(
            "추천 이유 · 에디터 큐레이션",
            recommendationContextLabel(
                evidence = listOf(RecommendationEvidence.EditorCurated),
                language = AppLanguage.KO,
            ),
        )
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
