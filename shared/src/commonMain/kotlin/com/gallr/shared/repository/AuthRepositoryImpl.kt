package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : AuthRepository {

    override fun observeAuthState(): Flow<AuthState> =
        supabaseClient.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    // session.user is null on iOS — always fetch from API
                    val user = try {
                        supabaseClient.auth.retrieveUserForCurrentSession()
                    } catch (_: Exception) {
                        status.session.user
                    }

                    val meta = user?.userMetadata
                    val displayName = (meta?.get("full_name") ?: meta?.get("name"))
                        ?.toString()
                        ?.removeSurrounding("\"")
                        ?: ""
                    val avatarUrl = (meta?.get("avatar_url") ?: meta?.get("picture"))
                        ?.toString()
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotBlank() && it != "null" }

                    AuthState.Authenticated(
                        GallrUser(
                            id = user?.id ?: "",
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                        )
                    )
                }
                is SessionStatus.NotAuthenticated -> AuthState.Anonymous
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.RefreshFailure -> AuthState.Anonymous
            }
        }

    override suspend fun signUpWithEmail(email: String, password: String) {
        supabaseClient.auth.signUpWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun resetPassword(email: String) {
        supabaseClient.auth.resetPasswordForEmail(email)
    }

    override suspend fun signOut() {
        supabaseClient.auth.signOut()
    }

    override suspend fun deleteAccount() {
        // TODO: Implement Supabase Edge Function for proper account deletion.
        // Current limitation: supabase-kt does not expose client-side deleteUser().
        // For now, delete user data (thoughts, bookmarks, profile) via Postgrest,
        // then sign out. The auth.users row persists until an Edge Function is added.
        val userId = supabaseClient.auth.currentUserOrNull()?.id
            ?: try { supabaseClient.auth.retrieveUserForCurrentSession()?.id } catch (_: Exception) { null }
        if (userId != null) {
            try {
                // RLS scopes each delete to the current user; explicit user_id filter for clarity
                supabaseClient.postgrest.from("thoughts").delete { filter { eq("user_id", userId) } }
                supabaseClient.postgrest.from("bookmarks").delete { filter { eq("user_id", userId) } }
                supabaseClient.postgrest.from("profiles").delete { filter { eq("id", userId) } }
            } catch (_: Exception) {
                // Best effort — data deletion may fail if already signed out
            }
        }
        supabaseClient.auth.signOut()
    }
}
