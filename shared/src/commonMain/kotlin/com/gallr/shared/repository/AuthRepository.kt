package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import kotlinx.coroutines.flow.Flow

enum class OAuthProvider {
    GOOGLE,
    APPLE,
}

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>

    suspend fun signUpWithEmail(
        email: String,
        password: String,
    )

    suspend fun signInWithEmail(
        email: String,
        password: String,
    )

    suspend fun resetPassword(email: String)

    suspend fun signInWithOAuth(provider: OAuthProvider)

    suspend fun signOut()

    suspend fun deleteAccount()
}

interface AccountDeletionSource {
    suspend fun deleteAccount(accessToken: String)
}

sealed class AccountDeletionException(
    message: String,
) : IllegalStateException(message)

class AccountDeletionAuthenticationRequiredException :
    AccountDeletionException("An authenticated session is required")

class AccountDeletionReauthenticationRequiredException : AccountDeletionException("A recent sign-in is required")

class AccountDeletionSupportRequiredException :
    AccountDeletionException("This operator account requires assisted deletion")

class AccountDeletionRateLimitedException : AccountDeletionException("Account deletion is temporarily rate limited")

class AccountDeletionStatusUnknownException :
    AccountDeletionException("The account deletion result could not be confirmed")

class AccountDeletionUnavailableException :
    AccountDeletionException("Authenticated account deletion is temporarily unavailable")
