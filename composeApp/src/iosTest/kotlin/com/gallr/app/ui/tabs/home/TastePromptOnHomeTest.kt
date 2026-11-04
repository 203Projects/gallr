package com.gallr.app.ui.tabs.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.viewmodel.HomeUiState
import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.app.viewmodel.TastePickMode
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeFeed
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 101 FR-004: the 내 취향 prompt on Home opens the taste sheet in the mode its wording promises. */
@OptIn(ExperimentalTestApi::class)
class TastePromptOnHomeTest {
    private val editorial =
        ExhibitionRecommendation(exhibition("editorial"), 4_000, listOf(RecommendationEvidence.Featured))

    // Value: protects=both Home prompts open the sheet in edit mode (save any selection, no skip) per the contract;
    // fails_when=HomeScreen opens a first-run sheet from the prompt or drops the onOpenTastePick call;
    // why_new=only the copy and the section kind are unit tested and the mode choice is inline in HomeScreen;
    // seam=none; no_claim=the sheet itself or what App.kt does with the mode
    @Test
    fun bothPromptsOpenTheSheetToSaveAnySelection() =
        runComposeUiTest {
            var recommendations by mutableStateOf<RecommendationUiState>(
                RecommendationUiState.Ready(1, listOf(editorial), RecommendationBasis(0, 0, 0)),
            )
            val opened = mutableListOf<TastePickMode>()
            setContent {
                HomeScreen(
                    state = HomeUiState.Ready(HomeFeed.EMPTY.copy(hero = listOf(exhibition("hero"))), null),
                    recommendations = recommendations,
                    activeEvents = emptyList(),
                    lang = AppLanguage.KO,
                    bookmarkedIds = emptySet(),
                    isRefreshing = false,
                    onRefresh = {},
                    onRetry = {},
                    onExhibitionTap = { _, _ -> },
                    onBookmarkToggle = {},
                    onImpressions = { _, _ -> },
                    onEventTap = {},
                    onForYouAll = {},
                    onCollectionTap = {},
                    onRouteTap = {},
                    onSeeAllRoutes = {},
                    onOpenTastePick = { opened += it },
                )
            }

            scrollHomeTo("취향 고르기")
            onNodeWithText("취향 고르기").performClick()
            assertEquals(listOf(TastePickMode.EDIT), opened)

            recommendations =
                RecommendationUiState.Ready(2, listOf(editorial), RecommendationBasis(0, 0, 0, pickedCount = 3))
            waitForIdle()
            scrollHomeTo("취향 바꾸기")
            onNodeWithText("취향 바꾸기").performClick()
            assertEquals(listOf(TastePickMode.EDIT, TastePickMode.EDIT), opened)
        }

    // The page is a lazy list: the prompt is composed only once the list scrolls to it.
    private fun ComposeUiTest.scrollHomeTo(text: String) {
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
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
