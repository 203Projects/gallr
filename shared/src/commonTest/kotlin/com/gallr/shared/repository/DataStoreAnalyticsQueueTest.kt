package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.analytics.AnalyticsEntryPoint
import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.MobileAnalyticsEvent
import com.gallr.shared.analytics.QueuedMobileAnalyticsEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DataStoreAnalyticsQueueTest {
    @Test
    fun `append read and acknowledgement preserve events added during delivery`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val queue = DataStoreAnalyticsQueue(store)
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val first = queued(1, now)
            val second = queued(2, now + 1.seconds)

            queue.append(first)
            val inFlight = queue.readBatch(now = now + 1.seconds, limit = 20)
            queue.append(second)
            queue.acknowledge(
                eventIds = inFlight.mapTo(mutableSetOf()) { it.event.eventId },
                now = now + 2.seconds,
            )

            val reconstructed = DataStoreAnalyticsQueue(store)
            assertEquals(listOf(second), reconstructed.readBatch(now + 2.seconds, 20))
        }

    @Test
    fun `queue enforces retention cap dedupe and purge`() =
        runTest {
            val queue = DataStoreAnalyticsQueue(InMemoryPreferencesDataStore())
            val now = Instant.parse("2026-08-30T12:00:00Z")
            queue.append(queued(1, now - 8.days))
            repeat(205) { index ->
                queue.append(queued(index + 2, now + index.seconds))
            }

            val retained = queue.readBatch(now = now + 300.seconds, limit = 20)

            assertEquals(20, retained.size)
            assertEquals(uuid(7), retained.first().event.eventId)
            queue.clear()
            assertTrue(queue.readBatch(now + 300.seconds, 20).isEmpty())
        }

    @Test
    fun `corrupt or unsupported payload is purged before append`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val queueKey = stringPreferencesKey("mobile_analytics_queue_v1")
            store.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[queueKey] = "{\"schemaVersion\":999,\"entries\":[]}"
                }
            }
            val queue = DataStoreAnalyticsQueue(store)
            val entry = queued(1, Instant.parse("2026-08-30T12:00:00Z"))

            queue.append(entry)

            assertEquals(listOf(entry), queue.readBatch(entry.queuedAt, 20))
        }

    @Test
    fun `backward wall clock adjustment preserves and clamps queued work`() =
        runTest {
            val queue = DataStoreAnalyticsQueue(InMemoryPreferencesDataStore())
            val originalNow = Instant.parse("2026-08-30T12:00:00Z")
            val adjustedNow = originalNow - 1.days
            queue.append(queued(1, originalNow))

            queue.append(queued(2, adjustedNow))

            val retained = queue.readBatch(adjustedNow, 20)
            assertEquals(listOf(uuid(1), uuid(2)).sorted(), retained.map { it.event.eventId }.sorted())
            assertTrue(retained.all { it.queuedAt == adjustedNow })
        }

    private fun queued(
        index: Int,
        queuedAt: Instant,
    ) = QueuedMobileAnalyticsEvent(event(index), queuedAt)

    private fun event(index: Int) =
        MobileAnalyticsEvent.surfaceViewed(
            eventId = uuid(index),
            occurredOn = LocalDate(2026, 8, 30),
            platform = AnalyticsPlatform.ANDROID,
            appMajor = 1,
            surface = AnalyticsSurface.FEATURED,
            entryPoint = AnalyticsEntryPoint.TAB,
        )

    private fun uuid(index: Int): String = "a2000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }
}
