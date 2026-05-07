package com.gallr.shared.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BookmarkDto(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("exhibition_id") val exhibitionId: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class BookmarkInsert(
    @SerialName("exhibition_id") val exhibitionId: String,
)
