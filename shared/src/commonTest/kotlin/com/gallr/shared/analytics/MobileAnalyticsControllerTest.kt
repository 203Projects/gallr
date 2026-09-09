package com.gallr.shared.analytics

import com.gallr.shared.repository.AnalyticsPreferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileAnalyticsControllerTest {
    @Test
    fun `release false purges stored opt in and never constructs or flushes events`() =
        runTest {
            val delegate = RecordingRecorder()
            val preferences = FakePreferences(initiallyEnabled = true)
            val controller = MobileAnalyticsController(delegate, preferences, releaseEnabled = false)
            var factoryCalled = false

            controller.initialize()
            controller.record {
                factoryCalled = true
                event(1)
            }
            controller.onResume()

            assertEquals(1, delegate.clears)
            assertEquals(0, delegate.flushes)
            assertFalse(factoryCalled)
            assertTrue(preferences.enabled.value)
        }

    @Test
    fun `release and user opt in preserve offline work and flush on resume`() =
        runTest {
            val delegate = RecordingRecorder()
            val controller =
                MobileAnalyticsController(
                    delegate,
                    FakePreferences(initiallyEnabled = true),
                    releaseEnabled = true,
                )

            controller.initialize()
            controller.record { event(2) }
            controller.onResume()

            assertEquals(0, delegate.clears)
            assertEquals(listOf(event(2)), delegate.events)
            assertEquals(1, delegate.flushes)
        }

    @Test
    fun `stored opt out purges at startup`() =
        runTest {
            val delegate = RecordingRecorder()
            val controller =
                MobileAnalyticsController(
                    delegate,
                    FakePreferences(initiallyEnabled = false),
                    releaseEnabled = true,
                )

            controller.initialize()

            assertEquals(1, delegate.clears)
        }

    @Test
    fun `failed startup purge is retried on resume`() =
        runTest {
            val delegate = RecordingRecorder(failClears = 1)
            val controller =
                MobileAnalyticsController(
                    delegate,
                    FakePreferences(initiallyEnabled = false),
                    releaseEnabled = true,
                )

            assertFailsWith<IllegalStateException> { controller.initialize() }
            controller.onResume()

            assertEquals(2, delegate.clears)
            assertEquals(0, delegate.flushes)
        }

    @Test
    fun `opt out persists false then clears and prevents later event construction`() =
        runTest {
            val transitions = mutableListOf<String>()
            val delegate = RecordingRecorder(onClear = { transitions += "clear" })
            val preferences =
                FakePreferences(
                    initiallyEnabled = true,
                    onSet = { transitions += "persist:$it" },
                )
            val controller = MobileAnalyticsController(delegate, preferences, releaseEnabled = true)
            controller.initialize()
            controller.record { event(3) }

            controller.setUserEnabled(false)
            var factoryCalled = false
            controller.record {
                factoryCalled = true
                event(4)
            }

            assertFalse(preferences.enabled.value)
            assertEquals(1, delegate.clears)
            assertFalse(factoryCalled)
            assertEquals(listOf("persist:false", "clear"), transitions)
        }

    @Test
    fun `failed opt out purge stays disabled and can be retried explicitly`() =
        runTest {
            val delegate = RecordingRecorder(failClears = 1)
            val preferences = FakePreferences(initiallyEnabled = true)
            val controller = MobileAnalyticsController(delegate, preferences, releaseEnabled = true)
            controller.initialize()
            controller.record { event(5) }

            assertFailsWith<IllegalStateException> { controller.setUserEnabled(false) }
            var factoryCalled = false
            controller.record {
                factoryCalled = true
                event(6)
            }

            assertFalse(preferences.enabled.value)
            assertFalse(factoryCalled)
            assertEquals(1, delegate.clears)

            controller.setUserEnabled(false)

            assertEquals(2, delegate.clears)
            assertTrue(delegate.events.isEmpty())
        }

    private fun event(index: Int) =
        MobileAnalyticsEvent.surfaceViewed(
            eventId = "a5000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
            occurredOn = LocalDate(2026, 8, 30),
            platform = AnalyticsPlatform.ANDROID,
            appMajor = 1,
            surface = AnalyticsSurface.FEATURED,
            entryPoint = AnalyticsEntryPoint.TAB,
        )

    private class FakePreferences(
        initiallyEnabled: Boolean,
        private val onSet: (Boolean) -> Unit = {},
    ) : AnalyticsPreferenceRepository {
        val enabled = MutableStateFlow(initiallyEnabled)

        override fun observeEnabled(): Flow<Boolean> = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            onSet(enabled)
            this.enabled.value = enabled
        }
    }

    private class RecordingRecorder(
        private var failClears: Int = 0,
        private val onClear: () -> Unit = {},
    ) : AnalyticsRecorder {
        val events = mutableListOf<MobileAnalyticsEvent>()
        var flushes = 0
        var clears = 0

        override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
            events += createEvent()
        }

        override suspend fun flush() {
            flushes += 1
        }

        override suspend fun clear() {
            onClear()
            clears += 1
            if (failClears-- > 0) error("private clear detail")
            events.clear()
        }
    }
}
