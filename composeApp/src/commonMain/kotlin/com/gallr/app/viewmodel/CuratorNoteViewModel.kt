package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.CuratorNoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

private val noteLog = AppLog.tagged("CuratorNoteViewModel")

sealed interface CuratorNoteUiState {
    data object Loading : CuratorNoteUiState

    /** The newest approved note, or null when the exhibition has none; only a note renders a block (FR-013). */
    data class Ready(
        val note: CuratorNote?,
    ) : CuratorNoteUiState

    data object Error : CuratorNoteUiState
}

/** The curator's note of one exhibition for the detail screen (spec 090 US3). */
class CuratorNoteViewModel(
    exhibitionId: String,
    repository: CuratorNoteRepository,
) : ViewModel() {
    val state: StateFlow<CuratorNoteUiState> =
        flow {
            emit(
                repository.getNote(exhibitionId).fold(
                    onSuccess = { CuratorNoteUiState.Ready(it) },
                    onFailure = { error ->
                        noteLog.warn("curator_note_load_failed", error)
                        CuratorNoteUiState.Error
                    },
                ),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CuratorNoteUiState.Loading)

    companion object {
        fun factory(
            exhibitionId: String,
            repository: CuratorNoteRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { CuratorNoteViewModel(exhibitionId, repository) }
            }
    }
}
