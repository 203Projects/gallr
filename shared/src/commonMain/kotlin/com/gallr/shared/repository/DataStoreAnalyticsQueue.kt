package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.analytics.MOBILE_ANALYTICS_MAX_BATCH_SIZE
import com.gallr.shared.analytics.MobileAnalyticsQueue
import com.gallr.shared.analytics.QueuedMobileAnalyticsEvent
import com.gallr.shared.analytics.normalizeAnalyticsQueue
import com.gallr.shared.observability.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

private val MOBILE_ANALYTICS_QUEUE_KEY = stringPreferencesKey("mobile_analytics_queue_v1")
private const val MOBILE_ANALYTICS_QUEUE_SCHEMA_VERSION = 1
private val analyticsQueueLog = AppLog.tagged("DataStoreAnalyticsQueue")

@Serializable
private data class MobileAnalyticsQueuePayload(
    val schemaVersion: Int = MOBILE_ANALYTICS_QUEUE_SCHEMA_VERSION,
    val entries: List<QueuedMobileAnalyticsEvent>,
)

/** Versioned, bounded, and independently purgeable analytics queue. */
class DataStoreAnalyticsQueue(
    private val dataStore: DataStore<Preferences>,
) : MobileAnalyticsQueue {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override suspend fun append(entry: QueuedMobileAnalyticsEvent) {
        dataStore.edit { preferences ->
            val updated = normalizeAnalyticsQueue(read(preferences) + entry, entry.queuedAt)
            write(preferences, updated)
        }
    }

    override suspend fun readBatch(
        now: Instant,
        limit: Int,
    ): List<QueuedMobileAnalyticsEvent> {
        require(limit in 1..MOBILE_ANALYTICS_MAX_BATCH_SIZE) {
            "limit must be between 1 and $MOBILE_ANALYTICS_MAX_BATCH_SIZE"
        }
        var batch = emptyList<QueuedMobileAnalyticsEvent>()
        dataStore.edit { preferences ->
            val normalized = normalizeAnalyticsQueue(read(preferences), now)
            write(preferences, normalized)
            batch = normalized.take(limit)
        }
        return batch
    }

    override suspend fun acknowledge(
        eventIds: Set<String>,
        now: Instant,
    ) {
        if (eventIds.isEmpty()) return
        dataStore.edit { preferences ->
            val remaining =
                normalizeAnalyticsQueue(read(preferences), now)
                    .filterNot { it.event.eventId in eventIds }
            write(preferences, remaining)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(MOBILE_ANALYTICS_QUEUE_KEY) }
    }

    private fun read(preferences: Preferences): List<QueuedMobileAnalyticsEvent> {
        val encoded = preferences[MOBILE_ANALYTICS_QUEUE_KEY] ?: return emptyList()
        return try {
            val payload = json.decodeFromString<MobileAnalyticsQueuePayload>(encoded)
            require(payload.schemaVersion == MOBILE_ANALYTICS_QUEUE_SCHEMA_VERSION) {
                "Unsupported analytics queue schema"
            }
            payload.entries
        } catch (error: Exception) {
            analyticsQueueLog.error("decode_queue", error)
            emptyList()
        }
    }

    private fun write(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        entries: List<QueuedMobileAnalyticsEvent>,
    ) {
        if (entries.isEmpty()) {
            preferences.remove(MOBILE_ANALYTICS_QUEUE_KEY)
        } else {
            preferences[MOBILE_ANALYTICS_QUEUE_KEY] =
                json.encodeToString(MobileAnalyticsQueuePayload(entries = entries))
        }
    }
}
