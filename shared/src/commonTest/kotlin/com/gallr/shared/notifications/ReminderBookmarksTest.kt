package com.gallr.shared.notifications

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.FakeBookmarkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderBookmarksTest {
    private val bookmarks = FakeBookmarkRepository(setOf("ex1"))
    private val accountRead = MutableStateFlow<Set<String>?>(null)

    private fun reminderBookmarks(auth: AuthState): ReminderBookmarks =
        ReminderBookmarks.forAccount(
            bookmarks = bookmarks,
            authState = MutableStateFlow(auth),
            accountBookmarksRead = accountRead,
        )

    @Test
    fun `a guest's saves are known at once`() =
        runTest {
            assertEquals(setOf("ex1"), reminderBookmarks(AuthState.Anonymous).knownIds())
        }

    @Test
    fun `nothing is known while the session is being restored`() =
        runTest {
            assertNull(reminderBookmarks(AuthState.Loading).knownIds())
        }

    @Test
    fun `a signed-in account's saves are unknown until they have been read`() =
        runTest {
            val signedIn = reminderBookmarks(AuthState.Authenticated(GallrUser("u1", "하나", null)))
            assertNull(signedIn.knownIds())

            accountRead.value = setOf("ex1")

            assertEquals(setOf("ex1"), signedIn.knownIds())
        }

    @Test
    fun `an account read as empty is known to have no saves`() =
        runTest {
            val empty = FakeBookmarkRepository(emptySet())
            accountRead.value = emptySet()
            val signedIn =
                ReminderBookmarks.forAccount(
                    bookmarks = empty,
                    authState = MutableStateFlow(AuthState.Authenticated(GallrUser("u1", "하나", null))),
                    accountBookmarksRead = accountRead,
                )

            assertEquals(emptySet(), signedIn.knownIds())
        }
}
