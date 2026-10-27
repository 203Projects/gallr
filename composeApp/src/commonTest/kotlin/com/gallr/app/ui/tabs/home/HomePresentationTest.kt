package com.gallr.app.ui.tabs.home

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.curatornote.CuratorEyeCard
import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.home.HomeCollectionKind
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RecommendationEvidenceAnchor
import com.gallr.shared.recommendation.RecommendationSignalSource
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class HomePresentationTest {
    @Test
    fun `the greeting names a signed-in visitor and otherwise the city`() {
        assertEquals("하신 님, 이번 주 볼 만한 전시", homeGreeting("하신", AppLanguage.KO))
        assertEquals("This week for Hanshin", homeGreeting("Hanshin", AppLanguage.EN))
        assertEquals("이번 주 서울의 전시", homeGreeting(null, AppLanguage.KO))
        assertEquals("This week in Seoul", homeGreeting(null, AppLanguage.EN))
    }

    @Test
    fun `the pager counter is one-based and two digits wide`() {
        assertEquals("01 / 06", pagerCounter(pageIndex = 0, pageCount = 6))
        assertEquals("06 / 06", pagerCounter(pageIndex = 5, pageCount = 6))
        assertEquals("10 / 12", pagerCounter(pageIndex = 9, pageCount = 12))
    }

    @Test
    fun `collection subtitles say what gathers the exhibitions`() {
        val closing =
            collection(HomeCollectionKind.CLOSING_THIS_WEEK, "closing-this-week", "이번 주 마감", "CLOSING THIS WEEK", 4)
        val opening =
            collection(HomeCollectionKind.OPENING_THIS_WEEK, "opening-this-week", "이번 주 개막", "OPENING THIS WEEK", 2)
        val hannam = collection(HomeCollectionKind.NEIGHBORHOOD, "region:한남동", "한남동", "Hannam-dong", 5)
        val identity = collection(HomeCollectionKind.THEME, "identity", "정체성", "Identity", 3)

        assertEquals("마지막 기회 · 4개 전시", collectionSubtitle(closing, AppLanguage.KO))
        assertEquals("LAST CHANCE · 4 EXHIBITIONS", collectionSubtitle(closing, AppLanguage.EN))
        assertEquals("새로 문을 여는 전시 2개", collectionSubtitle(opening, AppLanguage.KO))
        assertEquals("2 NEW OPENINGS", collectionSubtitle(opening, AppLanguage.EN))
        assertEquals("#한남동 · 5개 전시", collectionSubtitle(hannam, AppLanguage.KO))
        assertEquals("#HANNAM-DONG · 5 EXHIBITIONS", collectionSubtitle(hannam, AppLanguage.EN))
        assertEquals("#정체성 · 3개 전시", collectionSubtitle(identity, AppLanguage.KO))
        assertEquals("#IDENTITY · 3 EXHIBITIONS", collectionSubtitle(identity, AppLanguage.EN))
    }

    @Test
    fun `the For You subtitle states the basis with picks first and hides on a cold start or while nothing is ready`() {
        val pick = ExhibitionRecommendation(exhibition("a"), 5_000, listOf(RecommendationEvidence.Featured))
        val personal = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(3, 2, 1))
        val cold = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(0, 0, 0))

        assertEquals("저장 3 · 방문 2 · 팔로우 1 기반", forYouSubtitle(personal, AppLanguage.KO))
        val picked = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(1, 0, 0, pickedCount = 4))
        assertEquals("선택 4 · 저장 1 · 방문 0 · 팔로우 0 기반", forYouSubtitle(picked, AppLanguage.KO))
        assertEquals("BASED ON 4 PICKED · 1 SAVED · 0 VISITED · 0 FOLLOWED", forYouSubtitle(picked, AppLanguage.EN))
        assertEquals("BASED ON 3 SAVED · 2 VISITED · 1 FOLLOWED", forYouSubtitle(personal, AppLanguage.EN))
        // Spec 101: a cold visitor sees the taste prompt, so there is no subtitle to show.
        assertNull(forYouSubtitle(cold, AppLanguage.KO))
        assertNull(forYouSubtitle(RecommendationUiState.Loading, AppLanguage.KO))
        assertNull(
            forYouSubtitle(RecommendationUiState.Ready(1, emptyList(), RecommendationBasis(0, 0, 0)), AppLanguage.KO),
        )
    }

    @Test
    fun `the For You section shows only personal picks and never an editorial card under the taste heading`() {
        val anchor = RecommendationEvidenceAnchor("saved", "저장한 전시", "Saved show")
        val personal =
            ExhibitionRecommendation(
                exhibition("personal"),
                5_000,
                listOf(
                    RecommendationEvidence.TextSimilarity(RecommendationSignalSource.SAVED, anchor),
                    RecommendationEvidence.Featured,
                ),
            )
        val editorial =
            ExhibitionRecommendation(
                exhibition("editorial"),
                4_000,
                listOf(RecommendationEvidence.Featured, RecommendationEvidence.EditorCurated),
            )
        val withSaves = RecommendationUiState.Ready(1, listOf(personal, editorial), RecommendationBasis(2, 0, 0))

        val picks = assertIs<ForYouSection.Picks>(forYouSection(withSaves, AppLanguage.KO))

        assertEquals(listOf("personal"), picks.cards.map { it.exhibition.id })
        assertEquals("저장한 “저장한 전시”와 비슷한 전시", picks.cards.single().eyebrow, "Featured alone says nothing here")
    }

    @Test
    fun `with nothing to go on the section asks for picks instead of showing editorial cards`() {
        val editorial =
            ExhibitionRecommendation(exhibition("editorial"), 4_000, listOf(RecommendationEvidence.Featured))
        val cold = RecommendationUiState.Ready(1, listOf(editorial), RecommendationBasis(0, 0, 0))

        assertEquals(ForYouSection.Prompt(TastePromptKind.NO_SIGNAL), forYouSection(cold, AppLanguage.KO))
        assertEquals(
            ForYouSection.Prompt(TastePromptKind.NO_SIGNAL),
            forYouSection(RecommendationUiState.Empty, AppLanguage.KO),
        )
        assertEquals(ForYouSection.Hidden, forYouSection(RecommendationUiState.Loading, AppLanguage.KO))
        assertEquals(ForYouSection.Hidden, forYouSection(RecommendationUiState.Error, AppLanguage.KO))
    }

    @Test
    fun `picks that match nothing running ask to change them`() {
        val editorial =
            ExhibitionRecommendation(exhibition("editorial"), 4_000, listOf(RecommendationEvidence.Featured))
        val seeded = RecommendationUiState.Ready(1, listOf(editorial), RecommendationBasis(0, 0, 0, pickedCount = 3))
        val savedOnly = RecommendationUiState.Ready(1, listOf(editorial), RecommendationBasis(1, 0, 0))

        assertEquals(ForYouSection.Prompt(TastePromptKind.NOTHING_MATCHES), forYouSection(seeded, AppLanguage.KO))
        assertEquals(ForYouSection.Prompt(TastePromptKind.NO_SIGNAL), forYouSection(savedOnly, AppLanguage.KO))
    }

    @Test
    fun `the taste row appears whenever there are tags or picks to edit`() {
        val pick = ExhibitionRecommendation(exhibition("a"), 5_000, listOf(RecommendationEvidence.FollowedGallery))
        val followedOnly = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(0, 0, 1))
        val picked = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(0, 0, 0, pickedCount = 3))

        assertEquals(false, showsTasteRow(followedOnly, tagCount = 0))
        assertEquals(true, showsTasteRow(followedOnly, tagCount = 2))
        assertEquals(true, showsTasteRow(picked, tagCount = 0))
        assertEquals(false, showsTasteRow(RecommendationUiState.Loading, tagCount = 0))
    }

    @Test
    fun `prompt copy is bilingual and acknowledges existing picks`() {
        assertEquals(
            TastePromptCopy("전시를 고르거나 저장하면 취향에 맞춰 골라드려요", "취향 고르기"),
            tastePromptCopy(TastePromptKind.NO_SIGNAL, AppLanguage.KO),
        )
        assertEquals(
            TastePromptCopy("Pick or save exhibitions and the picks follow your taste", "PICK YOUR TASTE"),
            tastePromptCopy(TastePromptKind.NO_SIGNAL, AppLanguage.EN),
        )
        assertEquals(
            TastePromptCopy("선택한 취향에 맞는 전시가 아직 없어요", "취향 바꾸기"),
            tastePromptCopy(TastePromptKind.NOTHING_MATCHES, AppLanguage.KO),
        )
        assertEquals(
            TastePromptCopy("Nothing running matches your picks yet", "CHANGE PICKS"),
            tastePromptCopy(TastePromptKind.NOTHING_MATCHES, AppLanguage.EN),
        )
    }

    @Test
    fun `the curator eye section names itself in each language without review wording`() {
        assertEquals("에디터의 시선", homeCopy(AppLanguage.KO, null).curatorEyeTitle)
        assertEquals("EDITOR'S EYE", homeCopy(AppLanguage.EN, null).curatorEyeTitle)
        assertEquals("전시를 보기 전에 읽는 짧은 노트", homeCopy(AppLanguage.KO, null).curatorEyeSubtitle)
        assertEquals("Short notes to read before you go", homeCopy(AppLanguage.EN, null).curatorEyeSubtitle)
    }

    @Test
    fun `a curator eye card reads as one sentence with its position`() {
        val card =
            CuratorEyeCard(
                note =
                    CuratorNote(
                        exhibitionId = "a",
                        versionNumber = 1,
                        hookKo = "곰 한 마리가 동화 속으로 들어간 이유",
                        hookEn = "Why a bear walked into a fairy tale",
                        insightsKo = listOf("방마다 색을 바꾼다.", "둘"),
                        insightsEn = listOf("Each room changes colour.", "Two"),
                        tipKo = "팁",
                        tipEn = "Tip",
                        approvedAt = Instant.parse("2026-10-11T03:00:00Z"),
                    ),
                exhibition = exhibition("a"),
                daysToClosing = 9,
            )

        assertEquals(
            "에디터의 시선, 곰 한 마리가 동화 속으로 들어간 이유, 방마다 색을 바꾼다., 전시 a, " +
                "장소 · 2026.09.01 – 2026.11.30 · 노트 2단락, D-9, 2/6",
            curatorEyeCardDescription(card, index = 1, count = 6, language = AppLanguage.KO),
        )
        assertEquals(
            "Editor's eye, Why a bear walked into a fairy tale, Each room changes colour., Show a, " +
                "Venue · Sep 1 – Nov 30, 2026 · 2 insights, D-9, 2/6",
            curatorEyeCardDescription(card, index = 1, count = 6, language = AppLanguage.EN),
        )
    }

    @Test
    fun `the For You title addresses the visitor by name`() {
        assertEquals("하신 님의 취향", homeCopy(AppLanguage.KO, "하신").forYouTitle)
        assertEquals("내 취향 추천", homeCopy(AppLanguage.KO, null).forYouTitle)
        assertEquals("PICKED FOR HANSHIN", homeCopy(AppLanguage.EN, "HANSHIN").forYouTitle)
        assertEquals("FOR YOU", homeCopy(AppLanguage.EN, null).forYouTitle)
    }

    private fun collection(
        kind: HomeCollectionKind,
        key: String,
        titleKo: String,
        titleEn: String,
        count: Int,
    ) = HomeCollection(kind, key, titleKo, titleEn, null, (1..count).map { exhibition("$key-$it") })

    private fun exhibition(id: String) =
        Exhibition(
            id = id,
            nameKo = "전시 $id",
            nameEn = "Show $id",
            venueNameKo = "장소",
            venueNameEn = "Venue",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "",
            regionEn = "",
            openingDate = LocalDate(2026, 9, 1),
            closingDate = LocalDate(2026, 11, 30),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )
}
