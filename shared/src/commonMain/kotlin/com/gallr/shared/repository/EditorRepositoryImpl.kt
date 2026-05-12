package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.EditorApiClient

class EditorRepositoryImpl(
    private val apiClient: EditorApiClient,
) : EditorRepository {

    override suspend fun getActiveGuestEditor(): Result<Editor?> =
        runCatching { apiClient.fetchActiveGuestEditor() }
}
