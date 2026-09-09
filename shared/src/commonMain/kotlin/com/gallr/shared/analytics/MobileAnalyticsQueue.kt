package com.gallr.shared.analytics

import kotlin.time.Instant

/** Atomic persistence boundary for the identity-free retry queue. */
interface MobileAnalyticsQueue {
    suspend fun append(entry: QueuedMobileAnalyticsEvent)

    suspend fun readBatch(
        now: Instant,
        limit: Int = MOBILE_ANALYTICS_MAX_BATCH_SIZE,
    ): List<QueuedMobileAnalyticsEvent>

    suspend fun acknowledge(
        eventIds: Set<String>,
        now: Instant,
    )

    suspend fun clear()
}

/** Anonymous network boundary. It receives no account, session, or installation identity. */
fun interface MobileAnalyticsSink {
    suspend fun deliver(batch: MobileAnalyticsBatch)
}
