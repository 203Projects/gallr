package com.gallr.shared.repository

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(SupabaseInternal::class)
class CloudBookmarkIsolationTest {
    @Test
    fun `A bookmarks never appear under B or guest including refresh failure`() =
        runTest {
            val fixture = Fixture()
            fixture.signIn("A")
            fixture.repository.optimisticAdd("private-A")
            assertEquals(setOf("private-A"), fixture.repository.observeBookmarkedIds().first())
            fixture.signIn("B")
            assertEquals(emptySet(), fixture.repository.observeBookmarkedIds().first())
            assertFalse(fixture.repository.isBookmarked("private-A"))
            fixture.fail = true
            runCatching { fixture.repository.refresh() }
            assertEquals(emptySet(), fixture.repository.observeBookmarkedIds().first())
            fixture.client.auth.setSessionStatus(SessionStatus.NotAuthenticated(false))
            assertEquals(emptySet(), fixture.repository.observeBookmarkedIds().first())
            fixture.client.close()
        }

    @Test
    fun `late A response cannot overwrite B cache`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                Fixture {
                    entered.complete(Unit)
                    release.await()
                }
            fixture.signIn("A")
            val refresh = async { fixture.repository.refresh() }
            entered.await()
            fixture.signIn("B")
            fixture.repository.optimisticAdd("B-bookmark")
            release.complete(Unit)
            refresh.await()
            assertEquals(setOf("B-bookmark"), fixture.repository.observeBookmarkedIds().first())
            fixture.client.close()
        }

    @Test
    fun `iOS session without user uses subject and rejects missing identity`() =
        runTest {
            val fixture = Fixture()
            // base64url of {"sub":"A"}, deliberately without padding.
            fixture.signIn(null, "header.eyJzdWIiOiJBIn0.signature")
            fixture.repository.optimisticAdd("A-bookmark")
            assertEquals(setOf("A-bookmark"), fixture.repository.observeBookmarkedIds().first())
            fixture.signIn(null, "malformed")
            fixture.repository.optimisticAdd("unscoped")
            assertEquals(emptySet(), fixture.repository.observeBookmarkedIds().first())
            fixture.client.close()
        }

    private class Fixture(
        val beforeResponse: suspend () -> Unit = {},
    ) {
        var fail = false
        val client =
            createSupabaseClient("https://project.example", "test-key") {
                coroutineDispatcher = Dispatchers.Unconfined
                httpEngine =
                    MockEngine {
                        beforeResponse()
                        respond(
                            if (fail) "{}" else """[{"user_id":"A","exhibition_id":"private-A"}]""",
                            status = if (fail) HttpStatusCode.Forbidden else HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                install(Auth) {
                    autoLoadFromStorage = false
                    autoSaveToStorage = false
                    autoSetupPlatform = false
                    alwaysAutoRefresh = false
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                }
                install(Postgrest)
            }
        val repository = CloudBookmarkRepository(client)

        fun signIn(
            user: String?,
            token: String = "test-access",
        ) {
            client.auth.setSessionStatus(
                SessionStatus.Authenticated(
                    UserSession(
                        accessToken = token,
                        refreshToken = "test-refresh",
                        tokenType = "bearer",
                        expiresIn = 3600,
                        user = user?.let { UserInfo(id = it, aud = "authenticated") },
                    ),
                    SessionSource.External,
                ),
            )
        }
    }
}
