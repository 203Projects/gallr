package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.BookmarkDto
import com.gallr.shared.data.network.dto.BookmarkInsert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudBookmarkRepository(
    private val supabaseClient: SupabaseClient,
) {
    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())

    fun observeBookmarkedIds(): Flow<Set<String>> = _bookmarkedIds.asStateFlow()

    suspend fun refresh() {
        val bookmarks = supabaseClient.postgrest
            .from("bookmarks")
            .select()
            .decodeList<BookmarkDto>()
        _bookmarkedIds.value = bookmarks.map { it.exhibitionId }.toSet()
    }

    suspend fun addBookmark(exhibitionId: String) {
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(BookmarkInsert(exhibitionId = exhibitionId)) { onConflict = "user_id,exhibition_id" }
        _bookmarkedIds.value = _bookmarkedIds.value + exhibitionId
    }

    suspend fun removeBookmark(exhibitionId: String) {
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { eq("exhibition_id", exhibitionId) } }
        _bookmarkedIds.value = _bookmarkedIds.value - exhibitionId
    }

    fun optimisticAdd(exhibitionId: String) {
        _bookmarkedIds.value = _bookmarkedIds.value + exhibitionId
    }

    fun optimisticRemove(exhibitionId: String) {
        _bookmarkedIds.value = _bookmarkedIds.value - exhibitionId
    }

    suspend fun isBookmarked(exhibitionId: String): Boolean =
        exhibitionId in _bookmarkedIds.value

    suspend fun clearAll() {
        val ids = _bookmarkedIds.value.toList()
        if (ids.isEmpty()) return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { isIn("exhibition_id", ids) } }
        _bookmarkedIds.value = emptySet()
    }

    suspend fun bulkInsert(exhibitionIds: Set<String>) {
        if (exhibitionIds.isEmpty()) return
        val inserts = exhibitionIds.map { BookmarkInsert(exhibitionId = it) }
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(inserts) { onConflict = "user_id,exhibition_id" }
        _bookmarkedIds.value = _bookmarkedIds.value + exhibitionIds
    }
}
