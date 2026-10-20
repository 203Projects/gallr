package com.gallr.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 094: the tab bar under every screen (US1), each tab keeping its place (US2), and the keyboard (US4). */
@OptIn(ExperimentalTestApi::class)
class TabNavigationShellTest {
    @Test
    fun theBarStaysUnderAScreenAndHighlightsItsTab() =
        runComposeUiTest {
            setContent {
                TabNavigationShell(
                    selectedTab = AppTab.MY,
                    onTabSelected = {},
                    lang = AppLanguage.KO,
                    imeBottomPx = 0,
                ) { Box(Modifier.fillMaxSize().testTag("screen")) }
            }

            onNodeWithTag("screen").assertExists()
            onNodeWithTag(TAB_BAR_TEST_TAG).assertExists()
            onNodeWithText("MY").assertIsSelected()
            onNodeWithText("홈").assertIsNotSelected()
        }

    @Test
    fun tappingATabReportsIt() =
        runComposeUiTest {
            var selected by mutableStateOf(AppTab.HOME)
            setContent {
                TabNavigationShell(
                    selectedTab = selected,
                    onTabSelected = { selected = it },
                    lang = AppLanguage.EN,
                    imeBottomPx = 0,
                ) { Box(Modifier.fillMaxSize()) }
            }

            onNodeWithText("MAP").performClick()

            assertEquals(AppTab.MAP, selected)
            onNodeWithText("MAP").assertIsSelected()
        }

    @Test
    fun aScreensOwnActionBarSitsDirectlyAboveTheTabBar() =
        runComposeUiTest {
            setContent {
                TabNavigationShell(
                    selectedTab = AppTab.MY,
                    onTabSelected = {},
                    lang = AppLanguage.KO,
                    imeBottomPx = 0,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("actionBar"),
                        )
                    }
                }
            }

            val actionBar = onNodeWithTag("actionBar").getUnclippedBoundsInRoot()
            val tabBar = onNodeWithTag(TAB_BAR_TEST_TAG).getUnclippedBoundsInRoot()
            assertEquals(tabBar.top, actionBar.bottom)
        }

    @Test
    fun aScreenLeftForAnotherTabComesBackAsItWas() =
        runComposeUiTest {
            val navigation = AppNavigationState()
            setContent {
                TabScreenHost(navigation) { screen ->
                    var taps by rememberSaveable { mutableStateOf(0) }
                    Text(
                        text = "${screen.key}:$taps",
                        modifier = Modifier.testTag("screen").clickable { taps += 1 },
                    )
                }
            }
            runOnIdle { navigation.showEditor("editor-one") }
            onNodeWithTag("screen").performClick()
            onNodeWithTag("screen").performClick()
            onNodeWithTag("screen").assertTextEquals("entry-0:2")

            runOnIdle { navigation.selectTab(AppTab.MAP) }
            onNodeWithTag("screen").assertTextEquals("tab-MAP:0")
            runOnIdle { navigation.selectTab(AppTab.HOME) }

            onNodeWithTag("screen").assertTextEquals("entry-0:2")
        }

    @Test
    fun aScreenClosedAndOpenedAgainStartsFresh() =
        runComposeUiTest {
            val navigation = AppNavigationState()
            setContent {
                TabScreenHost(navigation) { screen ->
                    var taps by rememberSaveable { mutableStateOf(0) }
                    Text(
                        text = "${screen.destination}:$taps",
                        modifier = Modifier.testTag("screen").clickable { taps += 1 },
                    )
                }
            }
            runOnIdle { navigation.showEditor("editor-one") }
            onNodeWithTag("screen").performClick()

            runOnIdle {
                navigation.selectTab(AppTab.HOME)
                navigation.showEditor("editor-one")
            }

            onNodeWithTag("screen").assertTextEquals("${AppDestination.EditorDetail("editor-one")}:0")
        }

    @Test
    fun theBarStepsAsideWhileTheKeyboardIsOpen() =
        runComposeUiTest {
            var imeBottom by mutableStateOf(300)
            setContent {
                TabNavigationShell(
                    selectedTab = AppTab.MY,
                    onTabSelected = {},
                    lang = AppLanguage.KO,
                    imeBottomPx = imeBottom,
                ) { Box(Modifier.fillMaxSize().testTag("screen")) }
            }

            onNodeWithTag(TAB_BAR_TEST_TAG).assertDoesNotExist()
            assertEquals(
                onRoot().getUnclippedBoundsInRoot().bottom,
                onNodeWithTag("screen").getUnclippedBoundsInRoot().bottom,
            )

            imeBottom = 0
            onNodeWithTag(TAB_BAR_TEST_TAG).assertExists()
        }
}
