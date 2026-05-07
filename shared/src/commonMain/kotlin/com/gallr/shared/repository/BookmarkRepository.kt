package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeBookmarkedIds(): Flow<Set<String>>
    suspend fun addBookmark(exhibitionId: String)
    suspend fun removeBookmark(exhibitionId: String)
    suspend fun isBookmarked(exhibitionId: String): Boolean
    suspend fun clearAll()

    /**
     * Register a callback fired (suspending) after every bookmark mutation
     * (add / remove / clearAll). Used by NotificationSyncService to reconcile
     * scheduled notifications against the new bookmark set.
     *
     * Replaces any previously-set listener. Pass a no-op to disable.
     */
    fun setMutationListener(listener: suspend () -> Unit)
}
