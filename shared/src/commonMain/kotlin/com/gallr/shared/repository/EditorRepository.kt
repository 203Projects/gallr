package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor

interface EditorRepository {
    /**
     * Returns Result.success(Editor) when an active editor exists.
     * Returns Result.success(null) when no active editor exists (normal state).
     * Returns Result.failure on network or parse error; UI treats both as
     * "no editor" per spec (silent fail).
     */
    suspend fun getActiveGuestEditor(): Result<Editor?>
}
