package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.TastePreferenceRepository
import com.gallr.shared.taste.TASTE_PICK_MIN_SELECTIONS
import com.gallr.shared.taste.TasteSeed
import com.gallr.shared.taste.resolveArtTerms
import com.gallr.shared.taste.tastePickChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** How the taste sheet was opened: on its own for a new visitor, or to change existing picks. */
enum class TastePickMode {
    /** Start needs [TASTE_PICK_MIN_SELECTIONS] picks; skipping is offered. */
    FIRST_RUN,

    /** Opened from a chip or the prompt card: any selection may be saved. */
    EDIT,
}

/** The open taste sheet. */
data class TastePickSheetState(
    val mode: TastePickMode,
    val terms: List<ArtTerm>,
    val covers: List<Exhibition>,
    val selectedTermIds: Set<String>,
    val selectedExhibitionIds: Set<String>,
) {
    val selectionCount: Int get() = selectedTermIds.size + selectedExhibitionIds.size

    /** Picks still needed before a first-run start is allowed. */
    val remaining: Int get() =
        if (mode ==
            TastePickMode.EDIT
        ) {
            0
        } else {
            (TASTE_PICK_MIN_SELECTIONS - selectionCount).coerceAtLeast(0)
        }

    val canConfirm: Boolean get() = remaining == 0
}

/** Orchestrates the taste sheet (spec 101 US1): what it offers, the selection, start, save and skip. */
class TastePickViewModel(
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    private val featuredState: StateFlow<ExhibitionListState>,
    private val tastePreferences: TastePreferenceRepository,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {
    private val log = AppLog.tagged("TastePickViewModel")
    private val _sheet = MutableStateFlow<TastePickSheetState?>(null)

    /** The open sheet, or null when it is closed. */
    val sheet: StateFlow<TastePickSheetState?> = _sheet.asStateFlow()

    /** Opens the sheet with the current picks selected. */
    fun open(mode: TastePickMode) {
        viewModelScope.launch(backgroundDispatcher) {
            val catalogue = (exhibitionsState.value as? ExhibitionListState.Success)?.exhibitions.orEmpty()
            val featured = (featuredState.value as? ExhibitionListState.Success)?.exhibitions.orEmpty()
            val seed = tastePreferences.state.first().seed
            val choices = tastePickChoices(catalogue, featured, todayProvider())
            val offeredIds = choices.terms.mapTo(mutableSetOf(), ArtTerm::id)
            val pickedButNotOffered = resolveArtTerms(seed.termIds - offeredIds, catalogue)
            val coverIds = choices.covers.mapTo(mutableSetOf(), Exhibition::id)
            val pickedCoversNotOffered = catalogue.filter { it.id in seed.exhibitionIds && it.id !in coverIds }
            _sheet.value =
                TastePickSheetState(
                    mode = mode,
                    terms = choices.terms + pickedButNotOffered,
                    covers = choices.covers + pickedCoversNotOffered,
                    selectedTermIds = seed.termIds,
                    selectedExhibitionIds = seed.exhibitionIds,
                )
        }
    }

    fun toggleTerm(termId: String) =
        _sheet.update { sheet ->
            sheet?.copy(selectedTermIds = sheet.selectedTermIds.toggled(termId))
        }

    fun toggleCover(exhibitionId: String) =
        _sheet.update { sheet ->
            sheet?.copy(selectedExhibitionIds = sheet.selectedExhibitionIds.toggled(exhibitionId))
        }

    /** Saves the selection as the visitor's picks and closes the sheet. */
    fun confirm() {
        val sheet = _sheet.value ?: return
        if (!sheet.canConfirm) return
        _sheet.value = null
        write("taste_pick_save") {
            tastePreferences.saveSeed(
                TasteSeed(
                    termIds = sheet.selectedTermIds,
                    exhibitionIds = sheet.selectedExhibitionIds,
                    pickedOn = todayProvider(),
                ),
            )
        }
    }

    /** Records the skip, so the sheet never opens on its own again, and closes it. */
    fun skip() {
        if (_sheet.value == null) return
        _sheet.value = null
        write("taste_pick_skip") { tastePreferences.markSkipped() }
    }

    /** Closes the sheet without recording anything (back, or a tap outside). */
    fun close() {
        _sheet.value = null
    }

    private fun write(
        operation: String,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch(backgroundDispatcher) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn(operation, error)
            }
        }
    }

    companion object {
        fun factory(
            exhibitionsState: StateFlow<ExhibitionListState>,
            featuredState: StateFlow<ExhibitionListState>,
            tastePreferences: TastePreferenceRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TastePickViewModel(
                        exhibitionsState = exhibitionsState,
                        featuredState = featuredState,
                        tastePreferences = tastePreferences,
                    )
                }
            }
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id
