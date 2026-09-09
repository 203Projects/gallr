package com.gallr.shared.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private var dataStoreInstance: DataStore<Preferences>? = null
private var exhibitionCacheDataStoreInstance: DataStore<Preferences>? = null
private var analyticsQueueDataStoreInstance: DataStore<Preferences>? = null

@OptIn(ExperimentalForeignApi::class)
actual fun createDataStore(): DataStore<Preferences> =
    dataStoreInstance ?: run {
        val directory =
            NSFileManager.defaultManager
                .URLForDirectory(
                    directory = NSDocumentDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = false,
                    error = null,
                )?.path ?: error("Could not resolve NSDocumentDirectory")

        PreferenceDataStoreFactory
            .createWithPath(
                produceFile = { "$directory/$DATASTORE_FILE_NAME".toPath() },
            ).also { dataStoreInstance = it }
    }

@OptIn(ExperimentalForeignApi::class)
actual fun createExhibitionCacheDataStore(): DataStore<Preferences> =
    exhibitionCacheDataStoreInstance ?: run {
        val directory =
            NSFileManager.defaultManager
                .URLForDirectory(
                    directory = NSCachesDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = true,
                    error = null,
                )?.path ?: error("Could not resolve NSCachesDirectory")

        PreferenceDataStoreFactory
            .createWithPath(
                produceFile = { "$directory/$EXHIBITION_CACHE_DATASTORE_FILE_NAME".toPath() },
            ).also { exhibitionCacheDataStoreInstance = it }
    }

@OptIn(ExperimentalForeignApi::class)
actual fun createAnalyticsQueueDataStore(): DataStore<Preferences> =
    analyticsQueueDataStoreInstance ?: run {
        val directory =
            NSFileManager.defaultManager
                .URLForDirectory(
                    directory = NSCachesDirectory,
                    inDomain = NSUserDomainMask,
                    appropriateForURL = null,
                    create = true,
                    error = null,
                )?.path ?: error("Could not resolve NSCachesDirectory")

        PreferenceDataStoreFactory
            .createWithPath(
                produceFile = { "$directory/$ANALYTICS_QUEUE_DATASTORE_FILE_NAME".toPath() },
            ).also { analyticsQueueDataStoreInstance = it }
    }
