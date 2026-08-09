package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Thought

data class ThoughtListUiState(
    val thoughts: List<Thought> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val mutatingThoughtId: String? = null,
    val mutationFailed: Boolean = false,
)
