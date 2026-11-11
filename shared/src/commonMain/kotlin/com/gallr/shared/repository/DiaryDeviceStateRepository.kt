package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow

/** Diary state kept on this device only: never synced, never cleared on sign-out. Writes may throw. */
interface DiaryDeviceStateRepository {
    /** The ended-saved count the visitor last closed the strip at; zero until they close it. */
    val dismissedEndedSavedCount: Flow<Int>

    suspend fun dismissEndedSavedStrip(count: Int)
}
