package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EditorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EditorDetailViewModel(
    private val editorId: String,
    private val editorRepository: EditorRepository,
    private val tabsViewModel: TabsViewModel,
) : ViewModel() {

    private val _editor = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = _editor

    val exhibitions: StateFlow<List<Exhibition>> = tabsViewModel.filteredExhibitions
        .map { state ->
            (state as? ExhibitionListState.Success)?.exhibitions
                ?.filter { it.editorId == editorId }
                ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val language: StateFlow<AppLanguage> = tabsViewModel.language

    init {
        loadEditor()
    }

    fun loadEditor() {
        viewModelScope.launch {
            editorRepository.getEditorById(editorId)
                .onSuccess { _editor.value = it }
                .onFailure {
                    println("ERROR [EditorDetailViewModel] loadEditor($editorId): ${it.message}")
                    _editor.value = null
                }
        }
    }

    companion object {
        fun factory(
            editorId: String,
            editorRepository: EditorRepository,
            tabsViewModel: TabsViewModel,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorDetailViewModel(editorId, editorRepository, tabsViewModel)
            }
        }
    }
}
