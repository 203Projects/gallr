package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.diary.VisitFeeling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataStoreVisitRepositoryTest {
    @Test
    fun `archive starts empty`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())

            assertTrue(repository.observeVisits().first().isEmpty())
        }

    @Test
    fun `bulk add persists newest visits first`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())

            repository.addVisits(
                listOf(
                    visit("older", "2026-08-01T00:00:00Z"),
                    visit("newer", "2026-08-12T00:00:00Z"),
                ),
            )

            assertEquals(listOf("newer", "older"), repository.observeVisits().first().map { it.exhibitionId })
        }

    @Test
    fun `archive survives repository reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val firstRepository = DataStoreVisitRepository(store)
            val expected = visit("persistent", "2026-08-13T00:00:00Z")
            firstRepository.addVisits(listOf(expected))

            val reconstructed = DataStoreVisitRepository(store)

            assertEquals(listOf(expected), reconstructed.observeVisits().first())
        }

    @Test
    fun `adding an existing exhibition is idempotent and preserves original snapshot`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())
            val original = visit("same", "2026-08-01T00:00:00Z")
            val replacement =
                visit("same", "2026-08-13T00:00:00Z").copy(
                    clientRecordId = "replacement-record",
                    snapshot = visit("changed", "2026-08-13T00:00:00Z").snapshot,
                )

            repository.addVisits(listOf(original))
            repository.addVisits(listOf(replacement))

            assertEquals(listOf(original), repository.observeVisits().first())
        }

    @Test
    fun `remove deletes only the requested exhibition`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())
            repository.addVisits(
                listOf(
                    visit("keep", "2026-08-01T00:00:00Z"),
                    visit("remove", "2026-08-02T00:00:00Z"),
                ),
            )

            repository.removeVisit("remove")

            assertEquals(listOf("keep"), repository.observeVisits().first().map { it.exhibitionId })
        }

    @Test
    fun `malformed persisted archive fails instead of becoming empty`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val key = stringPreferencesKey("my_gallr_exhibition_visits_v1")
            store.updateData { preferences ->
                preferences.toMutablePreferences().apply { this[key] = "{not-json" }
            }

            val repository = DataStoreVisitRepository(store)

            assertFails { repository.observeVisits().first() }
        }

    @Test
    fun `save entry creates an entry and then replaces it in place`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())
            repository.addVisits(listOf(visit("other", "2026-08-01T00:00:00Z")))
            val first =
                visit("entry", "2026-08-02T00:00:00Z").copy(
                    visitDate = LocalDate(2026, 8, 2),
                    feelingWire = VisitFeeling.LOVED.wire,
                    note = "첫 메모",
                    entryUpdatedAt = Instant.parse("2026-08-02T00:00:00Z"),
                )

            repository.saveEntry(first)
            assertEquals(listOf(first, visit("other", "2026-08-01T00:00:00Z")), repository.observeVisits().first())

            val edited = first.copy(note = "고친 메모", entryUpdatedAt = Instant.parse("2026-08-03T00:00:00Z"))
            repository.saveEntry(edited)

            assertEquals(listOf("entry", "other"), repository.observeVisits().first().map { it.exhibitionId })
            assertEquals(edited, repository.observeVisits().first().first())
        }

    @Test
    fun `delete entry removes only that entry`() =
        runTest {
            val repository = DataStoreVisitRepository(InMemoryPreferencesDataStore())
            repository.addVisits(listOf(visit("keep", "2026-08-01T00:00:00Z"), visit("drop", "2026-08-02T00:00:00Z")))

            repository.deleteEntry("drop")

            assertEquals(listOf("keep"), repository.observeVisits().first().map { it.exhibitionId })
        }

    @Test
    fun `a visit archive written by 1_15 is still read`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val key = stringPreferencesKey("my_gallr_exhibition_visits_v1")
            store.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[key] =
                        """{"schemaVersion":1,"visits":[{"clientRecordId":"record-old","exhibitionId":"old",""" +
                        """"snapshot":{"nameKo":"전시","nameEn":"Exhibition","venueNameKo":"갤러리",""" +
                        """"venueNameEn":"Gallery","openingDate":"2026-08-01","closingDate":"2026-08-31",""" +
                        """"coverImageUrl":null,"galleryId":null},"createdAt":"2026-08-13T00:00:00Z"}]}"""
                }
            }

            val visit = DataStoreVisitRepository(store).observeVisits().first().single()

            assertEquals("old", visit.exhibitionId)
            assertEquals(null, visit.visitDate)
            assertEquals(null, visit.note)
        }

    private fun visit(
        exhibitionId: String,
        createdAt: String,
    ) = ExhibitionVisit(
        clientRecordId = "record-$exhibitionId",
        exhibitionId = exhibitionId,
        snapshot =
            ExhibitionVisitSnapshot(
                nameKo = "전시 $exhibitionId",
                nameEn = "Exhibition $exhibitionId",
                venueNameKo = "갤러리",
                venueNameEn = "Gallery",
                openingDate = LocalDate(2026, 8, 1),
                closingDate = LocalDate(2026, 8, 31),
                coverImageUrl = "https://example.com/$exhibitionId.jpg",
            ),
        createdAt = Instant.parse(createdAt),
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
