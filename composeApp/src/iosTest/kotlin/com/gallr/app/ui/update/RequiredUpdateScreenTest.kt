package com.gallr.app.ui.update

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.ui.theme.GallrTheme
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 098 contracts/update-screens.md: the notice, its one action and the fallback when the store cannot open. */
@OptIn(ExperimentalTestApi::class)
class RequiredUpdateScreenTest {
    @Test
    fun theNoticeHasAHeadingAndOneActionThatOpensTheStore() =
        runComposeUiTest {
            var opened = 0
            setContent {
                GallrTheme {
                    RequiredUpdateScreen(
                        language = AppLanguage.KO,
                        onUpdate = {
                            opened += 1
                            true
                        },
                    )
                }
            }

            onNodeWithText("업데이트가 필요해요").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            onNodeWithText("지금 쓰는 버전은 더 이상 지원되지 않아요. 업데이트하면 계속 쓸 수 있어요.").assertIsDisplayed()
            onNodeWithText("업데이트").performClick()

            assertEquals(1, opened)
            onNodeWithText("스토어에서 gallr를 업데이트해 주세요").assertDoesNotExist()
        }

    @Test
    fun whenTheStoreCannotOpenTheNoticeSaysSoAndStays() =
        runComposeUiTest {
            setContent {
                GallrTheme {
                    RequiredUpdateScreen(language = AppLanguage.EN, onUpdate = { false })
                }
            }

            onNodeWithText("UPDATE").performClick()

            onNodeWithText("Please update gallr from the store").assertIsDisplayed()
            onNodeWithText("UPDATE REQUIRED").assertIsDisplayed()
        }
}
