@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/**
 * BookmarkRepository wrapper that delegates to local DataStore (anonymous)
 * or cloud Supabase (authenticated) based on the current auth state.
 *
 * Local and cloud bookmarks are completely isolated. When authenticated,
 * only cloud bookmarks are used. Local bookmarks are for anonymous users only.
 */
class SyncBookmarkRepository(
    private val localRepository: BookmarkRepositoryImpl,
    private val cloudRepository: CloudBookmarkRepository,
    private val authState: StateFlow<AuthState>,
) : BookmarkRepository {

    private val isAuthenticated: Boolean
        get() = authState.value is AuthState.Authenticated

    private var mutationListener: (suspend () -> Unit)? = null

    override fun setMutationListener(listener: suspend () -> Unit) {
        mutationListener = listener
        // Avoid double-firing: when authenticated we fire from this wrapper;
        // when anonymous, BookmarkRepositoryImpl already fires after its own
        // mutations. Clear any listener on the local repo to prevent duplicate
        // notifications when the same listener is registered with both.
        localRepository.setMutationListener {}
    }

    override fun observeBookmarkedIds(): Flow<Set<String>> =
        authState.flatMapLatest { state ->
            when (state) {
                is AuthState.Authenticated -> cloudRepository.observeBookmarkedIds()
                else -> localRepository.observeBookmarkedIds()
            }
        }

    override suspend fun addBookmark(exhibitionId: String) {
        if (isAuthenticated) {
            cloudRepository.optimisticAdd(exhibitionId)
            try {
                cloudRepository.addBookmark(exhibitionId)
            } catch (_: Exception) {
                // Network failure — optimistic update keeps UI responsive
            }
        } else {
            localRepository.addBookmark(exhibitionId)
        }
        mutationListener?.invoke()
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        if (isAuthenticated) {
            cloudRepository.optimisticRemove(exhibitionId)
            try {
                cloudRepository.removeBookmark(exhibitionId)
            } catch (_: Exception) {
                // Network failure — optimistic update keeps UI responsive
            }
        } else {
            localRepository.removeBookmark(exhibitionId)
        }
        mutationListener?.invoke()
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean =
        if (isAuthenticated) cloudRepository.isBookmarked(exhibitionId)
        else localRepository.isBookmarked(exhibitionId)

    override suspend fun clearAll() {
        if (isAuthenticated) {
            try {
                cloudRepository.clearAll()
            } catch (_: Exception) {
                // Network failure — don't crash; bookmarks remain server-side
            }
        } else {
            localRepository.clearAll()
        }
        mutationListener?.invoke()
    }

    /**
     * One-time migration of anonymous bookmarks to cloud on first login,
     * then refresh cloud state. The bulk insert may fail (RLS policy,
     * network) but refresh must still run so the user sees their
     * cloud bookmarks. After migration, local bookmarks are cleared.
     */
    suspend fun migrateLocalToCloud() {
        val localIds = localRepository.observeBookmarkedIds().first()
        if (localIds.isNotEmpty()) {
            try {
                cloudRepository.bulkInsert(localIds)
                localRepository.clearAll()
            } catch (e: Exception) {
                println("WARN [SyncBookmarkRepository] Local→cloud migration failed (RLS or network): ${e.message}")
            }
        }
        refreshCloudWithRetry()
        // After migration the bookmark set may have changed substantially —
        // notify so notifications can reconcile against the new cloud set.
        mutationListener?.invoke()
    }

    /**
     * Refresh cloud bookmarks with retry on failure.
     */
    suspend fun refreshCloudWithRetry(maxAttempts: Int = 3) {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                cloudRepository.refresh()
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    delay(1000L * (attempt + 1))
                }
            }
        }
        println("WARN [SyncBookmarkRepository] Cloud refresh failed after $maxAttempts attempts: ${lastError?.message}")
    }
}
