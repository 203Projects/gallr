package com.gallr.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.map.WalkingRouter
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 093 US2: the full-screen route map's controls, credit and description around the map itself. */
@OptIn(ExperimentalTestApi::class)
class RouteMapFullScreenTest {
    @Test
    fun theExpandControlOpensTheFullScreenMap() =
        runComposeUiTest {
            var expanded = 0
            setContent { RouteMapExpandButton(language = AppLanguage.KO, onExpand = { expanded += 1 }) }

            onNodeWithContentDescription("지도 크게 보기").performClick()

            assertEquals(1, expanded)
        }

    @Test
    fun closeAndFitRouteAreOfferedAndReported() =
        runComposeUiTest {
            val events = mutableListOf<String>()
            setContent {
                RouteMapFullScreenChrome(
                    language = AppLanguage.KO,
                    description = routeMapLabel(3, AppLanguage.KO, distanceMeters = 2_412),
                    credit = routeMapCredit(routers = setOf(WalkingRouter.OPENROUTESERVICE)),
                    onFitRoute = { events += "fit" },
                    onClose = { events += "close" },
                    onCredit = { events += "credit" },
                ) { Box(Modifier.fillMaxSize().testTag("map")) }
            }

            onNodeWithTag("map").assertExists()
            onNodeWithContentDescription("경로 전체 보기").performClick()
            onNodeWithContentDescription("닫기").performClick()
            onNodeWithText("© OpenFreeMap · © OpenStreetMap · © openrouteservice by HeiGIT").performClick()

            assertEquals(listOf("fit", "close", "credit"), events)
        }

    @Test
    fun theMapIsDescribedForScreenReaders() =
        runComposeUiTest {
            setContent {
                RouteMapFullScreenChrome(
                    language = AppLanguage.EN,
                    description = routeMapLabel(3, AppLanguage.EN, distanceMeters = 2_412),
                    credit = routeMapCredit(routers = emptySet()),
                    onFitRoute = {},
                    onClose = {},
                    onCredit = {},
                ) { Box(Modifier.fillMaxSize()) }
            }

            onNodeWithContentDescription("ROUTE MAP · 3 STOPS · ~2.4 KM").assertExists()
            onNodeWithContentDescription("FIT ROUTE").assertExists()
            onNodeWithContentDescription("CLOSE").assertExists()
        }
}
