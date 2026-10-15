package com.gallr.app.ui.route.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.gallr.app.ShareHandler
import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.share.RouteShareCardContent
import com.gallr.app.share.RouteShareCardPalette
import com.gallr.app.share.StoryCardImage
import com.gallr.app.viewmodel.RouteSharePayload
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.route.PersonalRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The view model clears the payload as soon as the effect consumes it. Clearing it must not cancel the card that is
 * still rendering, or the share sheet never opens (regression found 2026-10-16).
 */
@OptIn(ExperimentalTestApi::class)
class RouteShareEffectTest {
    private val payload =
        RouteSharePayload(
            route = PersonalRoute(id = "route-1", name = "성수 오후 산책", stops = emptyList(), isPublished = true),
            link = "https://gallrmap.com/route/route-1?s=share&v=1",
        )

    @Test
    fun theSheetOpensAfterThePayloadIsConsumed() =
        runComposeUiTest {
            val handler = RecordingShareHandler()
            var consumed = 0
            setContent {
                var shown by remember { mutableStateOf<RouteSharePayload?>(payload) }
                RouteShareEffect(shown, AppLanguage.KO, handler, darkCard = false, onConsumed = {
                    consumed += 1
                    shown = null
                })
            }

            waitUntil(timeoutMillis = 5_000) { handler.shared.isNotEmpty() }

            assertEquals(listOf("route"), handler.shared)
            assertEquals(1, consumed)
        }

    private class RecordingShareHandler : ShareHandler {
        val shared = mutableListOf<String>()

        override fun shareApp() = Unit

        override suspend fun renderExhibitionStoryCard(
            exhibition: Exhibition,
            lang: AppLanguage,
            palette: ExhibitionStoryCardPalette,
        ): StoryCardImage = error("not used")

        // Rendering leaves the main thread, as both platforms do.
        override suspend fun renderRouteCard(
            content: RouteShareCardContent,
            palette: RouteShareCardPalette,
        ): StoryCardImage = withContext(Dispatchers.Default) { StoryCardImage(ByteArray(0), "route") }

        override fun shareStoryCard(
            card: StoryCardImage,
            onDismiss: () -> Unit,
            onPresented: () -> Unit,
        ) {
            shared += card.shareDescriptor
        }
    }
}
