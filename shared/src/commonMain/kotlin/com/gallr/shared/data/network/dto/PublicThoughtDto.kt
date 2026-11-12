package com.gallr.shared.data.network.dto

import com.gallr.shared.thoughts.PublicThought
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** One row of `list_exhibition_thoughts` (contracts/database.md M2); the server sends `''` for a blank name. */
@Serializable
data class PublicThoughtDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_display_name") val authorDisplayName: String = "",
    @SerialName("author_avatar_url") val authorAvatarUrl: String? = null,
    @SerialName("reported_by_me") val reportedByMe: Boolean = false,
) {
    /** Throws for a timestamp that does not parse, so the caller can leave the row out. */
    fun toDomain(): PublicThought =
        PublicThought(
            id = id,
            authorId = userId,
            content = content,
            createdAt = Instant.parse(createdAt),
            authorDisplayName = authorDisplayName,
            authorAvatarUrl = authorAvatarUrl?.takeIf { it.isNotBlank() },
            reportedByMe = reportedByMe,
        )
}
