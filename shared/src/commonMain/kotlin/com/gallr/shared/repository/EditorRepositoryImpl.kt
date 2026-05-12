package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.EditorApiClient

class EditorRepositoryImpl(
    private val apiClient: EditorApiClient,
) : EditorRepository {

    override suspend fun getAllEditors(): Result<List<Editor>> =
        runCatching { apiClient.fetchAllEditors() }

    override suspend fun getEditorById(id: String): Result<Editor?> =
        runCatching { apiClient.fetchEditorById(id) }
}
