package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.route.RouteVisitTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 096 FR-007: the composer's chosen visit time stays on the device, for one draft. */
class DataStoreRouteVisitTimeRepositoryTest {
    private val saturday = RouteVisitTime(LocalDate(2026, 10, 10), LocalTime(13, 0))

    @Test
    fun startsWithNoChoice() =
        runTest {
            assertNull(DataStoreRouteVisitTimeRepository(InMemoryPreferencesDataStore()).visitTime.first())
        }

    @Test
    fun aChoiceIsKeptForItsDraft() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            DataStoreRouteVisitTimeRepository(store).set("draft-1", saturday)

            val reopened = DataStoreRouteVisitTimeRepository(store)
            assertEquals(StoredVisitTime("draft-1", saturday), reopened.visitTime.first())
        }

    @Test
    fun choosingNowClearsIt() =
        runTest {
            val repository = DataStoreRouteVisitTimeRepository(InMemoryPreferencesDataStore())
            repository.set("draft-1", saturday)

            repository.set("draft-1", null)

            assertNull(repository.visitTime.first())
        }

    @Test
    fun theValueIsStoredUnderItsDocumentedName() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            DataStoreRouteVisitTimeRepository(store).set("draft-1", saturday)

            assertEquals("draft-1|2026-10-10|13:00", store.data.first()[stringPreferencesKey("route_visit_time")])
        }

    @Test
    fun aMalformedValueReadsAsNoChoice() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[stringPreferencesKey("route_visit_time")] = "draft-1|tomorrow|1pm" }

            assertNull(DataStoreRouteVisitTimeRepository(store).visitTime.first())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
