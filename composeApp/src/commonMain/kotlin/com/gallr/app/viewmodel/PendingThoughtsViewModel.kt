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

class PendingThoughtsViewModel(
    private val thoughtRepository: ThoughtRepository,
) : ViewModel() {
    private val log = AppLog.tagged("PendingThoughtsViewModel")
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
                runSuspendCatching { thoughtRepository.getPendingThoughts() }
                    .onSuccess { thoughts ->
                        _uiState.update {
                            it.copy(
                                thoughts = thoughts,
                                isLoading = false,
                                hasLoaded = true,
                                loadFailed = false,
                            )
                        }
                    }.onFailure { error ->
                        log.error("load_pending_thoughts", error)
                        _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                    }
            }
    }

    fun approveThought(thoughtId: String) {
        mutateThought(
            thoughtId = thoughtId,
            operation = "approve_thought",
            mutation = { thoughtRepository.approveThought(thoughtId) },
        )
    }

    fun rejectThought(thoughtId: String) {
        mutateThought(
            thoughtId = thoughtId,
            operation = "reject_thought",
            mutation = { thoughtRepository.rejectThought(thoughtId) },
        )
    }

    private fun mutateThought(
        thoughtId: String,
        operation: String,
        mutation: suspend () -> Unit,
    ) {
        if (_uiState.value.isLoading || _uiState.value.mutatingThoughtId != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(mutatingThoughtId = thoughtId, mutationFailed = false)
            }
            runSuspendCatching { mutation() }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            thoughts = state.thoughts.filterNot { it.id == thoughtId },
                            mutatingThoughtId = null,
                        )
                    }
                }.onFailure { error ->
                    log.warn(operation, error)
                    _uiState.update {
                        it.copy(mutatingThoughtId = null, mutationFailed = true)
                    }
                }
        }
    }

    companion object {
        fun factory(thoughtRepository: ThoughtRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PendingThoughtsViewModel(thoughtRepository) }
            }
    }
}
