package com.gallr.app.splash

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SplashControllerTest {

    @Test
    fun `happy path - theme then data ready then min time gates dismissal`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()

        assertTrue(controller.isVisible.value, "visible at t=0")

        advanceTimeBy(200)
        controller.markThemeReady()
        controller.markDataReady()
        runCurrent()
        assertTrue(controller.isVisible.value, "still visible at t=200ms (min not elapsed)")

        advanceTimeBy(1300)  // total = 1500
        runCurrent()
        assertFalse(controller.isVisible.value, "dismisses at min-time gate")
    }

    @Test
    fun `slow data - data gate is the bottleneck`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        controller.markThemeReady()

        advanceTimeBy(1500)
        runCurrent()
        assertTrue(controller.isVisible.value, "still visible — data not ready")

        advanceTimeBy(500)  // total 2000
        runCurrent()
        controller.markDataReady()
        runCurrent()
        assertFalse(controller.isVisible.value, "dismisses immediately when data arrives after min")
    }

    @Test
    fun `hard cap dismisses at 3s regardless of state`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        // Theme never marked ready
        advanceTimeBy(2999)
        runCurrent()
        assertTrue(controller.isVisible.value, "still visible at 2999ms")
        advanceTimeBy(1)  // total 3000
        runCurrent()
        assertFalse(controller.isVisible.value, "hard cap dismisses at exactly 3000ms")
    }

    @Test
    fun `fast error - markDataReady on Error path also dismisses`() = runTest {
        // Simulates: featuredState becomes Error at t=200ms
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        controller.markThemeReady()
        advanceTimeBy(200)
        runCurrent()
        controller.markDataReady()  // caller treats Error == data-ready
        runCurrent()
        assertTrue(controller.isVisible.value, "min not elapsed yet")
        advanceTimeBy(1300)
        runCurrent()
        assertFalse(controller.isVisible.value, "dismisses at 1500ms via min-time gate")
    }

    @Test
    fun `idempotent - duplicate markDataReady calls do not crash`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        controller.markThemeReady()
        controller.markDataReady()
        controller.markDataReady()
        controller.markDataReady()
        advanceTimeBy(1500)
        runCurrent()
        assertFalse(controller.isVisible.value)
    }

    @Test
    fun `skipSplash flips visibility to false immediately`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = backgroundScope,
        )
        controller.start()
        runCurrent()
        assertTrue(controller.isVisible.value)
        controller.skipSplash()
        runCurrent()
        assertFalse(controller.isVisible.value, "skipSplash is immediate")
    }
}
