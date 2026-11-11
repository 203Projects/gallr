package com.gallr.shared.data.network

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, io.github.jan.supabase.annotations.SupabaseInternal::class)
class AuthDeeplinkTest {
    @BeforeTest
    fun prepareDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `browser sign-in starts a PKCE flow that returns to the app callback`() =
        runTest {
            val fixture = Fixture()
            assertEquals(FlowType.PKCE, fixture.client.auth.config.flowType)

            val authorizeUrl = fixture.client.auth.getOAuthUrl(Google)

            assertTrue("code_challenge=" in authorizeUrl, authorizeUrl)
            assertTrue("redirect_to=com.gallr.app%3A%2F%2Flogin-callback" in authorizeUrl, authorizeUrl)
            assertNotNull(
                fixture.client.auth.codeVerifierCache
                    .loadCodeVerifier(),
            )
            handleAuthDeeplink(fixture.client, "com.gallr.app://login-callback?code=code")
            assertEquals("accepted-access", fixture.client.auth.currentAccessTokenOrNull())
            fixture.client.close()
        }

    @Test
    fun `unbound tokens and malformed callbacks cannot establish a session`() =
        runTest {
            val rejected =
                listOf(
                    "com.gallr.app://login-callback#access_token=attacker&refresh_token=attacker",
                    "com.gallr.app://login-callback?code=code#access_token=attacker&refresh_token=attacker",
                    "https://evil.example/login-callback?code=code",
                    "com.gallr.app://login-callback.evil?code=code",
                    "com.gallr.app://user@login-callback?code=code",
                    "com.gallr.app://login-callback:99?code=code",
                    "com.gallr.app://login-callback/other?code=code",
                    "com.gallr.app://login-callback?code=one&code=two",
                    "com.gallr.app://login-callback?code=one&%63ode=two",
                    "com.gallr.app://login-callback?code=",
                    "com.gallr.app://login-callback",
                )
            for (url in rejected) {
                val fixture = Fixture()
                fixture.client.auth.codeVerifierCache
                    .saveCodeVerifier("local-verifier")
                handleAuthDeeplink(fixture.client, url)
                assertEquals(0, fixture.requests, url)
                assertNull(fixture.client.auth.currentSessionOrNull(), url)
                fixture.client.close()
            }
        }

    @Test
    fun `code requires a locally initiated PKCE flow and is consumed once`() =
        runTest {
            val fixture = Fixture()
            val url = "com.gallr.app://login-callback?code=code"
            handleAuthDeeplink(fixture.client, url)
            assertEquals(0, fixture.requests)
            fixture.client.auth.codeVerifierCache
                .saveCodeVerifier("local-verifier")
            handleAuthDeeplink(fixture.client, url)
            assertEquals(1, fixture.requests)
            assertEquals("accepted-access", fixture.client.auth.currentAccessTokenOrNull())
            assertNull(
                fixture.client.auth.codeVerifierCache
                    .loadCodeVerifier(),
            )
            handleAuthDeeplink(fixture.client, url)
            assertEquals(1, fixture.requests)
            fixture.client.close()
        }

    @Test
    fun `a callback never replaces the signed-in session`() =
        runTest {
            val fixture = Fixture()
            fixture.client.auth.importSession(
                UserSession(
                    accessToken = "signed-in-access",
                    refreshToken = "signed-in-refresh",
                    expiresIn = 3600,
                    tokenType = "bearer",
                ),
            )
            fixture.client.auth.codeVerifierCache
                .saveCodeVerifier("local-verifier")

            handleAuthDeeplink(fixture.client, "com.gallr.app://login-callback?code=code")

            assertEquals(0, fixture.requests)
            assertEquals("signed-in-access", fixture.client.auth.currentAccessTokenOrNull())
            fixture.client.close()
        }

    private class Fixture {
        var requests = 0
        val client =
            createSupabaseClient("https://project.example", "test-key") {
                coroutineDispatcher = Dispatchers.Unconfined
                httpEngine =
                    MockEngine {
                        requests += 1
                        respond(
                            """{"access_token":"accepted-access","refresh_token":"accepted-refresh","token_type":"bearer","expires_in":3600}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                install(Auth) {
                    useGallrSignInCallback()
                    autoLoadFromStorage = false
                    autoSetupPlatform = false
                    autoSaveToStorage = false
                    alwaysAutoRefresh = false
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                }
            }.also { it.auth.setSessionStatus(SessionStatus.NotAuthenticated(false)) }
    }
}
