package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ended-saved strip's dismissal stays on this device (data-model "Device-only state"). */
class DataStoreDiaryDeviceStateRepositoryTest {
    @Test
    fun nothingDismissedReadsAsZero() =
        runTest {
            assertEquals(
                0,
                DataStoreDiaryDeviceStateRepository(InMemoryPreferencesDataStore()).dismissedEndedSavedCount.first(),
            )
        }

    @Test
    fun theDismissedCountSurvivesReopeningUnderItsFixedKey() =
        runTest {
            val store = InMemoryPreferencesDataStore()

            DataStoreDiaryDeviceStateRepository(store).dismissEndedSavedStrip(3)

            assertEquals(3, DataStoreDiaryDeviceStateRepository(store).dismissedEndedSavedCount.first())
            assertEquals(3, store.data.first()[intPreferencesKey("diary_ended_saved_dismissed_count_v1")])
        }

    @Test
    fun aLaterDismissalReplacesTheEarlierCount() =
        runTest {
            val repository = DataStoreDiaryDeviceStateRepository(InMemoryPreferencesDataStore())

            repository.dismissEndedSavedStrip(2)
            repository.dismissEndedSavedStrip(5)

            assertEquals(5, repository.dismissedEndedSavedCount.first())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
