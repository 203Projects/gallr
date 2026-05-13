package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor

interface EditorRepository {
    /**
     * Returns Result.success with all editors (active and past).
     * Sorted active-first then by active_from desc. The 'gallr-editors'
     * seed row is promoted to position 0 of the active subset.
     * Returns Result.failure on network/parse error.
     */
    suspend fun getAllEditors(): Result<List<Editor>>

    /**
     * Returns Result.success(Editor) for the matching slug.
     * Returns Result.success(null) when no editor matches.
     * Returns Result.failure on network/parse error.
     */
    suspend fun getEditorById(id: String): Result<Editor?>
}
