package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataStoreAnalyticsPreferenceRepositoryTest {
    @Test
    fun `preference defaults off and round trips independently of account state`() =
        runTest {
            val repository = DataStoreAnalyticsPreferenceRepository(InMemoryPreferencesDataStore())

            assertFalse(repository.observeEnabled().first())
            repository.setEnabled(true)
            assertTrue(repository.observeEnabled().first())
            repository.setEnabled(false)
            assertFalse(repository.observeEnabled().first())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }
}
