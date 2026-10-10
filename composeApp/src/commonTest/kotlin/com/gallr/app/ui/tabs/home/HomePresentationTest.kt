package com.gallr.app.ui.tabs.home

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import com.gallr.shared.home.HomeCollectionKind
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomePresentationTest {
    @Test
    fun `the greeting names a signed-in visitor and otherwise the city`() {
        assertEquals("하신 님, 이번 주 볼 만한 전시", homeGreeting("하신", AppLanguage.KO))
        assertEquals("This week for Hanshin", homeGreeting("Hanshin", AppLanguage.EN))
        assertEquals("이번 주 서울의 전시", homeGreeting(null, AppLanguage.KO))
        assertEquals("This week in Seoul", homeGreeting(null, AppLanguage.EN))
    }

    @Test
    fun `the date line reads the weekday in each language`() {
        val saturday = LocalDate(2026, 10, 10)

        assertEquals("10월 10일 토요일", homeDateLine(saturday, AppLanguage.KO))
        assertEquals("SATURDAY, OCTOBER 10", homeDateLine(saturday, AppLanguage.EN))
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
    fun `the For You subtitle states the basis or nudges on a cold start and hides while nothing is ready`() {
        val pick = ExhibitionRecommendation(exhibition("a"), 5_000, listOf(RecommendationEvidence.Featured))
        val personal = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(3, 2, 1))
        val cold = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(0, 0, 0))

        assertEquals("저장 3 · 방문 2 · 팔로우 1 기반", forYouSubtitle(personal, AppLanguage.KO))
        assertEquals("BASED ON 3 SAVED · 2 VISITED · 1 FOLLOWED", forYouSubtitle(personal, AppLanguage.EN))
        assertEquals("전시를 저장하면 취향에 맞춰 골라드려요", forYouSubtitle(cold, AppLanguage.KO))
        assertEquals("Save exhibitions to tune these picks", forYouSubtitle(cold, AppLanguage.EN))
        assertNull(forYouSubtitle(RecommendationUiState.Loading, AppLanguage.KO))
        assertNull(
            forYouSubtitle(RecommendationUiState.Ready(1, emptyList(), RecommendationBasis(0, 0, 0)), AppLanguage.KO),
        )
    }

    @Test
    fun `the For You rail drops the implied Featured reason and skips the hero on a cold start`() {
        val heroPick = ExhibitionRecommendation(exhibition("hero"), 5_000, listOf(RecommendationEvidence.Featured))
        val editorial =
            ExhibitionRecommendation(
                exhibition("editorial"),
                4_000,
                listOf(RecommendationEvidence.Featured, RecommendationEvidence.EditorCurated),
            )
        val closing = ExhibitionRecommendation(exhibition("closing"), 3_000, listOf(RecommendationEvidence.ClosingSoon))
        val cold = RecommendationUiState.Ready(1, listOf(heroPick, editorial, closing), RecommendationBasis(0, 0, 0))
        val personal =
            RecommendationUiState.Ready(
                1,
                listOf(heroPick, editorial, closing),
                RecommendationBasis(2, 0, 0),
            )

        val coldCards = forYouRailCards(cold, shownElsewhere = setOf("hero"), language = AppLanguage.KO)
        val personalCards = forYouRailCards(personal, shownElsewhere = setOf("hero"), language = AppLanguage.KO)

        assertEquals(listOf("editorial", "closing"), coldCards.map { it.exhibition.id }, "the hero's pick is left out")
        assertEquals(listOf("에디터 큐레이션", "곧 종료"), coldCards.map { it.eyebrow }, "Featured alone says nothing here")
        assertEquals(listOf("hero", "editorial", "closing"), personalCards.map { it.exhibition.id })
        assertNull(personalCards.first().eyebrow)
        assertEquals(emptyList(), forYouRailCards(RecommendationUiState.Loading, emptySet(), AppLanguage.KO))
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
