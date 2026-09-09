package com.gallr.shared.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

internal const val DATASTORE_FILE_NAME = "gallr_bookmarks.preferences_pb"
internal const val EXHIBITION_CACHE_DATASTORE_FILE_NAME = "gallr_exhibition_cache.preferences_pb"
internal const val ANALYTICS_QUEUE_DATASTORE_FILE_NAME = "gallr_mobile_analytics_queue.preferences_pb"

expect fun createDataStore(): DataStore<Preferences>

/** Returns the platform cache DataStore; its contents may be purged by the operating system. */
expect fun createExhibitionCacheDataStore(): DataStore<Preferences>

/** Returns the isolated, OS-purgeable analytics retry queue. */
expect fun createAnalyticsQueueDataStore(): DataStore<Preferences>
