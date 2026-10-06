package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationBasis
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
    fun `copy is bilingual`() {
        assertEquals("내 취향 추천", recommendationScreenCopy(AppLanguage.KO).title)
        assertEquals("FOR YOU", recommendationScreenCopy(AppLanguage.EN).title)
    }

    @Test
    fun `basis line names the history counts and says computation stays on device`() {
        val basis = RecommendationBasis(savedCount = 2, visitedCount = 1, followedCount = 0)

        assertEquals("저장 2 · 방문 1 · 팔로우 0 기반 · 이 기기에서만 계산", recommendationBasisLabel(basis, AppLanguage.KO))
        assertEquals(
            "BASED ON 2 SAVED · 1 VISITED · 0 FOLLOWED · COMPUTED ON THIS DEVICE",
            recommendationBasisLabel(basis, AppLanguage.EN),
        )
    }

    @Test
    fun `cold start basis line nudges toward saving or logging a visit and still states the device`() {
        val none = RecommendationBasis(savedCount = 0, visitedCount = 0, followedCount = 0)

        assertEquals(
            "전시를 저장하거나 방문을 기록하면 취향에 맞춰 추천해 드려요. 이 기기에서만 계산합니다.",
            recommendationBasisLabel(none, AppLanguage.KO),
        )
        assertEquals(
            "Save an exhibition or log a visit and recommendations follow your taste. Computed on this device.",
            recommendationBasisLabel(none, AppLanguage.EN),
        )
    }

    @Test
    fun `cards are grouped into personal and editorial sections keeping their ranks`() {
        val anchor = exhibition("saved").let(RecommendationEvidenceAnchor::from)
        val personal =
            ExhibitionRecommendation(
                exhibition = exhibition("personal"),
                scoreBasisPoints = 9_000,
                evidence = listOf(RecommendationEvidence.TextSimilarity(RecommendationSignalSource.SAVED, anchor)),
            )
        val followed =
            ExhibitionRecommendation(
                exhibition = exhibition("followed"),
                scoreBasisPoints = 8_000,
                evidence = listOf(RecommendationEvidence.FollowedGallery, RecommendationEvidence.Featured),
            )
        val editorial =
            ExhibitionRecommendation(
                exhibition = exhibition("editorial"),
                scoreBasisPoints = 7_000,
                evidence = listOf(RecommendationEvidence.Featured, RecommendationEvidence.ClosingSoon),
            )

        val sections = recommendationSections(listOf(personal, editorial, followed), AppLanguage.KO)

        assertEquals(
            listOf(RecommendationSection.PERSONAL, RecommendationSection.EDITORIAL),
            sections.map { it.section },
        )
        assertEquals(listOf("내 취향 기반", "이번 주 볼 만한 전시"), sections.map { it.title })
        assertEquals(listOf("personal", "followed"), sections[0].cards.map { it.exhibition.id })
        assertEquals(listOf(0, 2), sections[0].cards.map { it.rank })
        assertEquals(listOf("editorial"), sections[1].cards.map { it.exhibition.id })
        assertEquals(listOf(1), sections[1].cards.map { it.rank })
        // Only the top-ranked card overall is the hero, wherever its section sits.
        assertEquals(listOf(true, false), sections[0].cards.map { it.isHero })
        assertEquals(listOf(false), sections[1].cards.map { it.isHero })
        assertEquals("저장한 “전시 saved”와 비슷한 전시", sections[0].cards[0].reason)
        // Featured is implied by the editorial section, so only the specific part of the reason remains.
        assertEquals("곧 종료", sections[1].cards[0].reason)

        val featuredOnly =
            ExhibitionRecommendation(
                exhibition = exhibition("featured-only"),
                scoreBasisPoints = 6_000,
                evidence = listOf(RecommendationEvidence.Featured),
            )
        val editorialOnly = recommendationSections(listOf(editorial, featuredOnly), AppLanguage.EN)
        assertEquals(listOf("WORTH SEEING THIS WEEK"), editorialOnly.map { it.title })
        assertEquals(listOf("CLOSING SOON", ""), editorialOnly.single().cards.map { it.reason })
        assertEquals(listOf(true, false), editorialOnly.single().cards.map { it.isHero })
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
        assertEquals((0 until 6).toList(), presented.map { it.rank })
        assertEquals(
            "BECAUSE YOU SAVED “Exhibition saved” · SAME ARTIST: KIMSOOJA · " +
                "BECAUSE YOU SAVED “Exhibition saved” · SHARED MOOD: QUIET · MEDITATIVE",
            presented.first().reason,
        )
        assertEquals(
            RecommendationCardPresentation(
                exhibition = recommendations.first().exhibition,
                rank = 0,
                reason = presented.first().reason,
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
    fun `saved evidence names the visitor's own save without an inferred reason`() {
        assertEquals("저장한 전시", localizedRecommendationEvidence(RecommendationEvidence.Saved, AppLanguage.KO))
        assertEquals("SAVED", localizedRecommendationEvidence(RecommendationEvidence.Saved, AppLanguage.EN))
        assertEquals(
            "WHY THIS · SAVED · NEARBY",
            recommendationContextLabel(
                evidence = listOf(RecommendationEvidence.Saved, RecommendationEvidence.Nearby),
                language = AppLanguage.EN,
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
