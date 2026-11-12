package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The count the ended-saved strip was closed at; renaming the key needs a migration. */
private val DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY = intPreferencesKey("diary_ended_saved_dismissed_count_v1")

/** [DiaryDeviceStateRepository] in the app's preferences DataStore. */
class DataStoreDiaryDeviceStateRepository(
    private val dataStore: DataStore<Preferences>,
) : DiaryDeviceStateRepository {
    override val dismissedEndedSavedCount: Flow<Int> =
        dataStore.data.map { it[DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY] ?: 0 }.distinctUntilChanged()

    override suspend fun dismissEndedSavedStrip(count: Int) {
        dataStore.edit { it[DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY] = count }
    }
}
