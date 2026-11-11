package com.gallr.shared.notifications

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.BookmarkRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** The saved exhibitions that reminders are kept for. */
interface ReminderBookmarks {
    /**
     * The saved exhibition ids, or null while they are not known yet. An unknown set must never read as "nothing
     * saved": a sync then leaves every reminder as it is.
     */
    suspend fun knownIds(): Set<String>?

    companion object {
        /**
         * The visitor's saves as the auth-aware [bookmarks] repository reports them: the device's store for a guest,
         * the account's for a signed-in visitor. A guest's are known at once. A signed-in account's are known only
         * once they have been read from the server ([accountBookmarksRead] non-null): until then the repository holds
         * a placeholder, and an offline launch would otherwise cancel every reminder. Nothing is known while the
         * session is still being restored.
         */
        fun forAccount(
            bookmarks: BookmarkRepository,
            authState: StateFlow<AuthState>,
            accountBookmarksRead: StateFlow<Set<String>?>,
        ): ReminderBookmarks =
            object : ReminderBookmarks {
                override suspend fun knownIds(): Set<String>? {
                    val known =
                        when (authState.value) {
                            AuthState.Loading -> false
                            AuthState.Anonymous -> true
                            is AuthState.Authenticated -> accountBookmarksRead.value != null
                        }
                    return if (known) bookmarks.observeBookmarkedIds().first() else null
                }
            }
    }
}
