package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.gallr.shared.thoughts.HiddenAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Hidden 감상 authors stay on this device (FR-024, data-model "Device-only state"). */
class DataStoreHiddenThoughtAuthorRepositoryTest {
    private val key = stringPreferencesKey("hidden_thought_authors_v1")
    private val first = HiddenAuthor("user-1", "하나", Instant.parse("2026-11-10T00:00:00Z"))
    private val second = HiddenAuthor("user-2", "둘", Instant.parse("2026-11-11T00:00:00Z"))

    @Test
    fun nobodyIsHiddenAtFirst() =
        runTest {
            assertTrue(
                DataStoreHiddenThoughtAuthorRepository(InMemoryPreferencesDataStore()).hiddenAuthors.first().isEmpty(),
            )
        }

    @Test
    fun hiddenAuthorsSurviveReopeningUnderTheFixedKeyAndShape() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreHiddenThoughtAuthorRepository(store)

            repository.hide(first)
            repository.hide(second)

            assertEquals(listOf(first, second), DataStoreHiddenThoughtAuthorRepository(store).hiddenAuthors.first())
            assertEquals(
                Json.parseToJsonElement(
                    """
                    [
                      {"user_id":"user-1","display_name":"하나","hidden_at":"2026-11-10T00:00:00Z"},
                      {"user_id":"user-2","display_name":"둘","hidden_at":"2026-11-11T00:00:00Z"}
                    ]
                    """.trimIndent(),
                ),
                Json.parseToJsonElement(store.data.first()[key]!!),
            )
        }

    @Test
    fun hidingTheSamePersonAgainKeepsOneEntryAndUnhideRemovesIt() =
        runTest {
            val repository = DataStoreHiddenThoughtAuthorRepository(InMemoryPreferencesDataStore())
            repository.hide(first)
            repository.hide(second)

            repository.hide(first.copy(displayName = "새 이름", hiddenAt = Instant.parse("2026-11-12T00:00:00Z")))
            assertEquals(listOf("user-2", "user-1"), repository.hiddenAuthors.first().map { it.userId })
            assertEquals(
                "새 이름",
                repository.hiddenAuthors
                    .first()
                    .last()
                    .displayName,
            )

            repository.unhide("user-2")
            repository.unhide("nobody")
            assertEquals(listOf("user-1"), repository.hiddenAuthors.first().map { it.userId })
        }

    @Test
    fun aMalformedStoredListReadsAsEmptyAndLogsOnlyTheExceptionType() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[key] = "{\"user_id\":\"secret-user\"" }
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            try {
                assertTrue(DataStoreHiddenThoughtAuthorRepository(store).hiddenAuthors.first().isEmpty())
            } finally {
                Logger.setLogWriters(platformLogWriter())
            }

            val line = logs.messages.single { "hidden_authors_read_failed" in it }
            assertTrue(line.startsWith("operation=hidden_authors_read_failed outcome=failure cause="))
            assertFalse("secret-user" in line)
        }

    @Test
    fun hidingSomeoneOverAMalformedListStartsAFreshList() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[key] = "not json" }
            val repository = DataStoreHiddenThoughtAuthorRepository(store)

            repository.hide(first)

            assertEquals(listOf(first), repository.hiddenAuthors.first())
        }

    private class CapturingLogWriter : LogWriter() {
        val messages = mutableListOf<String>()

        override fun log(
            severity: Severity,
            message: String,
            tag: String,
            throwable: Throwable?,
        ) {
            messages += message
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
