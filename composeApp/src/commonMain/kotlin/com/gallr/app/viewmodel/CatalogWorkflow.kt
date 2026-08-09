package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

internal class CatalogWorkflow(
    private val scope: CoroutineScope,
    private val exhibitionRepository: ExhibitionRepository,
    private val eventRepository: EventRepository,
    private val todayProvider: () -> LocalDate,
    private val nowMillisProvider: () -> Long,
) {
    private val log = AppLog.tagged("CatalogWorkflow")

    private val _featuredState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
    val featuredState: StateFlow<ExhibitionListState> = _featuredState

    private val _allExhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
    val allExhibitions: StateFlow<ExhibitionListState> = _allExhibitions

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _activeEvents = MutableStateFlow<List<Event>>(emptyList())
    val activeEvents: StateFlow<List<Event>> = _activeEvents

    private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
    val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById

    private var activeLoadCount = 0
    private var featuredLoadInProgress = false
    private var allExhibitionsLoadInProgress = false
    private var lastSuccessfulCatalogLoadAtMillis: Long? = null

    fun loadInitial() {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
    }

    fun loadFeaturedExhibitions() {
        startFeaturedLoad(preserveCurrentContent = false)
    }

    fun loadAllExhibitions() {
        startAllExhibitionsLoad(preserveCurrentContent = false)
    }

    fun refresh() {
        startFeaturedLoad(preserveCurrentContent = true)
        startAllExhibitionsLoad(preserveCurrentContent = true)
        loadActiveEvents()
    }

    fun refreshIfStale(maxAgeMillis: Long) {
        val lastSuccess = lastSuccessfulCatalogLoadAtMillis
        if (lastSuccess != null && nowMillisProvider() - lastSuccess < maxAgeMillis) return

        refresh()
    }

    fun findExhibitionById(id: String): Exhibition? =
        (_allExhibitions.value as? ExhibitionListState.Success)
            ?.exhibitions
            ?.firstOrNull { it.id == id }

    private fun loadActiveEvents() {
        scope.launch {
            eventRepository
                .getActiveEvents()
                .onSuccess { events ->
                    _activeEventsById.value = events.associateBy { it.id }
                    _activeEvents.value = events
                }.onFailure { error ->
                    log.error("load_active_events", error)
                    _activeEventsById.value = emptyMap()
                    _activeEvents.value = emptyList()
                }
        }
    }

    private fun startFeaturedLoad(preserveCurrentContent: Boolean) {
        if (featuredLoadInProgress) return
        featuredLoadInProgress = true
        beginLoad()
        scope.launch {
            val hadContent = _featuredState.value is ExhibitionListState.Success
            if (!preserveCurrentContent || !hadContent) {
                _featuredState.value = ExhibitionListState.Loading
            }
            try {
                exhibitionRepository
                    .getFeaturedExhibitions()
                    .onSuccess { exhibitions ->
                        val today = todayProvider()
                        _featuredState.value =
                            ExhibitionListState.Success(
                                exhibitions.filter { it.isVisibleInCatalog(today) },
                            )
                    }.onFailure { error ->
                        log.error("load_featured_exhibitions", error)
                        if (!preserveCurrentContent || !hadContent) {
                            _featuredState.value = ExhibitionListState.Error(classifyCatalogError(error))
                        }
                    }
            } finally {
                featuredLoadInProgress = false
                endLoad()
            }
        }
    }

    private fun startAllExhibitionsLoad(preserveCurrentContent: Boolean) {
        if (allExhibitionsLoadInProgress) return
        allExhibitionsLoadInProgress = true
        beginLoad()
        scope.launch {
            val hadContent = _allExhibitions.value is ExhibitionListState.Success
            if (!preserveCurrentContent || !hadContent) {
                _allExhibitions.value = ExhibitionListState.Loading
            }
            try {
                exhibitionRepository
                    .getExhibitions()
                    .onSuccess { exhibitions ->
                        _allExhibitions.value = ExhibitionListState.Success(exhibitions)
                        lastSuccessfulCatalogLoadAtMillis = nowMillisProvider()
                    }.onFailure { error ->
                        log.error("load_all_exhibitions", error)
                        if (!preserveCurrentContent || !hadContent) {
                            _allExhibitions.value = ExhibitionListState.Error(classifyCatalogError(error))
                        }
                    }
            } finally {
                allExhibitionsLoadInProgress = false
                endLoad()
            }
        }
    }

    private fun beginLoad() {
        activeLoadCount += 1
        _isRefreshing.value = true
    }

    private fun endLoad() {
        activeLoadCount = (activeLoadCount - 1).coerceAtLeast(0)
        _isRefreshing.value = activeLoadCount > 0
    }
}

private fun classifyCatalogError(error: Throwable): String {
    val name = error::class.simpleName.orEmpty()
    return if (
        name.contains("UnknownHost") ||
        name.contains("Connect") ||
        name.contains("Timeout") ||
        name.contains("NoRoute")
    ) {
        "network"
    } else {
        "server"
    }
}
