package com.gallr.shared.analytics

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Failure-isolated boundary; disabled implementations never invoke the event factory. */
interface AnalyticsRecorder {
    suspend fun record(createEvent: () -> MobileAnalyticsEvent)

    suspend fun flush()

    suspend fun clear()
}

/** Recorder used while analytics is unavailable or release-disabled. */
object NoopAnalyticsRecorder : AnalyticsRecorder {
    override suspend fun record(createEvent: () -> MobileAnalyticsEvent) = Unit

    override suspend fun flush() = Unit

    override suspend fun clear() = Unit
}

/** Runtime and user-preference gate that clears work immediately when disabled. */
class GatedAnalyticsRecorder internal constructor(
    private val delegate: AnalyticsRecorder,
    initiallyEnabled: Boolean = false,
) : AnalyticsRecorder {
    private val lifecycleMutex = Mutex()
    private val stateMutex = Mutex()
    private val flushMutex = Mutex()
    private var enabled = initiallyEnabled
    private var purgeRequired = !initiallyEnabled
    private var activeFlushJob: Job? = null
    private var gateCanceledFlushJob: Job? = null

    suspend fun setEnabled(value: Boolean) {
        lifecycleMutex.withLock {
            if (!value) {
                purge(targetEnabled = false)
            } else if (stateMutex.withLock { purgeRequired }) {
                purge(targetEnabled = true)
            } else {
                stateMutex.withLock { enabled = true }
            }
        }
    }

    /** Stops new records and cancels delivery without claiming that local purge has completed. */
    suspend fun pause() {
        lifecycleMutex.withLock {
            val flushJob =
                stateMutex.withLock {
                    enabled = false
                    purgeRequired = true
                    gateCanceledFlushJob = activeFlushJob
                    activeFlushJob
                }
            if (flushJob != null && flushJob !== currentCoroutineContext().job) {
                flushJob.cancelAndJoin()
            }
        }
    }

    override suspend fun record(createEvent: () -> MobileAnalyticsEvent) {
        stateMutex.withLock {
            if (enabled) delegate.record(createEvent)
        }
    }

    override suspend fun flush() {
        flushMutex.withLock {
            coroutineScope {
                val delivery = async(start = CoroutineStart.LAZY) { delegate.flush() }
                val shouldFlush =
                    stateMutex.withLock {
                        if (enabled) {
                            activeFlushJob = delivery
                            true
                        } else {
                            false
                        }
                    }
                if (!shouldFlush) {
                    delivery.cancel()
                    return@coroutineScope
                }
                try {
                    delivery.start()
                    delivery.await()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    currentCoroutineContext().ensureActive()
                    val canceledByGate = stateMutex.withLock { gateCanceledFlushJob === delivery }
                    if (!canceledByGate) throw error
                } finally {
                    withContext(NonCancellable) {
                        stateMutex.withLock {
                            if (activeFlushJob === delivery) activeFlushJob = null
                            if (gateCanceledFlushJob === delivery) gateCanceledFlushJob = null
                        }
                    }
                }
            }
        }
    }

    override suspend fun clear() {
        lifecycleMutex.withLock {
            val resumeEnabled = stateMutex.withLock { enabled }
            purge(targetEnabled = resumeEnabled)
        }
    }

    private suspend fun purge(targetEnabled: Boolean) {
        val flushJob =
            stateMutex.withLock {
                enabled = false
                purgeRequired = true
                gateCanceledFlushJob = activeFlushJob
                activeFlushJob
            }
        if (flushJob != null && flushJob !== currentCoroutineContext().job) {
            flushJob.cancelAndJoin()
        }
        delegate.clear()
        stateMutex.withLock {
            purgeRequired = false
            enabled = targetEnabled
        }
    }
}

/** Creates a gate that purges stale queued work before starting disabled. */
suspend fun createGatedAnalyticsRecorder(
    delegate: AnalyticsRecorder,
    initiallyEnabled: Boolean = false,
): GatedAnalyticsRecorder =
    GatedAnalyticsRecorder(delegate, initiallyEnabled).also { recorder ->
        if (!initiallyEnabled) recorder.setEnabled(false)
    }
