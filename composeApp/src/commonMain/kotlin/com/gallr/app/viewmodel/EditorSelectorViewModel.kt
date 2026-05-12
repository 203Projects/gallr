package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.repository.EditorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** All-editors state for the selector screen. */
sealed class EditorSelectorState {
    data object Loading : EditorSelectorState()
    data class Success(
        val active: List<Editor>,
        val past: List<Editor>,
        val exhibitionCounts: Map<String, Int>,
    ) : EditorSelectorState()
    data class Error(val message: String) : EditorSelectorState()
}

class EditorSelectorViewModel(
    private val editorRepository: EditorRepository,
    private val tabsViewModel: TabsViewModel,
) : ViewModel() {

    private val _editorsResult = MutableStateFlow<Result<List<Editor>>?>(null)

    /** Combined state: editors split + per-editor exhibition counts. */
    val state: StateFlow<EditorSelectorState> = combine(
        _editorsResult,
        tabsViewModel.filteredExhibitions,
    ) { editorsResult, _ ->
        when {
            editorsResult == null -> EditorSelectorState.Loading
            editorsResult.isFailure -> EditorSelectorState.Error(
                editorsResult.exceptionOrNull()?.message ?: "Unknown error"
            )
            else -> {
                val editors = editorsResult.getOrThrow()
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val (active, past) = editors.partition { it.isCurrentlyActive(today) }
                val allExhibitions = (tabsViewModel.filteredExhibitions.value
                    as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
                val counts = editors.associate { editor ->
                    editor.id to allExhibitions.count { it.editorId == editor.id }
                }
                EditorSelectorState.Success(active = active, past = past, exhibitionCounts = counts)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSelectorState.Loading)

    val language: StateFlow<AppLanguage> = tabsViewModel.language

    init {
        loadEditors()
    }

    fun loadEditors() {
        viewModelScope.launch {
            _editorsResult.value = editorRepository.getAllEditors()
        }
    }

    companion object {
        fun factory(
            editorRepository: EditorRepository,
            tabsViewModel: TabsViewModel,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorSelectorViewModel(editorRepository, tabsViewModel)
            }
        }
    }
}
