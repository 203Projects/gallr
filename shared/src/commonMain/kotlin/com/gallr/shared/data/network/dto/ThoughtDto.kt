package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Thought
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThoughtDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("exhibition_id") val exhibitionId: String,
    val content: String,
    @SerialName("is_approved") val isApproved: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // Joined from profiles table
    val profiles: ProfileDto? = null,
) {
    fun toDomain(): Thought = Thought(
        id = id,
        userId = userId,
        exhibitionId = exhibitionId,
        content = content,
        isApproved = isApproved,
        createdAt = createdAt,
        updatedAt = updatedAt,
        authorDisplayName = profiles?.displayName ?: "",
        authorAvatarUrl = profiles?.avatarUrl,
    )
}

@Serializable
data class ThoughtInsert(
    @SerialName("exhibition_id") val exhibitionId: String,
    val content: String,
)
