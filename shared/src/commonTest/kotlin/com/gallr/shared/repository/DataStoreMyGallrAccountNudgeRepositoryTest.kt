package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataStoreMyGallrAccountNudgeRepositoryTest {
    @Test
    fun `dismissal starts false and survives repository reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val first = DataStoreMyGallrAccountNudgeRepository(store)

            assertFalse(first.observeDismissed().first())

            first.dismiss()

            assertTrue(DataStoreMyGallrAccountNudgeRepository(store).observeDismissed().first())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
