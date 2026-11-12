package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.observability.AppLog
import com.gallr.shared.thoughts.HiddenAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON list of [HiddenAuthor]; renaming the key or the shape needs a migration. */
private val HIDDEN_THOUGHT_AUTHORS_KEY = stringPreferencesKey("hidden_thought_authors_v1")

private val HIDDEN_AUTHOR_LIST = ListSerializer(HiddenAuthor.serializer())

private val hiddenAuthorsLog = AppLog.tagged("HiddenAuthors")

/** [HiddenThoughtAuthorRepository] in the app's preferences DataStore. */
class DataStoreHiddenThoughtAuthorRepository(
    private val dataStore: DataStore<Preferences>,
) : HiddenThoughtAuthorRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val hiddenAuthors: Flow<List<HiddenAuthor>> =
        dataStore.data
            .map { it[HIDDEN_THOUGHT_AUTHORS_KEY] }
            .distinctUntilChanged()
            .map(::decode)

    override suspend fun hide(author: HiddenAuthor) =
        update { authors -> authors.filterNot { it.userId == author.userId } + author }

    override suspend fun unhide(userId: String) = update { authors -> authors.filterNot { it.userId == userId } }

    /** A malformed stored list is replaced by the new one: there is nothing in it this build could keep. */
    private suspend fun update(transform: (List<HiddenAuthor>) -> List<HiddenAuthor>) {
        try {
            dataStore.edit { preferences ->
                val next = transform(decode(preferences[HIDDEN_THOUGHT_AUTHORS_KEY]))
                if (next.isEmpty()) {
                    preferences.remove(HIDDEN_THOUGHT_AUTHORS_KEY)
                } else {
                    preferences[HIDDEN_THOUGHT_AUTHORS_KEY] = json.encodeToString(HIDDEN_AUTHOR_LIST, next)
                }
            }
        } catch (error: Exception) {
            hiddenAuthorsLog.warn("hidden_author_write_failed", error)
            throw error
        }
    }

    private fun decode(stored: String?): List<HiddenAuthor> {
        if (stored == null) return emptyList()
        return try {
            json.decodeFromString(HIDDEN_AUTHOR_LIST, stored)
        } catch (error: SerializationException) {
            hiddenAuthorsLog.warn("hidden_authors_read_failed", error)
            emptyList()
        } catch (error: IllegalArgumentException) {
            hiddenAuthorsLog.warn("hidden_authors_read_failed", error)
            emptyList()
        }
    }
}
