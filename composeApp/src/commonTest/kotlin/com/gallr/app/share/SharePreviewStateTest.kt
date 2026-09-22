package com.gallr.app.share

import com.gallr.app.viewmodel.SharePreviewState
import com.gallr.app.viewmodel.SharePreviewStateHolder
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.model.resolvesToDark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharePreviewStateTest {
    @Test
    fun `theme resolution and palettes match app tokens`() {
        assertFalse(ThemeMode.LIGHT.resolvesToDark(true))
        assertTrue(ThemeMode.DARK.resolvesToDark(false))
        assertTrue(ThemeMode.SYSTEM.resolvesToDark(true))
        assertFalse(ThemeMode.SYSTEM.resolvesToDark(false))
        assertEquals(0xFFFFFFFF.toInt(), ExhibitionStoryCardPalette.LIGHT.background)
        assertEquals(0xFF121212.toInt(), ExhibitionStoryCardPalette.DARK.background)
        assertEquals(0xFFE0E0E0.toInt(), ExhibitionStoryCardPalette.DARK.title)
        assertEquals(0xFF525252.toInt(), ExhibitionStoryCardPalette.LIGHT.secondary)
    }

    @Test
    fun `ready shares the same card and blocks duplicate sheets until completion`() =
        runTest {
            val card = StoryCardImage(byteArrayOf(1, 2), "image")
            var shared: StoryCardImage? = null
            var completion: (() -> Unit)? = null
            var calls = 0
            val holder =
                SharePreviewStateHolder(this, { card }) { value, done ->
                    calls++
                    shared = value
                    completion = done
                }
            holder.share()
            assertEquals(0, calls)
            runCurrent()
            assertSame(card, assertIs<SharePreviewState.Ready>(holder.state.value).card)
            holder.share()
            holder.share()
            assertSame(card, shared)
            assertEquals(1, calls)
            completion?.invoke()
            holder.share()
            assertEquals(2, calls)
            holder.close()
        }

    @Test
    fun `failed render can retry and cannot share`() =
        runTest {
            var attempts = 0
            val card = StoryCardImage(byteArrayOf(1), "image")
            var shares = 0
            val holder =
                SharePreviewStateHolder(this, {
                    if (attempts++ == 0) error("render failed")
                    card
                }) { _, _ -> shares++ }
            runCurrent()
            assertIs<SharePreviewState.Failed>(holder.state.value)
            holder.share()
            assertEquals(0, shares)
            holder.retry()
            runCurrent()
            assertSame(card, assertIs<SharePreviewState.Ready>(holder.state.value).card)
            holder.close()
        }

    @Test
    fun `close cancels render and prevents later actions`() =
        runTest {
            val cancelled = CompletableDeferred<Unit>()
            val holder =
                SharePreviewStateHolder(this, {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }) { _, _ -> error("must not share") }
            runCurrent()
            holder.close()
            runCurrent()
            assertTrue(cancelled.isCompleted)
            holder.retry()
            holder.share()
            assertIs<SharePreviewState.Rendering>(holder.state.value)
        }
}
