package com.gallr.shared.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistentAnalyticsRecorderTest {
    @Test
    fun `record persists before flush and successful delivery acknowledges exact ids`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val sink = FakeAnalyticsSink()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, sink, now = { now })
            val event = event(1)

            recorder.record { event }
            assertEquals(listOf(event.eventId), queue.entries.map { it.event.eventId })

            recorder.flush()

            assertEquals(listOf(listOf(event)), sink.deliveries)
            assertEquals(emptyList(), queue.entries)
        }

    @Test
    fun `delivery and acknowledgement failures retain retry identities`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val sink = FakeAnalyticsSink(failDeliveries = 1)
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, sink, now = { now })
            val event = event(2)
            recorder.record { event }

            recorder.flush()
            assertEquals(listOf(event.eventId), queue.entries.map { it.event.eventId })

            queue.failAcknowledgements = 1
            recorder.flush()
            assertEquals(listOf(event.eventId), queue.entries.map { it.event.eventId })

            recorder.flush()
            assertEquals(emptyList(), queue.entries)
            assertEquals(3, sink.attempts)
            assertEquals(listOf(listOf(event), listOf(event)), sink.deliveries)
        }

    @Test
    fun `concurrent flushes serialize one network delivery`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val sink = FakeAnalyticsSink()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, sink, now = { now })
            recorder.record { event(3) }

            val first = async { recorder.flush() }
            val second = async { recorder.flush() }
            first.await()
            second.await()

            assertEquals(1, sink.attempts)
            assertEquals(emptyList(), queue.entries)
        }

    @Test
    fun `record appended during delivery survives exact acknowledgement`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val sink = BlockingAnalyticsSink()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, sink, now = { now })
            val inFlight = event(5)
            val later = event(6)
            recorder.record { inFlight }

            val flush = async { recorder.flush() }
            sink.started.await()
            recorder.record { later }
            sink.release.complete(Unit)
            flush.await()

            assertEquals(listOf(later.eventId), queue.entries.map { it.event.eventId })
        }

    @Test
    fun `flush drains bounded batches until the queue is empty`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val sink = FakeAnalyticsSink()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, sink, now = { now })
            repeat(21) { index -> recorder.record { event(index + 10) } }

            recorder.flush()

            assertEquals(listOf(20, 1), sink.deliveries.map { it.size })
            assertEquals(emptyList(), queue.entries)
        }

    @Test
    fun `disabled gate cancels active flush then clears and blocks later work`() =
        runTest {
            val delegate = BlockingAnalyticsRecorder()
            val gate = GatedAnalyticsRecorder(delegate, initiallyEnabled = true)
            var productContinuationRan = false
            val flush =
                async {
                    gate.flush()
                    productContinuationRan = true
                }
            delegate.started.await()
            val disable = async { gate.setEnabled(false) }

            disable.await()
            flush.await()
            gate.record { event(4) }

            assertEquals(1, delegate.flushes)
            assertEquals(1, delegate.clears)
            assertEquals(0, delegate.records)
            assertTrue(productContinuationRan)
        }

    @Test
    fun `genuine caller cancellation still propagates`() =
        runTest {
            val delegate = BlockingAnalyticsRecorder()
            val gate = GatedAnalyticsRecorder(delegate, initiallyEnabled = true)
            val flush = async { gate.flush() }
            delegate.started.await()

            flush.cancel()

            assertFailsWith<CancellationException> { flush.await() }
        }

    @Test
    fun `disabled startup purges stale work before exposing the gate`() =
        runTest {
            val delegate = BlockingAnalyticsRecorder()

            val gate = createGatedAnalyticsRecorder(delegate, initiallyEnabled = false)
            var factoryCalled = false
            gate.record {
                factoryCalled = true
                event(9)
            }

            assertEquals(1, delegate.clears)
            assertFalse(factoryCalled)
        }

    @Test
    fun `failed opt-out purge is surfaced while the gate remains disabled`() =
        runTest {
            val queue = FakeAnalyticsQueue()
            val now = Instant.parse("2026-08-30T12:00:00Z")
            val recorder = PersistentAnalyticsRecorder(queue, FakeAnalyticsSink(), now = { now })
            val gate = GatedAnalyticsRecorder(recorder, initiallyEnabled = true)
            val retained = event(7)
            gate.record { retained }
            queue.failClears = 1

            assertFailsWith<IllegalStateException> { gate.setEnabled(false) }
            var disabledFactoryCalled = false
            gate.record {
                disabledFactoryCalled = true
                event(8)
            }

            assertFalse(disabledFactoryCalled)
            assertEquals(listOf(retained.eventId), queue.entries.map { it.event.eventId })

            gate.setEnabled(true)
            val fresh = event(8)
            gate.record { fresh }
            assertEquals(listOf(fresh.eventId), queue.entries.map { it.event.eventId })
        }

    private fun event(index: Int) =
        MobileAnalyticsEvent.surfaceViewed(
            eventId = "a3000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
            occurredOn = LocalDate(2026, 8, 30),
            platform = AnalyticsPlatform.IOS,
            appMajor = 1,
            surface = AnalyticsSurface.MAP,
            entryPoint = AnalyticsEntryPoint.TAB,
        )

    private class FakeAnalyticsQueue : MobileAnalyticsQueue {
        val entries = mutableListOf<QueuedMobileAnalyticsEvent>()
        var failAcknowledgements = 0
        var failClears = 0

        override suspend fun append(entry: QueuedMobileAnalyticsEvent) {
            entries += entry
        }

        override suspend fun readBatch(
            now: Instant,
            limit: Int,
        ): List<QueuedMobileAnalyticsEvent> = entries.take(limit)

        override suspend fun acknowledge(
            eventIds: Set<String>,
            now: Instant,
        ) {
            if (failAcknowledgements-- > 0) error("ack detail must not be logged")
            entries.removeAll { it.event.eventId in eventIds }
        }

        override suspend fun clear() {
            if (failClears-- > 0) error("clear detail must not be logged")
            entries.clear()
        }
    }

    private class FakeAnalyticsSink(
        private var failDeliveries: Int = 0,
    ) : MobileAnalyticsSink {
        var attempts = 0
        val deliveries = mutableListOf<List<MobileAnalyticsEvent>>()

        override suspend fun deliver(batch: MobileAnalyticsBatch) {
            attempts += 1
            if (failDeliveries-- > 0) error("network detail must not be logged")
            deliveries += batch.events
        }
    }

    private class BlockingAnalyticsRecorder : AnalyticsRecorder {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var records = 0
        var flushes = 0
        var clears = 0

        override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
            records += 1
        }

        override suspend fun flush() {
            flushes += 1
            started.complete(Unit)
            release.await()
        }

        override suspend fun clear() {
            clears += 1
        }
    }

    private class BlockingAnalyticsSink : MobileAnalyticsSink {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun deliver(batch: MobileAnalyticsBatch) {
            started.complete(Unit)
            release.await()
        }
    }
}
