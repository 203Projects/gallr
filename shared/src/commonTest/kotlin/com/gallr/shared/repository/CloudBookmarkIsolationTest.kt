package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One device, two accounts: the bookmarks held for one account must never be observable while another is signed in,
 * whatever order the app's sign-in and sign-out handling runs in (security audit #372, finding 4).
 */
class CloudBookmarkIsolationTest {
    private var status = HttpStatusCode.OK
    private var rows = """[{"user_id":"account-a","exhibition_id":"private-a"}]"""
    private val requests = mutableListOf<Url>()
    private var beforeResponse: suspend () -> Unit = {}

    private val client =
        cloudBookmarkTestClient { request ->
            requests += request.url
            beforeResponse()
            val body = if (request.method == HttpMethod.Get) rows else "[]"
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    private val repository = CloudBookmarkRepository(client)

    @Test
    fun `switching straight to another account shows none of the previous account bookmarks`() =
        runTest {
            client.signIn("account-a")
            repository.refresh()
            assertEquals(setOf("private-a"), repository.observeBookmarkedIds().first())

            // No signed-out state in between, and the app has not yet reacted to the switch.
            client.signIn("account-b")

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
            assertFalse(repository.isBookmarked("private-a"))
            assertNull(repository.readBookmarkedIds.first())
            assertFalse(repository.hasSettled.first())
        }

    @Test
    fun `a failed read after a switch leaves the previous account bookmarks hidden`() =
        runTest {
            client.signIn("account-a")
            repository.refresh()
            client.signIn("account-b")
            status = HttpStatusCode.InternalServerError
            val sync =
                SyncBookmarkRepository(
                    BookmarkRepositoryImpl(InMemoryPreferencesDataStore()),
                    repository,
                    MutableStateFlow(AuthState.Authenticated(GallrUser("account-b", "B", null))),
                )

            sync.refreshCloudWithRetry(maxAttempts = 1)

            assertTrue(repository.hasSettled.first())
            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
            assertNull(repository.readBookmarkedIds.first())
        }

    @Test
    fun `signing in as another account before sign out is handled shows none of the previous bookmarks`() =
        runTest {
            client.signIn("account-a")
            repository.refresh()

            // forgetAccount() is the app's sign-out reaction; here it never gets the chance to run.
            client.signOut()
            client.signIn("account-b")

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
            assertFalse(repository.hasSettled.first())
        }

    @Test
    fun `a read that completes after a switch is not kept for the next account`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            beforeResponse = {
                entered.complete(Unit)
                release.await()
            }
            client.signIn("account-a")
            val read = async { repository.refresh() }
            entered.await()

            client.signIn("account-b")
            release.complete(Unit)
            read.await()

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
            assertNull(repository.readBookmarkedIds.first())
        }

    @Test
    fun `a save that completes after a switch is not kept for the next account`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            beforeResponse = {
                entered.complete(Unit)
                release.await()
            }
            client.signIn("account-a")
            val save = async { repository.addBookmark("private-a") }
            entered.await()

            client.signIn("account-b")
            release.complete(Unit)
            save.await()

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
        }

    @Test
    fun `a read asks only for the signed in account rows`() =
        runTest {
            client.signIn("account-a")

            repository.refresh()

            assertEquals("eq.account-a", requests.single().parameters["user_id"])
        }

    @Test
    fun `a session without a user is keyed by its token subject`() =
        runTest {
            client.signIn(accountId = null, accessToken = "header.eyJzdWIiOiJhY2NvdW50LWEifQ.signature")
            repository.optimisticAdd("saved-by-a")
            assertEquals(setOf("saved-by-a"), repository.observeBookmarkedIds().first())

            client.signIn(accountId = null, accessToken = "header.eyJzdWIiOiJhY2NvdW50LWIifQ.signature")

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
        }

    @Test
    fun `a session with no readable account holds nothing`() =
        runTest {
            client.signIn(accountId = null, accessToken = "malformed")

            repository.optimisticAdd("unowned")

            assertEquals(emptySet(), repository.observeBookmarkedIds().first())
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
