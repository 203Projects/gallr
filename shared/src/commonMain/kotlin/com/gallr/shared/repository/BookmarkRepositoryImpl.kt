package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val BOOKMARKED_IDS_KEY = stringSetPreferencesKey("bookmarked_exhibition_ids")
private val PROFILE_NUDGE_SHOWN_KEY = booleanPreferencesKey("profile_nudge_shown")

class BookmarkRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : BookmarkRepository,
    ProfileNudgeRepository {
    private var mutationListener: (suspend () -> Unit)? = null

    override fun setMutationListener(listener: suspend () -> Unit) {
        mutationListener = listener
    }

    override fun observeBookmarkedIds(): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[BOOKMARKED_IDS_KEY] ?: emptySet() }

    override suspend fun addBookmark(exhibitionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[BOOKMARKED_IDS_KEY] ?: emptySet()
            prefs[BOOKMARKED_IDS_KEY] = current + exhibitionId
        }
        mutationListener?.invoke()
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[BOOKMARKED_IDS_KEY] ?: emptySet()
            prefs[BOOKMARKED_IDS_KEY] = current - exhibitionId
        }
        mutationListener?.invoke()
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean =
        (dataStore.data.first()[BOOKMARKED_IDS_KEY] ?: emptySet()).contains(exhibitionId)

    override suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.remove(BOOKMARKED_IDS_KEY) }
        mutationListener?.invoke()
    }

    override fun observeProfileNudgeShown(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PROFILE_NUDGE_SHOWN_KEY] ?: false }

    override suspend fun setProfileNudgeShown() {
        dataStore.edit { prefs -> prefs[PROFILE_NUDGE_SHOWN_KEY] = true }
    }
}
