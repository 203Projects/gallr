package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Editor
import com.gallr.shared.repository.EditorRepository

/**
 * In-memory EditorRepository for ViewModel tests.
 * Each test sets the responses it needs; the fake serves them on demand.
 */
class FakeEditorRepository(
    var allEditorsResult: Result<List<Editor>> = Result.success(emptyList()),
    var editorByIdResults: Map<String, Result<Editor?>> = emptyMap(),
) : EditorRepository {

    var getAllEditorsCallCount: Int = 0
        private set
    var getEditorByIdCalls: MutableList<String> = mutableListOf()
        private set

    override suspend fun getAllEditors(): Result<List<Editor>> {
        getAllEditorsCallCount++
        return allEditorsResult
    }

    override suspend fun getEditorById(id: String): Result<Editor?> {
        getEditorByIdCalls.add(id)
        return editorByIdResults[id] ?: Result.success(null)
    }
}
