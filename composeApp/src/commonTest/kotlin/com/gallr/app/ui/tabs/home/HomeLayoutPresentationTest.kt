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

/** Spec 091 revision 2: what leads the page, how collections split, and the copy on the new cards. */
class HomeLayoutPresentationTest {
    @Test
    fun `picks with a personal basis lead the page and anything else leaves the featured list in front`() {
        val pick = ExhibitionRecommendation(exhibition("a"), 5_000, listOf(RecommendationEvidence.ClosingSoon))
        val personal = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(2, 0, 0))
        val cold = RecommendationUiState.Ready(1, listOf(pick), RecommendationBasis(0, 0, 0))
        val nothing = RecommendationUiState.Ready(1, emptyList(), RecommendationBasis(2, 0, 0))

        assertEquals(HomeLead.FOR_YOU, homeLead(personal))
        assertEquals(HomeLead.FEATURED, homeLead(cold))
        assertEquals(HomeLead.FEATURED, homeLead(nothing))
        assertEquals(HomeLead.FEATURED, homeLead(RecommendationUiState.Loading))
        assertEquals(HomeLead.FEATURED, homeLead(RecommendationUiState.Error))
    }

    @Test
    fun `timing and most viewed collections form the grid and places and themes form the rail`() {
        val all =
            listOf(
                collection(HomeCollectionKind.MOST_VIEWED, "most-viewed"),
                collection(HomeCollectionKind.CLOSING_THIS_WEEK, "closing"),
                collection(HomeCollectionKind.FREE_ADMISSION, "free-admission"),
                collection(HomeCollectionKind.SITUATION, "situation:quiet"),
                collection(HomeCollectionKind.NEIGHBORHOOD, "region:한남동"),
                collection(HomeCollectionKind.OPENING_THIS_WEEK, "opening"),
                collection(HomeCollectionKind.THEME, "identity"),
            )

        assertEquals(
            listOf("most-viewed", "closing", "free-admission", "opening"),
            curationTiles(all).map { it.key },
        )
        assertEquals(listOf("situation:quiet", "region:한남동", "identity"), themeTiles(all).map { it.key })
    }

    @Test
    fun `a situation tile names the signals it rests on`() {
        val quiet =
            HomeCollection(
                kind = HomeCollectionKind.SITUATION,
                key = "situation:quiet",
                titleKo = "조용히 보고 싶은 날",
                titleEn = "A QUIET VISIT",
                coverImageUrl = null,
                exhibitions = (1..5).map { exhibition("q$it") },
                tagsKo = listOf("고요함", "미니멀"),
                tagsEn = listOf("QUIET", "MINIMAL"),
            )

        assertEquals("#고요함 #미니멀 · 5개 전시", collectionSubtitle(quiet, AppLanguage.KO))
        assertEquals("#QUIET #MINIMAL · 5 EXHIBITIONS", collectionSubtitle(quiet, AppLanguage.EN))
    }

    @Test
    fun `a tile headline breaks at the word nearest its middle and short ones stay whole`() {
        assertEquals("사진 찍기\n좋은 공간", balancedHeadline("사진 찍기 좋은 공간"))
        assertEquals("둘이서\n천천히", balancedHeadline("둘이서 천천히"))
        assertEquals("SPACES TO\nPHOTOGRAPH", balancedHeadline("SPACES TO PHOTOGRAPH"))
        // One word, or short enough for one line, is left alone.
        assertEquals("용산구", balancedHeadline("용산구"))
        assertEquals("자연", balancedHeadline("자연"))
        assertEquals("한남 산책", balancedHeadline("한남 산책"))
        assertEquals("", balancedHeadline(""))
    }

    @Test
    fun `the most viewed tile says what it counts`() {
        val mostViewed = collection(HomeCollectionKind.MOST_VIEWED, "most-viewed", count = 6)

        assertEquals("이번 주 기준 · 6개 전시", collectionSubtitle(mostViewed, AppLanguage.KO))
        assertEquals("THIS WEEK · 6 EXHIBITIONS", collectionSubtitle(mostViewed, AppLanguage.EN))
    }

    @Test
    fun `the free admission tile says who confirmed it`() {
        val free = collection(HomeCollectionKind.FREE_ADMISSION, "free-admission", count = 4)

        assertEquals("에디터 확인 · 4개 전시", collectionSubtitle(free, AppLanguage.KO))
        assertEquals("EDITOR CONFIRMED · 4 EXHIBITIONS", collectionSubtitle(free, AppLanguage.EN))
    }

    @Test
    fun `the banner's last line gives the venue and the run`() {
        assertEquals("장소 · 2026.09.01 – 2026.11.30", bannerMetaLine(exhibition("a"), AppLanguage.KO))
        assertEquals("Venue · Sep 1 – Nov 30, 2026", bannerMetaLine(exhibition("a"), AppLanguage.EN))
    }

    @Test
    fun `a route's copies are stated only when there are some`() {
        assertNull(routeCopyCount(0, AppLanguage.KO))
        assertEquals("복사 24회", routeCopyCount(24, AppLanguage.KO))
        assertEquals("1 COPY", routeCopyCount(1, AppLanguage.EN))
        assertEquals("24 COPIES", routeCopyCount(24, AppLanguage.EN))
    }

    @Test
    fun `the new sections are named in both languages`() {
        val ko = homeCopy(AppLanguage.KO, null)
        val en = homeCopy(AppLanguage.EN, null)

        assertEquals("큐레이션", ko.curationTitle)
        assertEquals("상황별·주제별", ko.themesTitle)
        assertEquals("내 취향", ko.tasteLabel)
        assertEquals("CURATIONS", en.curationTitle)
        assertEquals("BY MOOD AND THEME", en.themesTitle)
        assertEquals("YOUR TASTE", en.tasteLabel)
    }

    private fun collection(
        kind: HomeCollectionKind,
        key: String,
        count: Int = 3,
    ) = HomeCollection(kind, key, "제목", "TITLE", null, (1..count).map { exhibition("$key-$it") })

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
