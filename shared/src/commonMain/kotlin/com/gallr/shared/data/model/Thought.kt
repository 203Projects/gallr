package com.gallr.shared.data.model

data class Thought(
    val id: String,
    val userId: String,
    val exhibitionId: String,
    val content: String,
    val isApproved: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
)
