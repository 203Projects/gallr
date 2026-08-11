package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val authRepositoryLog = AppLog.tagged("AuthRepository")

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val accountDeletionSource: AccountDeletionSource,
) : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> =
        supabaseClient.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    // session.user is null on iOS — always fetch from API
                    val user =
                        try {
                            supabaseClient.auth.retrieveUserForCurrentSession()
                        } catch (error: Exception) {
                            authRepositoryLog.warn("refresh_authenticated_user", error)
                            status.session.user
                        }

                    val meta = user?.userMetadata
                    val displayName =
                        (meta?.get("full_name") ?: meta?.get("name"))
                            ?.toString()
                            ?.removeSurrounding("\"")
                            ?: ""
                    val avatarUrl =
                        (meta?.get("avatar_url") ?: meta?.get("picture"))
                            ?.toString()
                            ?.removeSurrounding("\"")
                            ?.takeIf { it.isNotBlank() && it != "null" }

                    AuthState.Authenticated(
                        GallrUser(
                            id = user?.id ?: "",
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                        ),
                    )
                }

                is SessionStatus.NotAuthenticated -> {
                    AuthState.Anonymous
                }

                is SessionStatus.Initializing -> {
                    AuthState.Loading
                }

                is SessionStatus.RefreshFailure -> {
                    AuthState.Anonymous
                }
            }
        }

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
