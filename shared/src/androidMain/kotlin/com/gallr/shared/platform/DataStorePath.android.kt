package com.gallr.shared.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

private var dataStoreInstance: DataStore<Preferences>? = null
private var exhibitionCacheDataStoreInstance: DataStore<Preferences>? = null
private var analyticsQueueDataStoreInstance: DataStore<Preferences>? = null

/**
 * Must be called once in MainActivity.onCreate() (or Application.onCreate())
 * before the shared module creates a BookmarkRepositoryImpl.
 */
fun initDataStore(context: Context) {
    if (dataStoreInstance == null) {
        dataStoreInstance =
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.filesDir, DATASTORE_FILE_NAME) },
            )
    }
    if (exhibitionCacheDataStoreInstance == null) {
        exhibitionCacheDataStoreInstance =
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.cacheDir, EXHIBITION_CACHE_DATASTORE_FILE_NAME) },
            )
    }
    if (analyticsQueueDataStoreInstance == null) {
        analyticsQueueDataStoreInstance =
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.cacheDir, ANALYTICS_QUEUE_DATASTORE_FILE_NAME) },
            )
    }
}

actual fun createDataStore(): DataStore<Preferences> =
    checkNotNull(dataStoreInstance) {
        "DataStore not initialized. Call initDataStore(context) before createDataStore()."
    }

actual fun createExhibitionCacheDataStore(): DataStore<Preferences> =
    checkNotNull(exhibitionCacheDataStoreInstance) {
        "DataStore not initialized. Call initDataStore(context) before creating the exhibition cache."
    }

actual fun createAnalyticsQueueDataStore(): DataStore<Preferences> =
    checkNotNull(analyticsQueueDataStoreInstance) {
        "DataStore not initialized. Call initDataStore(context) before creating the analytics queue."
    }
