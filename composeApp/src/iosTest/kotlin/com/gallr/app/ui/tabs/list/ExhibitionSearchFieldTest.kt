package com.gallr.app.ui.tabs.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ExhibitionSearchFieldTest {
    // TabsViewModel hands the List tab its query through combine + stateIn, so on a device the screen
    // can draw a frame with the previous query before the new one arrives. The host here delivers the
    // query only after that frame, which is the order that broke Korean typing: the caret went to the
    // start and the next syllable landed in front of the first.
    @Test
    fun typedSyllablesStayInOrderWhenTheScreenQueryArrivesAFrameLate() =
        runComposeUiTest {
            var lastTyped = ""
            var screenQuery by mutableStateOf("")
            setContent {
                ExhibitionSearchField(
                    initialQuery = screenQuery,
                    onQueryChange = { lastTyped = it },
                    lang = AppLanguage.KO,
                    onDone = {},
                )
            }

            val field = onNode(hasSetTextAction())
            field.performClick()
            for (syllable in listOf("가", "나", "다")) {
                field.performTextInput(syllable)
                waitForIdle()
                screenQuery = lastTyped
                waitForIdle()
            }

            assertEquals("가나다", lastTyped)
        }

    // The filter header collapses on scroll and takes the field out of the screen, so the field must
    // come back showing the query the list is still filtered by.
    @Test
    fun startsWithTheSeededQueryAndClearsIt() =
        runComposeUiTest {
            var lastTyped: String? = null
            setContent {
                ExhibitionSearchField(
                    initialQuery = "모네",
                    onQueryChange = { lastTyped = it },
                    lang = AppLanguage.KO,
                    onDone = {},
                )
            }

            onNode(hasSetTextAction()).assert(hasText("모네"))
            onNodeWithText("✕").performClick()
            waitForIdle()

            assertEquals("", lastTyped)
            onNode(hasSetTextAction()).assert(hasText(""))
        }
}
