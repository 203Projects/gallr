package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow

interface ProfileNudgeRepository {
    fun observeProfileNudgeShown(): Flow<Boolean>

    suspend fun setProfileNudgeShown()
}
