package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 101: whether a signed-in account's bookmarks are known yet. The taste sheet's first-run gate and the lifting
 * of 덜 보기 hides both wait on it, so it must settle on every way out of a sign-in sync.
 */
class SyncBookmarkSettleTest {
    private val signedIn = MutableStateFlow<AuthState>(AuthState.Authenticated(GallrUser("u1", "하나", null)))

    @Test
    fun `a sign in sync is stale until the account bookmarks are read`() =
        runTest {
            val cloud = cloudRepository(status = HttpStatusCode.OK, body = """[{"exhibition_id":"ex-1"}]""")
            cloud.refresh()
            cloud.markStale()
            assertFalse(cloud.hasSettled.value)

            SyncBookmarkRepository(BookmarkRepositoryImpl(InMemoryPreferencesDataStore()), cloud, signedIn)
                .migrateLocalToCloud()

            assertTrue(cloud.hasSettled.value)
            assertEquals(setOf("ex-1"), cloud.observeBookmarkedIds().first())
        }

    @Test
    fun `retries that all fail still settle the account`() =
        runTest {
            val cloud = cloudRepository(status = HttpStatusCode.InternalServerError, body = """{"message":"down"}""")
            val sync = SyncBookmarkRepository(BookmarkRepositoryImpl(InMemoryPreferencesDataStore()), cloud, signedIn)

            sync.migrateLocalToCloud()

            assertTrue(cloud.hasSettled.value)
        }

    @Test
    fun `a local store that fails before the read still settles the account`() =
        runTest {
            val cloud = cloudRepository(status = HttpStatusCode.OK, body = "[]")
            val sync = SyncBookmarkRepository(BookmarkRepositoryImpl(FailingPreferencesDataStore()), cloud, signedIn)

            assertFailsWith<IllegalStateException> { sync.migrateLocalToCloud() }

            assertTrue(cloud.hasSettled.value)
        }

    @Test
    fun `a cancelled sign in sync stays stale`() =
        runTest {
            val cloud = cloudRepository(status = HttpStatusCode.OK, body = "[]")
            val sync = SyncBookmarkRepository(BookmarkRepositoryImpl(SuspendedPreferencesDataStore()), cloud, signedIn)

            val job = launch { sync.migrateLocalToCloud() }
            runCurrent()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(cloud.hasSettled.value)
        }

    @Test
    fun `signing out forgets the account bookmarks and its settle`() =
        runTest {
            val cloud = cloudRepository(status = HttpStatusCode.OK, body = """[{"exhibition_id":"ex-1"}]""")
            cloud.refresh()

            cloud.forgetAccount()

            assertFalse(cloud.hasSettled.value)
            assertTrue(cloud.observeBookmarkedIds().first().isEmpty())
        }

    private fun cloudRepository(
        status: HttpStatusCode,
        body: String,
    ): CloudBookmarkRepository =
        CloudBookmarkRepository(
            createSupabaseClient(supabaseUrl = "https://example.supabase.co", supabaseKey = "test-key") {
                httpEngine =
                    MockEngine {
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                install(Postgrest)
            },
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class SuspendedPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { awaitCancellation() }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            awaitCancellation()
    }

    private class FailingPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IllegalStateException("unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IllegalStateException("unwritable")
    }
}
