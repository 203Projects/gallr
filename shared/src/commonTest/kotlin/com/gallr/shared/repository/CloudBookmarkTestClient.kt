@file:OptIn(SupabaseInternal::class)

package com.gallr.shared.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.Dispatchers

/** A Supabase client whose session the test sets by hand and whose requests [handler] answers. */
internal fun cloudBookmarkTestClient(handler: MockRequestHandler): SupabaseClient =
    createSupabaseClient(supabaseUrl = "https://example.supabase.co", supabaseKey = "test-key") {
        coroutineDispatcher = Dispatchers.Unconfined
        httpEngine = MockEngine(handler)
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

/** Signs [accountId] in; a null id stands for a session that carries no user (one imported from a sign-in link). */
internal fun SupabaseClient.signIn(
    accountId: String?,
    accessToken: String = "test-access",
) {
    auth.setSessionStatus(
        SessionStatus.Authenticated(
            UserSession(
                accessToken = accessToken,
                refreshToken = "test-refresh",
                tokenType = "bearer",
                expiresIn = 3600,
                user = accountId?.let { UserInfo(id = it, aud = "authenticated") },
            ),
            SessionSource.External,
        ),
    )
}

internal fun SupabaseClient.signOut() {
    auth.setSessionStatus(SessionStatus.NotAuthenticated(true))
}

/**
 * The signed-in session's refresh fails for a network error once it has expired. supabase-kt then keeps the session in
 * storage, retries it, and reports [SessionStatus.RefreshFailure], whose payload carries no session.
 */
@Suppress("DEPRECATION")
internal suspend fun SupabaseClient.failSessionRefresh() {
    val session = auth.currentSessionOrNull() ?: error("No signed-in session to refresh")
    auth.sessionManager.saveSession(session)
    val offline = RefreshFailureCause.NetworkError(IllegalStateException("offline"))
    auth.setSessionStatus(SessionStatus.RefreshFailure(offline))
}

/**
 * The app starts with [accountId]'s session stored and its start-up refresh fails before the session expires:
 * supabase-kt ends start-up as `NotAuthenticated(isSignOut = false)` and keeps the stored session.
 */
internal suspend fun SupabaseClient.startWithRefreshPending(accountId: String) {
    auth.sessionManager.saveSession(
        UserSession(
            accessToken = "test-access",
            refreshToken = "test-refresh",
            tokenType = "bearer",
            expiresIn = 3600,
            user = UserInfo(id = accountId, aud = "authenticated"),
        ),
    )
    auth.setSessionStatus(SessionStatus.NotAuthenticated(isSignOut = false))
}

/** supabase-kt ends every start-up without a usable session as `NotAuthenticated(isSignOut = false)`, even with none stored. */
internal fun SupabaseClient.startWithNoStoredSession() {
    auth.setSessionStatus(SessionStatus.NotAuthenticated(isSignOut = false))
}
