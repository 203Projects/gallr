package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val authRepositoryLog = AppLog.tagged("AuthRepository")

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val accountDeletionSource: AccountDeletionSource,
) : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> =
        authStatesFrom(
            sessionStatus = supabaseClient.auth.sessionStatus,
            fetchCurrentUser = { supabaseClient.auth.retrieveUserForCurrentSession() },
            loadStoredSession = { supabaseClient.auth.sessionManager.loadSessionOrNull() },
        )

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ) {
        supabaseClient.auth.signUpWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun resetPassword(email: String) {
        supabaseClient.auth.resetPasswordForEmail(email)
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider) {
        when (provider) {
            OAuthProvider.GOOGLE -> {
                supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.Google) {
                    queryParams["prompt"] = "select_account"
                }
            }

            OAuthProvider.APPLE -> {
                supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.Apple)
            }
        }
    }

    override suspend fun signOut() {
        supabaseClient.auth.signOut()
    }

    override suspend fun deleteAccount() {
        val accessToken =
            supabaseClient.auth.currentAccessTokenOrNull()
                ?: throw AccountDeletionAuthenticationRequiredException()
        accountDeletionSource.deleteAccount(accessToken)
        // The server has confirmed Auth deletion. Clear only local session state;
        // a network sign-out would target an identity that no longer exists.
        supabaseClient.auth.clearSession()
    }
}

/**
 * Reads supabase-kt's session status as the app's [AuthState].
 *
 * When a refresh fails for a network or server error, supabase-kt 3.8 keeps the stored session and retries it
 * every few seconds: an expired session reports [SessionStatus.RefreshFailure], and a start-up refresh that fails
 * before expiry ends start-up as `NotAuthenticated(isSignOut = false)`. A sign-out, a revoked refresh token or a
 * session the server no longer knows deletes the stored session and reports `NotAuthenticated(isSignOut = true)`.
 * So only that status signs the user out here; a failed refresh keeps the last signed-in user (or, on a cold start,
 * the stored session's user) with [AuthState.Authenticated.isSessionRefreshPending] set.
 *
 * A signed-in state always carries a user id; when none can be found the state is [AuthState.Loading].
 */
internal fun authStatesFrom(
    sessionStatus: Flow<SessionStatus>,
    fetchCurrentUser: suspend () -> UserInfo,
    loadStoredSession: suspend () -> UserSession?,
): Flow<AuthState> =
    flow {
        var signedInUser: GallrUser? = null
        sessionStatus.collect { status ->
            val state = nextAuthState(status, signedInUser, fetchCurrentUser, loadStoredSession)
            // Loading here means a session whose account is not known, so the last user must not stand in for it.
            signedInUser = (state as? AuthState.Authenticated)?.user
            emit(state)
        }
    }

private suspend fun nextAuthState(
    status: SessionStatus,
    lastSignedInUser: GallrUser?,
    fetchCurrentUser: suspend () -> UserInfo,
    loadStoredSession: suspend () -> UserSession?,
): AuthState =
    when (status) {
        SessionStatus.Initializing -> {
            AuthState.Loading
        }

        is SessionStatus.Authenticated -> {
            authenticatedState(status, fetchCurrentUser, lastSignedInUser)
        }

        is SessionStatus.NotAuthenticated -> {
            if (status.isSignOut) AuthState.Anonymous else refreshPendingState(lastSignedInUser, loadStoredSession)
        }

        is SessionStatus.RefreshFailure -> {
            refreshPendingState(lastSignedInUser, loadStoredSession)
        }
    }

private suspend fun authenticatedState(
    status: SessionStatus.Authenticated,
    fetchCurrentUser: suspend () -> UserInfo,
    lastSignedInUser: GallrUser?,
): AuthState {
    // A session imported from a deep link carries no user, so the user is read from the server first.
    val user =
        fetchUserOrNull(fetchCurrentUser)?.toGallrUserOrNull()
            ?: status.session.user?.toGallrUserOrNull()
            ?: lastSignedInUser?.takeIf { status.source.continuesSession() }
    if (user == null) {
        authRepositoryLog.warn("resolve_authenticated_user")
        return AuthState.Loading
    }
    return AuthState.Authenticated(user)
}

private suspend fun refreshPendingState(
    lastSignedInUser: GallrUser?,
    loadStoredSession: suspend () -> UserSession?,
): AuthState {
    val user =
        lastSignedInUser
            ?: run {
                val storedSession = loadStoredSession() ?: return AuthState.Anonymous
                storedSession.user?.toGallrUserOrNull()
            }
    if (user == null) {
        authRepositoryLog.warn("restore_stored_user")
        return AuthState.Loading
    }
    return AuthState.Authenticated(user, isSessionRefreshPending = true)
}

private suspend fun fetchUserOrNull(fetchCurrentUser: suspend () -> UserInfo): UserInfo? =
    try {
        fetchCurrentUser()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        authRepositoryLog.warn("refresh_authenticated_user", error)
        null
    }

/** A refreshed or updated session belongs to the account already signed in; a new sign-in may not. */
private fun SessionSource.continuesSession(): Boolean =
    this is SessionSource.Refresh || this is SessionSource.UserChanged || this is SessionSource.UserIdentitiesChanged

private fun UserInfo.toGallrUserOrNull(): GallrUser? {
    if (id.isBlank()) return null
    val metadata = userMetadata
    val avatarUrl = metadata?.stringOrNull("avatar_url") ?: metadata?.stringOrNull("picture")
    return GallrUser(
        id = id,
        displayName = metadata?.stringOrNull("full_name") ?: metadata?.stringOrNull("name") ?: "",
        avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
