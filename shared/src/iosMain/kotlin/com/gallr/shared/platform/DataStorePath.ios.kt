package com.gallr.shared.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private var dataStoreInstance: DataStore<Preferences>? = null

@OptIn(ExperimentalForeignApi::class)
actual fun createDataStore(): DataStore<Preferences> {
    return dataStoreInstance ?: run {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )?.path ?: error("Could not resolve NSDocumentDirectory")

        PreferenceDataStoreFactory.createWithPath(
            produceFile = { "$directory/$DATASTORE_FILE_NAME".toPath() }
        ).also { dataStoreInstance = it }
    }
}
