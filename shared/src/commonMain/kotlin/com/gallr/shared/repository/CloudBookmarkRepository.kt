package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.BookmarkDto
import com.gallr.shared.data.network.dto.BookmarkInsert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

class CloudBookmarkRepository(
    private val supabaseClient: SupabaseClient,
) {
    private data class Cache(
        val owner: String? = null,
        val ids: Set<String> = emptySet(),
    )

    private val cache = MutableStateFlow(Cache())

    // Scope cached values at every read, including synchronous reads and late
    // request completions. A failed B refresh must never expose A's cache.
    fun observeBookmarkedIds(): Flow<Set<String>> =
        combine(supabaseClient.auth.sessionStatus, cache) { _, cached ->
            cached.ids.takeIf { cached.owner != null && cached.owner == currentOwner() } ?: emptySet()
        }

    private fun currentOwner(): String? {
        val session = supabaseClient.auth.currentSessionOrNull() ?: return null
        session.user
            ?.id
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // iOS sessions can omit user. This claim is only a local cache key;
        // server-side JWT verification and RLS still authorize every request.
        return runCatching {
            val payload = session.accessToken.split('.').getOrNull(1) ?: return null
            val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
            Json
                .parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString())
                .jsonObject["sub"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun currentIds(): Set<String> =
        cache.value.let { cached ->
            cached.ids.takeIf { cached.owner != null && cached.owner == currentOwner() } ?: emptySet()
        }

    private fun update(
        owner: String,
        transform: (Set<String>) -> Set<String>,
    ) {
        cache.update { cached ->
            if (owner !=
                currentOwner()
            ) {
                cached
            } else {
                Cache(
                    owner,
                    transform(cached.ids.takeIf { cached.owner == owner } ?: emptySet()),
                )
            }
        }
    }

    suspend fun refresh() {
        val owner = currentOwner() ?: return
        val bookmarks =
            supabaseClient.postgrest
                .from("bookmarks")
                .select { filter { eq("user_id", owner) } }
                .decodeList<BookmarkDto>()
        update(owner) { bookmarks.filter { it.userId == owner }.map { it.exhibitionId }.toSet() }
    }

    suspend fun addBookmark(exhibitionId: String) {
        val owner = currentOwner() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(
                BookmarkInsert(userId = owner, exhibitionId = exhibitionId),
            ) { onConflict = "user_id,exhibition_id" }
        update(owner) { it + exhibitionId }
    }

    suspend fun removeBookmark(exhibitionId: String) {
        val owner = currentOwner() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete {
                filter {
                    eq("user_id", owner)
                    eq("exhibition_id", exhibitionId)
                }
            }
        update(owner) { it - exhibitionId }
    }

    fun optimisticAdd(exhibitionId: String) {
        val owner = currentOwner() ?: return
        update(owner) { it + exhibitionId }
    }

    fun optimisticRemove(exhibitionId: String) {
        val owner = currentOwner() ?: return
        update(owner) { it - exhibitionId }
    }

    suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in currentIds()

    suspend fun clearAll() {
        val owner = currentOwner() ?: return
        val ids = currentIds().toList()
        if (ids.isEmpty()) return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete {
                filter {
                    eq("user_id", owner)
                    isIn("exhibition_id", ids)
                }
            }
        update(owner) { emptySet() }
    }

    suspend fun bulkInsert(exhibitionIds: Set<String>) {
        val owner = currentOwner() ?: return
        if (exhibitionIds.isEmpty()) return
        val inserts = exhibitionIds.map { BookmarkInsert(userId = owner, exhibitionId = it) }
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(inserts) { onConflict = "user_id,exhibition_id" }
        update(owner) { it + exhibitionIds }
    }
}
