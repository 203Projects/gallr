package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.TasteSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Spec 101 FR-003 and FR-016: picks, the skip and dismissals stay on this device. */
class DataStoreTastePreferenceRepositoryTest {
    private val seed =
        TasteSeed(
            termIds = setOf("style:minimalist", "medium:photography"),
            exhibitionIds = setOf("ex-1"),
        )
    private val dismissal =
        TasteDismissal(
            exhibitionId = "ex-9",
            closingDate = LocalDate(2026, 11, 30),
            termIds = setOf("theme:body"),
            artistIds = setOf("artist-1"),
        )

    @Test
    fun startsEmpty() =
        runTest {
            assertEquals(
                TastePickState.EMPTY,
                DataStoreTastePreferenceRepository(InMemoryPreferencesDataStore()).state.first(),
            )
        }

    @Test
    fun aSeedSurvivesReopening() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            DataStoreTastePreferenceRepository(store).saveSeed(seed)

            assertEquals(seed, DataStoreTastePreferenceRepository(store).state.first().seed)
        }

    @Test
    fun skippingIsRememberedAndSavingASeedClearsIt() =
        runTest {
            val repository = DataStoreTastePreferenceRepository(InMemoryPreferencesDataStore())
            repository.markSkipped()
            assertTrue(repository.state.first().skipped)

            repository.saveSeed(seed)

            assertFalse(repository.state.first().skipped)
        }

    @Test
    fun dismissalsRoundTripAndCanBeRemovedOrReleased() =
        runTest {
            val repository = DataStoreTastePreferenceRepository(InMemoryPreferencesDataStore())
            repository.dismiss(dismissal)
            repository.dismiss(dismissal.copy(exhibitionId = "ex-10"))
            assertEquals(
                setOf("ex-9", "ex-10"),
                repository.state
                    .first()
                    .dismissals.hiddenExhibitionIds,
            )

            repository.removeDismissals(setOf("ex-10"))
            repository.releaseTerm("theme:body")

            val dismissals = repository.state.first().dismissals
            assertEquals(setOf("ex-9"), dismissals.hiddenExhibitionIds)
            assertEquals(0, dismissals.termCount("theme:body"))
            assertEquals(1, dismissals.artistCount("artist-1"))
        }

    @Test
    fun expiringDropsClosedExhibitions() =
        runTest {
            val repository = DataStoreTastePreferenceRepository(InMemoryPreferencesDataStore())
            repository.dismiss(dismissal)

            repository.expire(LocalDate(2026, 12, 1))

            assertTrue(
                repository.state
                    .first()
                    .dismissals.records
                    .isEmpty(),
            )
        }

    @Test
    fun valuesAreStoredUnderTheirDocumentedNames() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreTastePreferenceRepository(store)
            repository.saveSeed(seed)
            repository.markSkipped()
            repository.dismiss(dismissal)

            val preferences = store.data.first()
            assertNotNull(preferences[stringPreferencesKey("taste_seed")])
            assertEquals(true, preferences[booleanPreferencesKey("taste_pick_skipped")])
            assertNotNull(preferences[stringPreferencesKey("taste_dismissals")])
        }

    @Test
    fun malformedValuesReadAsEmpty() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit {
                it[stringPreferencesKey("taste_seed")] = "{not json"
                it[stringPreferencesKey("taste_dismissals")] = "[1,2"
            }

            assertEquals(TastePickState.EMPTY, DataStoreTastePreferenceRepository(store).state.first())
        }

    @Test
    fun clearingEveryPickCountsAsAnAnswerSoTheSheetStaysClosed() =
        runTest {
            val repository = DataStoreTastePreferenceRepository(InMemoryPreferencesDataStore())
            repository.saveSeed(seed)

            repository.saveSeed(TasteSeed.EMPTY)

            val state = repository.state.first()
            assertEquals(TasteSeed.EMPTY, state.seed)
            assertTrue(state.skipped)
        }

    @Test
    fun oneUnreadableHideCostsOnlyItself() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit {
                it[stringPreferencesKey("taste_dismissals")] =
                    """[{"exhibitionId":"ex-9","closingDate":"2026-11-30"},{"exhibitionId":"ex-10"}]"""
            }

            val dismissals = DataStoreTastePreferenceRepository(store).state.first().dismissals

            assertEquals(setOf("ex-9"), dismissals.hiddenExhibitionIds)
        }

    @Test
    fun anExpiryWithNothingToDoLeavesAnUnreadableValueAsItIs() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[stringPreferencesKey("taste_dismissals")] = "[1,2" }

            DataStoreTastePreferenceRepository(store).expire(LocalDate(2026, 12, 1))

            assertEquals("[1,2", store.data.first()[stringPreferencesKey("taste_dismissals")])
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
