package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.repository.HiddenThoughtAuthorRepository
import com.gallr.shared.thoughts.HiddenAuthor
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → 숨긴 사용자: the people whose 감상 this device hides, in the order they were hidden. */
data class HiddenAuthorsUiState(
    val authors: List<HiddenAuthor> = emptyList(),
    val hasLoaded: Boolean = false,
    val unhideFailed: Boolean = false,
)

/** Lists and un-hides 감상 authors on this device (spec 103 FR-024); the same for guests and accounts. */
class HiddenAuthorsViewModel(
    private val hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository,
) : ViewModel() {
    private val unhideFailed = MutableStateFlow(false)

    val state: StateFlow<HiddenAuthorsUiState> =
        combine(hiddenThoughtAuthorRepository.hiddenAuthors, unhideFailed) { authors, failed ->
            HiddenAuthorsUiState(authors = authors, hasLoaded = true, unhideFailed = failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HiddenAuthorsUiState())

    /** 숨기기 해제. The store logs its own write failure (hidden_author_write_failed). */
    fun unhide(userId: String) {
        unhideFailed.value = false
        viewModelScope.launch {
            runSuspendCatching { hiddenThoughtAuthorRepository.unhide(userId) }
                .onFailure { unhideFailed.value = true }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { HiddenAuthorsViewModel(hiddenThoughtAuthorRepository) } }
    }
}
