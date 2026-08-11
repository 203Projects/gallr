package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Profile
import com.gallr.shared.repository.ProfileRepository

internal data class ProfileUpdate(
    val userId: String,
    val displayName: String,
    val bio: String,
)

internal class FakeProfileRepository(
    var profileResult: Result<Profile?> = Result.success(null),
    var updateResult: Result<Unit> = Result.success(Unit),
    var avatarResult: Result<String> = Result.success("avatar"),
) : ProfileRepository {
    val updates = mutableListOf<ProfileUpdate>()
    val avatarUploads = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun getProfile(userId: String): Profile? = profileResult.getOrThrow()

    override suspend fun updateProfile(
        userId: String,
        displayName: String,
        bio: String,
    ) {
        updateResult.getOrThrow()
        updates += ProfileUpdate(userId, displayName, bio)
    }

    override suspend fun ensureProfileExists(
        userId: String,
        displayName: String,
        avatarUrl: String?,
    ) = Unit

    override suspend fun uploadAvatar(
        userId: String,
        imageBytes: ByteArray,
    ): String {
        val avatarUrl = avatarResult.getOrThrow()
        avatarUploads += userId to imageBytes
        return avatarUrl
    }
}
