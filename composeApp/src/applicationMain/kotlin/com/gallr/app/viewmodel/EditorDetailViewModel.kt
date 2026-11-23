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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * One editor's page: loading, the editor's visible exhibitions, or a failed read that
 * [EditorDetailViewModel.retry] repeats.
 */
sealed interface EditorDetailUiState {
    data object Loading : EditorDetailUiState

    /** [editor] is null when the editor is not published; the list still shows. An empty list is a real empty page. */
    data class Content(
        val editor: Editor?,
        val exhibitions: List<Exhibition>,
    ) : EditorDetailUiState

    /** The editor or the catalogue could not be read. */
    data object Error : EditorDetailUiState
}

class EditorDetailViewModel(
    private val editorId: String,
    private val editorRepository: EditorRepository,
    private val allExhibitionsFlow: StateFlow<ExhibitionListState>,
    val language: StateFlow<AppLanguage>,
    private val reloadExhibitions: () -> Unit = {},
    private val todayProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
    },
) : ViewModel() {
    private val log = AppLog.tagged("EditorDetailViewModel")

    /** Null until the read answers. */
    private val editorResult = MutableStateFlow<Result<Editor?>?>(null)

    val state: StateFlow<EditorDetailUiState> =
        combine(editorResult, allExhibitionsFlow) { editor, catalogue ->
            when {
                editor == null -> {
                    EditorDetailUiState.Loading
                }

                editor.isFailure || catalogue is ExhibitionListState.Error -> {
                    EditorDetailUiState.Error
                }

                catalogue is ExhibitionListState.Success -> {
                    val today = todayProvider()
                    EditorDetailUiState.Content(
                        editor = editor.getOrNull(),
                        exhibitions =
                            catalogue.exhibitions.filter {
                                it.editorId == editorId && it.isVisibleInCatalog(today)
                            },
                    )
                }

                else -> {
                    EditorDetailUiState.Loading
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorDetailUiState.Loading)

    init {
        loadEditor()
    }

    /** Reads again whatever failed: the editor, and the shared catalogue only when it is the one in error. */
    fun retry() {
        if (editorResult.value?.isFailure == true) loadEditor()
        if (allExhibitionsFlow.value is ExhibitionListState.Error) reloadExhibitions()
    }

    private fun loadEditor() {
        editorResult.value = null
        viewModelScope.launch {
            editorResult.value =
                editorRepository.getEditorById(editorId).onFailure { error ->
                    log.error("load_editor", error)
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
                        reloadExhibitions = tabsViewModel::loadAllExhibitions,
                    )
                }
            }
    }
}
