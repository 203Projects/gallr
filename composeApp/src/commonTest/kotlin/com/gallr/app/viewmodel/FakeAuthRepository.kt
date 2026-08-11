package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.OAuthProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeAuthRepository(
    var signUpResult: Result<Unit> = Result.success(Unit),
    var signInResult: Result<Unit> = Result.success(Unit),
    var resetResult: Result<Unit> = Result.success(Unit),
    var oauthResult: Result<Unit> = Result.success(Unit),
    var signOutResult: Result<Unit> = Result.success(Unit),
    var deleteAccountResult: Result<Unit> = Result.success(Unit),
) : AuthRepository {
    val signUps = mutableListOf<Pair<String, String>>()
    val signIns = mutableListOf<Pair<String, String>>()
    val passwordResets = mutableListOf<String>()
    val oauthProviders = mutableListOf<OAuthProvider>()
    var signOutCount = 0
        private set
    var deleteAccountCount = 0
        private set

    override fun observeAuthState(): Flow<AuthState> = flowOf(AuthState.Anonymous)

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ) {
        signUpResult.getOrThrow()
        signUps += email to password
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        signInResult.getOrThrow()
        signIns += email to password
    }

    override suspend fun resetPassword(email: String) {
        resetResult.getOrThrow()
        passwordResets += email
    }

    override suspend fun signInWithOAuth(provider: OAuthProvider) {
        oauthResult.getOrThrow()
        oauthProviders += provider
    }

    override suspend fun signOut() {
        signOutResult.getOrThrow()
        signOutCount += 1
    }

    override suspend fun deleteAccount() {
        deleteAccountResult.getOrThrow()
        deleteAccountCount += 1
    }
}
