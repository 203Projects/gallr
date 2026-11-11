package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.BookmarkDto
import com.gallr.shared.data.network.dto.BookmarkInsert
import com.gallr.shared.data.network.jwtStringClaim
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
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
 *
 * A session waiting for its token refresh still belongs to its account, so the account keeps its bookmarks and can
 * save and remove on this device; requests to the server fail with [BookmarkSessionRefreshPendingException] until the
 * refresh succeeds, as they would without a network.
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

    /** The account a session waiting for refresh was resolved to, for the very status it was resolved for. */
    private class PendingSessionAccount(
        val status: SessionStatus,
        val accountId: String?,
    )

    private val sessionStatus = supabaseClient.auth.sessionStatus
    private val held = MutableStateFlow<AccountBookmarks?>(null)
    private val pendingSessionAccount = MutableStateFlow<PendingSessionAccount?>(null)

    /** What is held, while it belongs to the account signed in now. */
    private val signedInBookmarks: Flow<AccountBookmarks?> =
        combine(sessionStatus, held) { status, bookmarks ->
            bookmarks?.takeIf { it.accountId == accountIdOf(status) }
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
    suspend fun markStale() {
        updateSignedIn { it.copy(settled = false) }
    }

    /** Reading failed after retries; the account's set stays as it is until the next sync reads it. */
    suspend fun settleWithoutRead() {
        updateSignedIn { it.copy(settled = true) }
    }

    /** Signed out: drops what is held. Isolation does not wait on this; it only frees the previous account's set. */
    fun forgetAccount() {
        held.value = null
    }

    suspend fun refresh() {
        val accountId = requestAccountId() ?: return
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
        val accountId = requestAccountId() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(BookmarkInsert(exhibitionId = exhibitionId)) { onConflict = "user_id,exhibition_id" }
        update(accountId) { it.withSave(exhibitionId) }
    }

    suspend fun removeBookmark(exhibitionId: String) {
        val accountId = requestAccountId() ?: return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { eq("exhibition_id", exhibitionId) } }
        update(accountId) { it.copy(ids = it.ids - exhibitionId) }
    }

    suspend fun optimisticAdd(exhibitionId: String) {
        updateSignedIn { it.withSave(exhibitionId) }
    }

    suspend fun optimisticRemove(exhibitionId: String) {
        updateSignedIn { it.copy(ids = it.ids - exhibitionId) }
    }

    suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in signedInIds()

    suspend fun clearAll() {
        val accountId = requestAccountId() ?: return
        val ids = signedInIds().toList()
        if (ids.isEmpty()) return
        supabaseClient.postgrest
            .from("bookmarks")
            .delete { filter { isIn("exhibition_id", ids) } }
        update(accountId) { it.copy(ids = emptySet()) }
    }

    suspend fun bulkInsert(exhibitionIds: Set<String>) {
        val accountId = requestAccountId() ?: return
        if (exhibitionIds.isEmpty()) return
        val inserts = exhibitionIds.map { BookmarkInsert(exhibitionId = it) }
        supabaseClient.postgrest
            .from("bookmarks")
            .upsert(inserts) { onConflict = "user_id,exhibition_id" }
        update(accountId) { it.copy(ids = it.ids + exhibitionIds) }
    }

    /**
     * The account whose requests may be sent now, or null with no account signed in. A session waiting for refresh
     * holds no access token, and a request would go out under the anonymous key, which the server cannot attribute to
     * the account; it fails instead, as a request that cannot reach the server does.
     */
    private suspend fun requestAccountId(): String? {
        val status = sessionStatus.value
        if (status is SessionStatus.Authenticated) return status.session.accountId()
        if (accountIdOf(status) != null) throw BookmarkSessionRefreshPendingException()
        return null
    }

    private suspend fun signedInIds(): Set<String> {
        val accountId = accountIdOf(sessionStatus.value) ?: return emptySet()
        return held.value
            ?.takeIf { it.accountId == accountId }
            ?.ids
            .orEmpty()
    }

    private suspend fun updateSignedIn(change: (AccountBookmarks) -> AccountBookmarks) {
        accountIdOf(sessionStatus.value)?.let { update(it, change) }
    }

    /**
     * Applies [change] to [accountId]'s bookmarks, starting them afresh when another account's are held, and only
     * while [accountId] is still signed in: a result that arrives after the account changed is dropped. The change is
     * applied only under the session status it was checked against; when the status moves in between, it is checked
     * again.
     */
    private suspend fun update(
        accountId: String,
        change: (AccountBookmarks) -> AccountBookmarks,
    ) {
        while (true) {
            val status = sessionStatus.value
            if (accountIdOf(status) != accountId) return
            var applied = false
            held.update { bookmarks ->
                applied = sessionStatus.value === status
                if (applied) {
                    change(bookmarks?.takeIf { it.accountId == accountId } ?: AccountBookmarks(accountId))
                } else {
                    bookmarks
                }
            }
            if (applied) return
        }
    }

    /**
     * The account [status] belongs to. supabase-kt (3.8, 3.9) keeps a session whose refresh failed for a network or
     * server error in storage and retries it, reporting [SessionStatus.RefreshFailure] once it has expired and ending a
     * start-up whose refresh failed as `NotAuthenticated(isSignOut = false)`; neither status carries the session, so
     * its account is the stored session's. A sign-out, a revoked refresh token or a session the server no longer knows
     * deletes the stored session and reports `NotAuthenticated(isSignOut = true)`, which belongs to no account.
     */
    private suspend fun accountIdOf(status: SessionStatus): String? =
        when {
            status is SessionStatus.Authenticated -> status.session.accountId()
            status.awaitsRefresh() -> storedSessionAccountId(status)
            else -> null
        }

    /** Read once for each status: a retried refresh reports a new one, so storage is read again when it might differ. */
    private suspend fun storedSessionAccountId(status: SessionStatus): String? {
        pendingSessionAccount.value?.takeIf { it.status === status }?.let { return it.accountId }
        val accountId =
            supabaseClient.auth.sessionManager
                .loadSessionOrNull()
                ?.accountId()
        pendingSessionAccount.value = PendingSessionAccount(status, accountId)
        return accountId
    }
}

/** A bookmark request was not sent: the session is waiting for its token refresh, so it cannot act for the account. */
class BookmarkSessionRefreshPendingException : IllegalStateException("The session is waiting for its token refresh")

/**
 * The session's account: its user when the session carries one, else the access token's subject (a session imported
 * from a sign-in link carries no user). Only a key for what this device holds; the server authorises every request.
 */
private fun UserSession.accountId(): String? =
    user?.id?.takeIf { it.isNotBlank() }
        ?: jwtStringClaim(accessToken, "sub")?.takeIf { it.isNotBlank() }

private fun SessionStatus.awaitsRefresh(): Boolean =
    this is SessionStatus.RefreshFailure || (this is SessionStatus.NotAuthenticated && !isSignOut)
