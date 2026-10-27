package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.BookmarkDto
import com.gallr.shared.data.network.dto.BookmarkInsert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudBookmarkRepository(
    private val supabaseClient: SupabaseClient,
) {
    private val bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    private val loaded = MutableStateFlow(false)

    fun observeBookmarkedIds(): Flow<Set<String>> = bookmarkedIds.asStateFlow()

    /** Whether the signed-in account's bookmarks have been read since sign-in; until then the set is a placeholder. */
    val hasLoaded: StateFlow<Boolean> = loaded.asStateFlow()

    /** A new sign-in begins: the set held now belongs to no one until [refresh] reads the account's own. */
    fun markStale() {
        loaded.value = false
    }

    suspend fun refresh() {
        val bookmarks =
            supabaseClient.postgrest
                .from("bookmarks")
                .select()
                .decodeList<BookmarkDto>()
        bookmarkedIds.value = bookmarks.map { it.exhibitionId }.toSet()
        loaded.value = true
    }

    suspend fun addBookmark(exhibitionId: String) {
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(BookmarkInsert(exhibitionId = exhibitionId)) { onConflict = "user_id,exhibition_id" }
        bookmarkedIds.value = bookmarkedIds.value + exhibitionId
    }

    suspend fun removeBookmark(exhibitionId: String) {
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { eq("exhibition_id", exhibitionId) } }
        bookmarkedIds.value = bookmarkedIds.value - exhibitionId
    }

    fun optimisticAdd(exhibitionId: String) {
        bookmarkedIds.value = bookmarkedIds.value + exhibitionId
    }

    fun optimisticRemove(exhibitionId: String) {
        bookmarkedIds.value = bookmarkedIds.value - exhibitionId
    }

    suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in bookmarkedIds.value

    suspend fun clearAll() {
        val ids = bookmarkedIds.value.toList()
        if (ids.isEmpty()) return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { isIn("exhibition_id", ids) } }
        bookmarkedIds.value = emptySet()
    }

    suspend fun bulkInsert(exhibitionIds: Set<String>) {
        if (exhibitionIds.isEmpty()) return
        val inserts = exhibitionIds.map { BookmarkInsert(exhibitionId = it) }
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(inserts) { onConflict = "user_id,exhibition_id" }
        bookmarkedIds.value = bookmarkedIds.value + exhibitionIds
    }
}
