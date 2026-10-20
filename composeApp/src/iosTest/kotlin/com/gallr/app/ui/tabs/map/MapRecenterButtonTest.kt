package com.gallr.app.ui.tabs.map

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrTheme
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Map tab's 내 위치 / MY LOCATION button must show its whole label in both languages (found truncated 2026-10-16). */
@OptIn(ExperimentalTestApi::class)
class MapRecenterButtonTest {
    @Test
    fun theEnglishLabelIsNotClipped() =
        runComposeUiTest {
            setContent { GallrTheme { MapRecenterButton(language = AppLanguage.EN, onClick = {}) } }

            assertLabelFits("MY LOCATION", buttonDescription = "RECENTER ON MY LOCATION")
        }

    @Test
    fun theKoreanLabelKeepsTheMinimumWidthAndTheButtonStillReportsTaps() =
        runComposeUiTest {
            var taps = 0
            setContent { GallrTheme { MapRecenterButton(language = AppLanguage.KO, onClick = { taps += 1 }) } }

            assertLabelFits("내 위치", buttonDescription = "내 위치로 이동")
            onNodeWithContentDescription("내 위치로 이동").performClick()

            assertEquals(1, taps)
        }

    /**
     * The whole label must sit on its one line, and the label inside the button. The old fixed width wrapped
     * "MY LOCATION" and, with one line allowed, showed only "MY".
     */
    private fun ComposeUiTest.assertLabelFits(
        text: String,
        buttonDescription: String,
    ) {
        val label = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val button = onNodeWithContentDescription(buttonDescription).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()

        assertEquals(1, layout.lineCount)
        val shown = text.substring(0, layout.getLineEnd(lineIndex = 0, visibleEnd = true))
        assertEquals(text, shown, "the button shows only part of its label")
        assertTrue(label.boundsInRoot.right <= button.boundsInRoot.right + HALF_PIXEL, "label leaves the button")
        val minimumWidthPx = with(label.layoutInfo.density) { MINIMUM_WIDTH.toPx() }
        assertTrue(button.boundsInRoot.width >= minimumWidthPx, "the button keeps its minimum width")
    }

    private companion object {
        const val HALF_PIXEL = 0.5f
        val MINIMUM_WIDTH = 104.dp
    }
}
