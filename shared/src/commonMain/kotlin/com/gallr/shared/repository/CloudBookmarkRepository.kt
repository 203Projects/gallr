package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.BookmarkDto
import com.gallr.shared.data.network.dto.BookmarkInsert
import com.gallr.shared.data.network.jwtStringClaim
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The signed-in account's bookmarks. Everything held is tagged with the account it was read or saved for, and is
 * visible only while the Supabase session belongs to that account: another account signed in on the same device never
 * observes it, however late the app's sign-in or sign-out handling runs, and a read or save that completes after the
 * account changed is dropped.
 */
class CloudBookmarkRepository(
    private val supabaseClient: SupabaseClient,
) {
    private data class AccountBookmarks(
        val accountId: String,
        val ids: Set<String> = emptySet(),
        /** The account's bookmarks as last read from the server; null until a read succeeds. */
        val read: Set<String>? = null,
        /** Saves made on this device: the visitor's own acts, never part of what the account brought. */
        val savedHere: Set<String> = emptySet(),
        val settled: Boolean = false,
    ) {
        fun withSave(exhibitionId: String) =
            copy(ids = ids + exhibitionId, read = read?.minus(exhibitionId), savedHere = savedHere + exhibitionId)
    }

    private val held = MutableStateFlow<AccountBookmarks?>(null)

    /** What is held, while it belongs to the account signed in now. */
    private val signedInBookmarks: Flow<AccountBookmarks?> =
        combine(supabaseClient.auth.sessionStatus, held) { status, bookmarks ->
            bookmarks?.takeIf { it.accountId == status.accountId() }
        }

    fun observeBookmarkedIds(): Flow<Set<String>> = signedInBookmarks.map { it?.ids.orEmpty() }.distinctUntilChanged()

    /**
     * Whether the signed-in account's bookmarks are as known as they will be for now: read since sign-in, or given
     * up on after retries (the set may then be short, as an offline device's always is). Until then the set is a
     * placeholder that says nothing about the account.
     */
    val hasSettled: Flow<Boolean> = signedInBookmarks.map { it?.settled == true }.distinctUntilChanged()

    /**
     * The account's bookmarks as last read from the server since sign-in; null until a read succeeds. Unlike
     * [hasSettled], bookmarks given up on after retries ([settleWithoutRead]) do not count: nothing of the account's
     * arrived. Published before the set changes, so what a read brings is known before the list shows it.
     */
    val readBookmarkedIds: Flow<Set<String>?> = signedInBookmarks.map { it?.read }.distinctUntilChanged()

    /** A sign-in's sync begins: the set held now says nothing about the account until [refresh] reads it. */
    fun markStale() {
        updateSignedIn { it.copy(settled = false) }
    }

    /** Reading failed after retries; the account's set stays as it is until the next sync reads it. */
    fun settleWithoutRead() {
        updateSignedIn { it.copy(settled = true) }
    }

    /** Signed out: drops what is held. Isolation does not wait on this; it only frees the previous account's set. */
    fun forgetAccount() {
        held.value = null
    }

    suspend fun refresh() {
        val accountId = signedInAccountId() ?: return
        // Row-level security already limits the read to the session's account; the filter also keeps a session that
        // changed while the request was being sent from returning another account's rows under this one's key.
        val ids =
            supabaseClient.postgrest
                .from("bookmarks")
                .select { filter { eq("user_id", accountId) } }
                .decodeList<BookmarkDto>()
                .map { it.exhibitionId }
                .toSet()
        update(accountId) { it.copy(read = ids - it.savedHere) }
        update(accountId) { it.copy(ids = ids, settled = true) }
    }

    suspend fun addBookmark(exhibitionId: String) {
        val accountId = signedInAccountId() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(BookmarkInsert(exhibitionId = exhibitionId)) { onConflict = "user_id,exhibition_id" }
        update(accountId) { it.withSave(exhibitionId) }
    }

    suspend fun removeBookmark(exhibitionId: String) {
        val accountId = signedInAccountId() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { eq("exhibition_id", exhibitionId) } }
        update(accountId) { it.copy(ids = it.ids - exhibitionId) }
    }

    fun optimisticAdd(exhibitionId: String) {
        updateSignedIn { it.withSave(exhibitionId) }
    }

    fun optimisticRemove(exhibitionId: String) {
        updateSignedIn { it.copy(ids = it.ids - exhibitionId) }
    }

    suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in signedInIds()

    suspend fun clearAll() {
        val accountId = signedInAccountId() ?: return
        val ids = signedInIds().toList()
        if (ids.isEmpty()) return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { isIn("exhibition_id", ids) } }
        update(accountId) { it.copy(ids = emptySet()) }
    }

    suspend fun bulkInsert(exhibitionIds: Set<String>) {
        val accountId = signedInAccountId() ?: return
        if (exhibitionIds.isEmpty()) return
        val inserts = exhibitionIds.map { BookmarkInsert(exhibitionId = it) }
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(inserts) { onConflict = "user_id,exhibition_id" }
        update(accountId) { it.copy(ids = it.ids + exhibitionIds) }
    }

    private fun signedInAccountId(): String? =
        supabaseClient.auth.sessionStatus.value
            .accountId()

    private fun signedInIds(): Set<String> =
        held.value
            ?.takeIf { it.accountId == signedInAccountId() }
            ?.ids
            .orEmpty()

    private fun updateSignedIn(change: (AccountBookmarks) -> AccountBookmarks) {
        signedInAccountId()?.let { update(it, change) }
    }

    /**
     * Applies [change] to [accountId]'s bookmarks, starting them afresh when another account's are held, and only
     * while [accountId] is still signed in: a result that arrives after the account changed is dropped.
     */
    private fun update(
        accountId: String,
        change: (AccountBookmarks) -> AccountBookmarks,
    ) {
        held.update { bookmarks ->
            if (accountId != signedInAccountId()) {
                bookmarks
            } else {
                change(bookmarks?.takeIf { it.accountId == accountId } ?: AccountBookmarks(accountId))
            }
        }
    }
}

/**
 * The session's account: its user when the session carries one, else the access token's subject (a session imported
 * from a sign-in link carries no user). Only a key for what this device holds; the server authorises every request.
 */
private fun SessionStatus.accountId(): String? {
    val session = (this as? SessionStatus.Authenticated)?.session ?: return null
    return session.user?.id?.takeIf { it.isNotBlank() }
        ?: jwtStringClaim(session.accessToken, "sub")?.takeIf { it.isNotBlank() }
}
