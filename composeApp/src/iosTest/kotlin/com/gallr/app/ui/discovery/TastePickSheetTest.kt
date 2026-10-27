package com.gallr.app.ui.discovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.viewmodel.TastePickMode
import com.gallr.app.viewmodel.TastePickSheetState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 101 US1: the taste sheet starts at three picks, can be skipped once, and speaks the app's language. */
@OptIn(ExperimentalTestApi::class)
class TastePickSheetTest {
    private val terms =
        listOf(
            ArtTerm("medium:painting", ArtTermCategory.MEDIUM, "회화", "Painting"),
            ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist"),
            ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City"),
            ArtTerm("mood:quiet-meditative", ArtTermCategory.MOOD, "고요함", "Quiet"),
        )

    private fun sheet(mode: TastePickMode = TastePickMode.FIRST_RUN) =
        TastePickSheetState(
            mode = mode,
            terms = terms,
            covers = emptyList(),
            selectedTermIds = emptySet(),
            selectedExhibitionIds = emptySet(),
        )

    @Test
    fun startStaysDisabledUntilThreePicksThenConfirms() =
        runComposeUiTest {
            var state by mutableStateOf(sheet())
            var confirmed = 0
            setContent {
                TastePickSheetContent(
                    state = state,
                    language = AppLanguage.KO,
                    onToggleTerm = { id -> state = state.copy(selectedTermIds = state.selectedTermIds + id) },
                    onToggleCover = {},
                    onConfirm = { confirmed += 1 },
                    onSkip = {},
                )
            }

            onNodeWithText("시작하기 (0)").assertIsNotEnabled()
            onNodeWithText("3개 더 골라 주세요").assertExists()

            onNodeWithText("회화").performClick()
            onNodeWithText("미니멀").performClick()
            onNodeWithText("시작하기 (2)").assertIsNotEnabled()
            onNodeWithText("도시").performClick()

            onNodeWithText("시작하기 (3)").assertIsEnabled().performClick()
            assertEquals(1, confirmed)
        }

    @Test
    fun skipIsOfferedOnTheFirstRunAndReported() =
        runComposeUiTest {
            var skipped = 0
            setContent {
                TastePickSheetContent(sheet(), AppLanguage.KO, {}, {}, {}, onSkip = { skipped += 1 })
            }

            onNodeWithText("건너뛰기").performClick()

            assertEquals(1, skipped)
        }

    @Test
    fun editingOffersSaveAndNoSkip() =
        runComposeUiTest {
            setContent {
                TastePickSheetContent(sheet(TastePickMode.EDIT), AppLanguage.KO, {}, {}, {}, {})
            }

            onNodeWithText("저장").assertIsEnabled()
            onNodeWithText("건너뛰기").assertDoesNotExist()
        }

    @Test
    fun everyWordIsEnglishWhenTheAppIsInEnglish() =
        runComposeUiTest {
            setContent {
                TastePickSheetContent(sheet(), AppLanguage.EN, {}, {}, {}, {})
            }

            onNodeWithText("What draws you?").assertExists()
            onNodeWithText("Minimalist").assertExists()
            onNodeWithText("SKIP").assertExists()
            onNodeWithText("START (0)").assertIsNotEnabled()
        }
}
