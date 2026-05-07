package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeBookmarkRepository(initial: Set<String> = emptySet()) : BookmarkRepository {
    private val state = MutableStateFlow(initial)
    private var listener: (suspend () -> Unit)? = null

    override fun setMutationListener(listener: suspend () -> Unit) {
        this.listener = listener
    }

    override fun observeBookmarkedIds(): Flow<Set<String>> = state.asStateFlow()

    override suspend fun addBookmark(exhibitionId: String) {
        state.value = state.value + exhibitionId
        listener?.invoke()
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        state.value = state.value - exhibitionId
        listener?.invoke()
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in state.value

    override suspend fun clearAll() {
        state.value = emptySet()
        listener?.invoke()
    }
}
