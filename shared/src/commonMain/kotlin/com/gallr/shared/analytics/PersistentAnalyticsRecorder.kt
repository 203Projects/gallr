package com.gallr.shared.analytics

import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

private val analyticsRecorderLog = AppLog.tagged("PersistentAnalyticsRecorder")

/**
 * Appends before delivery and acknowledges only the exact retry IDs confirmed
 * by a successful anonymous batch request. Analytics failures stay isolated
 * from the customer action that produced the event. A purge failure is the one
 * exception: clear/opt-out surfaces it so callers can retry while remaining
 * disabled.
 */
class PersistentAnalyticsRecorder(
    private val queue: MobileAnalyticsQueue,
    private val sink: MobileAnalyticsSink,
    private val now: () -> Instant = { Clock.System.now() },
) : AnalyticsRecorder {
    private val flushMutex = Mutex()

    override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
        try {
            queue.append(QueuedMobileAnalyticsEvent(createEvent(), now()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            analyticsRecorderLog.error("queue_append", error)
        }
    }

    override suspend fun flush() {
        flushMutex.withLock {
            while (deliverNextBatch()) {
                // Continue until the queue returns fewer than one full batch.
            }
        }
    }

    override suspend fun clear() {
        flushMutex.withLock {
            try {
                queue.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analyticsRecorderLog.error("queue_clear", error)
                throw error
            }
        }
    }

    private suspend fun deliverNextBatch(): Boolean {
        val entries =
            try {
                queue.readBatch(now())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analyticsRecorderLog.error("queue_read", error)
                return false
            }
        if (entries.isEmpty()) return false

        try {
            sink.deliver(MobileAnalyticsBatch(entries.map(QueuedMobileAnalyticsEvent::event)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            analyticsRecorderLog.warn("batch_delivery", error)
            return false
        }

        try {
            queue.acknowledge(
                eventIds = entries.mapTo(mutableSetOf()) { it.event.eventId },
                now = now(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            analyticsRecorderLog.error("batch_acknowledge", error)
            return false
        }
        return entries.size == MOBILE_ANALYTICS_MAX_BATCH_SIZE
    }
}
