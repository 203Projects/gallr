package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.EditorApiClient
import com.gallr.shared.util.runSuspendCatching

class EditorRepositoryImpl(
    private val apiClient: EditorApiClient,
) : EditorRepository {
    override suspend fun getAllEditors(): Result<List<Editor>> = runSuspendCatching { apiClient.fetchAllEditors() }

    override suspend fun getEditorById(id: String): Result<Editor?> =
        runSuspendCatching { apiClient.fetchEditorById(id) }
}
