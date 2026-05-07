package com.gallr.shared.repository

import com.gallr.shared.data.model.Profile

interface ProfileRepository {
    suspend fun getProfile(userId: String): Profile?
    suspend fun updateProfile(userId: String, displayName: String, bio: String)
    suspend fun ensureProfileExists(userId: String, displayName: String, avatarUrl: String?)
    suspend fun uploadAvatar(userId: String, imageBytes: ByteArray): String
}
