package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EventDetailViewModel(
    private val eventId: String,
    private val eventRepository: EventRepository,
) : ViewModel() {

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event

    private val _exhibitions = MutableStateFlow<List<Exhibition>>(emptyList())
    val exhibitions: StateFlow<List<Exhibition>> = _exhibitions

    private val _venuesKo = MutableStateFlow<List<String>>(emptyList())
    val venuesKo: StateFlow<List<String>> = _venuesKo

    private val _venuesEn = MutableStateFlow<List<String>>(emptyList())
    val venuesEn: StateFlow<List<String>> = _venuesEn

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            eventRepository.getEventById(eventId)
                .onSuccess { _event.value = it }
                .onFailure {
                    _error.value = it.message ?: "load_event_failed"
                    _isLoading.value = false
                    return@launch
                }

            eventRepository.getExhibitionsForEvent(eventId)
                .onSuccess { list ->
                    _exhibitions.value = list.sortedBy { it.openingDate }
                    _venuesKo.value = list.map { it.venueNameKo }.distinct().sorted()
                    _venuesEn.value = list.map { it.venueNameEn.ifEmpty { it.venueNameKo } }.distinct().sorted()
                }
                .onFailure { _error.value = it.message ?: "load_exhibitions_failed" }

            _isLoading.value = false
        }
    }

    companion object {
        fun factory(
            eventId: String,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EventDetailViewModel(eventId, eventRepository) }
        }
    }
}
