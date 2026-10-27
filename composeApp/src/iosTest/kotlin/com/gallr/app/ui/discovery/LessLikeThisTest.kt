package com.gallr.app.ui.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
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

/** Spec 101 US3: a 내 취향 card can be hidden from its menu, and the snackbar undoes it. */
@OptIn(ExperimentalTestApi::class)
class LessLikeThisTest {
    private val minimal = ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")
    private val personal =
        ExhibitionRecommendation(exhibition("picked"), 5_000, listOf(RecommendationEvidence.ChosenTerm(minimal)))
    private val editorial =
        ExhibitionRecommendation(exhibition("featured"), 1_000, listOf(RecommendationEvidence.Featured))
    private val state =
        RecommendationUiState.Ready(1, listOf(personal, editorial), RecommendationBasis(0, 0, 0, pickedCount = 1))

    @Test
    fun hidingFromTheMenuShowsTheSnackbarAndUndoBringsTheCardBack() =
        runComposeUiTest {
            var hidden by mutableStateOf(emptySet<String>())
            setContent {
                RecommendationsScreen(
                    state = state,
                    lang = AppLanguage.KO,
                    bookmarkedIds = emptySet(),
                    onBookmarkToggle = {},
                    onExhibitionTap = { _, _ -> },
                    onImpressions = {},
                    onBack = {},
                    onRetry = {},
                    hiddenIds = hidden,
                    onLessLikeThis = { hidden = hidden + it.id },
                    onUndoLessLikeThis = { hidden = hidden - it },
                )
            }

            // Only the personal card carries the menu; editorial picks do not.
            assertEquals(1, onAllNodesWithContentDescription("추천 메뉴").fetchSemanticsNodes().size)

            onNodeWithContentDescription("추천 메뉴").performScrollTo().performClick()
            onNodeWithText("덜 보기").performClick()
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(SNACKBAR_SETTLE_MILLIS)

            assertEquals(setOf("picked"), hidden)
            onNodeWithText("숨겼어요 · 종료일까지").assertExists()

            onNodeWithText("되돌리기").performClick()
            mainClock.advanceTimeBy(SNACKBAR_SETTLE_MILLIS)

            assertEquals(emptySet(), hidden)
        }

    @Test
    fun aHiddenCardTellsAScreenReaderItIsHiddenAndOffersItsUndo() =
        runComposeUiTest {
            val undone = mutableListOf<String>()
            setContent {
                RecommendationsScreen(
                    state = state,
                    lang = AppLanguage.KO,
                    bookmarkedIds = emptySet(),
                    onBookmarkToggle = {},
                    onExhibitionTap = { _, _ -> },
                    onImpressions = {},
                    onBack = {},
                    onRetry = {},
                    hiddenIds = setOf("picked"),
                    onUndoLessLikeThis = { undone += it },
                )
            }

            val hiddenState = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "숨김 · 종료일까지")
            assertEquals(1, onAllNodes(hiddenState, useUnmergedTree = true).fetchSemanticsNodes().size)

            val hiddenCard = onNode(hiddenState, useUnmergedTree = true).fetchSemanticsNode()
            val actions = hiddenCard.config[SemanticsActions.CustomActions]
            runOnIdle { actions.single { it.label == "되돌리기" }.action() }

            assertEquals(listOf("picked"), undone)
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

    private companion object {
        const val SNACKBAR_SETTLE_MILLIS = 500L
    }
}
