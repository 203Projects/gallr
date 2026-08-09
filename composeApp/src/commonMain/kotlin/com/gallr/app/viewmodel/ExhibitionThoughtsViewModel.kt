package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Thought
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExhibitionThoughtsUiState(
    val thoughts: List<Thought> = emptyList(),
    val ownPendingThought: Thought? = null,
    val hasUserThought: Boolean = false,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val mutatingThoughtId: String? = null,
    val mutationFailed: Boolean = false,
)

private data class ExhibitionThoughtsSnapshot(
    val thoughts: List<Thought>,
    val ownPendingThought: Thought?,
)

class ExhibitionThoughtsViewModel(
    private val exhibitionId: String,
    private val currentUserId: String?,
    private val thoughtRepository: ThoughtRepository,
) : ViewModel() {
    private val log = AppLog.tagged("ExhibitionThoughtsViewModel")
    private val _uiState = MutableStateFlow(ExhibitionThoughtsUiState())
    val uiState: StateFlow<ExhibitionThoughtsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return

        loadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, loadFailed = false) }
                runSuspendCatching { loadSnapshot() }
                    .onSuccess { snapshot ->
                        _uiState.value =
                            ExhibitionThoughtsUiState(
                                thoughts = snapshot.thoughts,
                                ownPendingThought = snapshot.ownPendingThought,
                                hasUserThought = snapshot.hasUserThought(),
                                hasLoaded = true,
                            )
                    }.onFailure { error ->
                        log.error("load_exhibition_thoughts", error)
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
                        val thoughts = state.thoughts.filterNot { it.id == thoughtId }
                        val ownPendingThought = state.ownPendingThought?.takeUnless { it.id == thoughtId }
                        state.copy(
                            thoughts = thoughts,
                            ownPendingThought = ownPendingThought,
                            hasUserThought = hasUserThought(thoughts, ownPendingThought),
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

    suspend fun submitThought(content: String) {
        thoughtRepository.submitThought(exhibitionId, content)
        refresh()
    }

    private suspend fun loadSnapshot(): ExhibitionThoughtsSnapshot {
        require(exhibitionId.isNotBlank()) { "Exhibition ID is missing" }

        val thoughts = thoughtRepository.getThoughtsForExhibition(exhibitionId)
        val ownPendingThought =
            currentUserId
                ?.let { thoughtRepository.getUserThoughtForExhibition(exhibitionId) }
                ?.takeUnless { it.isApproved }
        return ExhibitionThoughtsSnapshot(
            thoughts = thoughts,
            ownPendingThought = ownPendingThought,
        )
    }

    private fun ExhibitionThoughtsSnapshot.hasUserThought(): Boolean = hasUserThought(thoughts, ownPendingThought)

    private fun hasUserThought(
        thoughts: List<Thought>,
        ownPendingThought: Thought?,
    ): Boolean =
        currentUserId != null && (
            thoughts.any { it.userId == currentUserId } || ownPendingThought != null
        )

    companion object {
        fun factory(
            exhibitionId: String,
            currentUserId: String?,
            thoughtRepository: ThoughtRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExhibitionThoughtsViewModel(
                        exhibitionId = exhibitionId,
                        currentUserId = currentUserId,
                        thoughtRepository = thoughtRepository,
                    )
                }
            }
    }
}
