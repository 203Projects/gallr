package com.gallr.shared.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

private var dataStoreInstance: DataStore<Preferences>? = null

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
}

actual fun createDataStore(): DataStore<Preferences> =
    checkNotNull(dataStoreInstance) {
        "DataStore not initialized. Call initDataStore(context) before createDataStore()."
    }
