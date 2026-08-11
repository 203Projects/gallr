package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.EditorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

class EditorDetailViewModel(
    private val editorId: String,
    private val editorRepository: EditorRepository,
    allExhibitionsFlow: StateFlow<ExhibitionListState>,
    val language: StateFlow<AppLanguage>,
    private val todayProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
    },
) : ViewModel() {
    private val log = AppLog.tagged("EditorDetailViewModel")

    private val _editor = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = _editor

    val exhibitions: StateFlow<List<Exhibition>> =
        allExhibitionsFlow
            .map { state ->
                val today = todayProvider()
                (state as? ExhibitionListState.Success)
                    ?.exhibitions
                    ?.filter { it.editorId == editorId && it.isVisibleInCatalog(today) }
                    ?: emptyList()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadEditor()
    }

    fun loadEditor() {
        viewModelScope.launch {
            editorRepository
                .getEditorById(editorId)
                .onSuccess { _editor.value = it }
                .onFailure { error ->
                    log.error("load_editor", error)
                    _editor.value = null
                }
        }
    }

    companion object {
        fun factory(
            editorId: String,
            editorRepository: EditorRepository,
            tabsViewModel: TabsViewModel,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EditorDetailViewModel(
                        editorId = editorId,
                        editorRepository = editorRepository,
                        allExhibitionsFlow = tabsViewModel.allExhibitions,
                        language = tabsViewModel.language,
                    )
                }
            }
    }
}
