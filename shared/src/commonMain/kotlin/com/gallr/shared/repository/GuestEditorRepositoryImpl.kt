package com.gallr.shared.repository

import com.gallr.shared.data.model.GuestEditor
import com.gallr.shared.data.network.GuestEditorApiClient

class GuestEditorRepositoryImpl(
    private val apiClient: GuestEditorApiClient,
) : GuestEditorRepository {

    override suspend fun getActiveGuestEditor(): Result<GuestEditor?> =
        runCatching { apiClient.fetchActiveGuestEditor() }
}
