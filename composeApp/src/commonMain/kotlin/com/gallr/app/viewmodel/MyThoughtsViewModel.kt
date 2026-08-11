package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyThoughtsViewModel(
    private val userId: String,
    private val thoughtRepository: ThoughtRepository,
) : ViewModel() {
    private val log = AppLog.tagged("MyThoughtsViewModel")
    private val _uiState = MutableStateFlow(ThoughtListUiState())
    val uiState: StateFlow<ThoughtListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return

        loadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, loadFailed = false) }
                runSuspendCatching {
                    require(userId.isNotBlank()) { "Authenticated user ID is missing" }
                    thoughtRepository.getUserThoughts(userId)
                }.onSuccess { thoughts ->
                    _uiState.update {
                        it.copy(
                            thoughts = thoughts,
                            isLoading = false,
                            hasLoaded = true,
                            loadFailed = false,
                        )
                    }
                }.onFailure { error ->
                    log.error("load_thoughts", error)
                    _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
    }

    fun deleteThought(thoughtId: String) {
        if (_uiState.value.isLoading || _uiState.value.mutatingThoughtId != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(mutatingThoughtId = thoughtId, mutationFailed = false)
            }
            runSuspendCatching { thoughtRepository.deleteThought(thoughtId) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            thoughts = state.thoughts.filterNot { it.id == thoughtId },
                            mutatingThoughtId = null,
                        )
                    }
                }.onFailure { error ->
                    log.warn("delete_thought", error)
                    _uiState.update {
                        it.copy(mutatingThoughtId = null, mutationFailed = true)
                    }
                }
        }
    }

    companion object {
        fun factory(
            userId: String,
            thoughtRepository: ThoughtRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MyThoughtsViewModel(
                        userId = userId,
                        thoughtRepository = thoughtRepository,
                    )
                }
            }
    }
}
