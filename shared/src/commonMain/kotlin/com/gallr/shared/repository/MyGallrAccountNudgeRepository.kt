package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow

interface MyGallrAccountNudgeRepository {
    fun observeDismissed(): Flow<Boolean>

    suspend fun dismiss()
}
