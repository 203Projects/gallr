package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.EventRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The event page: loading, the event with its visible exhibitions, an unpublished event, or a failed read. */
sealed interface EventDetailUiState {
    data object Loading : EventDetailUiState

    data class Content(
        val event: Event,
        val exhibitions: List<Exhibition>,
        val venuesKo: List<String>,
        val venuesEn: List<String>,
    ) : EventDetailUiState

    /** The event is not published or no longer active; reading it again gives the same answer. */
    data object NotFound : EventDetailUiState

    /** The event or its exhibitions could not be read; [EventDetailViewModel.retry] reads both again. */
    data object Error : EventDetailUiState
}

class EventDetailViewModel(
    private val eventId: String,
    private val eventRepository: EventRepository,
    private val todayProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
    },
) : ViewModel() {
    private val log = AppLog.tagged("EventDetailViewModel")

    private val _state = MutableStateFlow<EventDetailUiState>(EventDetailUiState.Loading)
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        _state.value = EventDetailUiState.Loading
        loadJob = viewModelScope.launch { _state.value = readEvent() }
    }

    private suspend fun readEvent(): EventDetailUiState {
        val event =
            eventRepository.getEventById(eventId).getOrElse { error ->
                log.error("load_event", error)
                return EventDetailUiState.Error
            } ?: return EventDetailUiState.NotFound
        val exhibitions =
            eventRepository.getExhibitionsForEvent(eventId).getOrElse { error ->
                log.error("load_event_exhibitions", error)
                return EventDetailUiState.Error
            }
        val today = todayProvider()
        val visible = exhibitions.filter { it.isVisibleInCatalog(today) }
        return EventDetailUiState.Content(
            event = event,
            exhibitions = visible.sortedBy { it.openingDate },
            venuesKo = visible.map { it.venueNameKo }.distinct().sorted(),
            venuesEn = visible.map { it.venueNameEn.ifEmpty { it.venueNameKo } }.distinct().sorted(),
        )
    }

    companion object {
        fun factory(
            eventId: String,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { EventDetailViewModel(eventId, eventRepository) }
            }
    }
}
