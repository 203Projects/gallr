package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String = "",
    @SerialName("is_admin") val isAdmin: Boolean = false,
) {
    fun toDomain(): Profile = Profile(
        id = id,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        isAdmin = isAdmin,
    )
}
