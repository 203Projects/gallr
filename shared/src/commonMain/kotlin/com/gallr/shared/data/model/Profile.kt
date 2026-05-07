package com.gallr.shared.data.model

data class Profile(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String,
    val isAdmin: Boolean,
)
