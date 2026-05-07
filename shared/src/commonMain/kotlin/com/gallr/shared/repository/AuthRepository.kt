package com.gallr.shared.repository

import com.gallr.shared.data.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>
    suspend fun signUpWithEmail(email: String, password: String)
    suspend fun signInWithEmail(email: String, password: String)
    suspend fun resetPassword(email: String)
    suspend fun signOut()
    suspend fun deleteAccount()
}
