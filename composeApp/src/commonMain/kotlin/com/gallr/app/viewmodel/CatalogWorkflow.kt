package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    private var activeEventsLoadInProgress = false
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
        if (activeEventsLoadInProgress) return
        activeEventsLoadInProgress = true
        scope.launch {
            try {
                eventRepository
                    .getActiveEvents()
                    .onSuccess { events ->
                        _activeEventsById.value = events.associateBy { it.id }
                        _activeEvents.value = events
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        log.error("load_active_events", error)
                    }
            } finally {
                activeEventsLoadInProgress = false
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
                loadCatalogWithStartupRetry(
                    surface = "featured",
                    retryWhenNoContent = !hadContent,
                    fetch = exhibitionRepository::getFeaturedExhibitions,
                ).onSuccess { exhibitions ->
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
                loadCatalogWithStartupRetry(
                    surface = "all",
                    retryWhenNoContent = !hadContent,
                    fetch = exhibitionRepository::getExhibitions,
                ).onSuccess { exhibitions ->
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

    /** Keep transient cold-start transport failures behind the loading state. */
    private suspend fun <T> loadCatalogWithStartupRetry(
        surface: String,
        retryWhenNoContent: Boolean,
        fetch: suspend () -> Result<T>,
    ): Result<T> {
        val maxAttempts = if (retryWhenNoContent) STARTUP_LOAD_MAX_ATTEMPTS else 1
        var lastFailure: Throwable? = null

        repeat(maxAttempts) { zeroBasedAttempt ->
            val attempt = zeroBasedAttempt + 1
            val result = fetch()
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            lastFailure = error
            if (attempt < maxAttempts) {
                log.warn("catalog_load_retry_$surface", error)
                delay(STARTUP_LOAD_RETRY_DELAY_MILLIS)
            }
        }

        return Result.failure(lastFailure ?: IllegalStateException("Catalog load failed"))
    }
}

private fun classifyCatalogError(error: Throwable): String {
    var current: Throwable? = error
    repeat(MAX_ERROR_CAUSE_DEPTH) {
        val cause = current ?: return "server"
        val signature =
            buildString {
                append(cause::class.simpleName.orEmpty())
                append(' ')
                append(cause.message.orEmpty())
            }.lowercase()
        if (NETWORK_ERROR_MARKERS.any(signature::contains)) return "network"

        val next = cause.cause
        if (next === cause) return "server"
        current = next
    }
    return "server"
}

private const val STARTUP_LOAD_MAX_ATTEMPTS = 2
private const val STARTUP_LOAD_RETRY_DELAY_MILLIS = 500L
private const val MAX_ERROR_CAUSE_DEPTH = 8

private val NETWORK_ERROR_MARKERS =
    listOf(
        "unknownhost",
        "connectexception",
        "timeoutexception",
        "noroutetohost",
        "darwinhttprequest",
        "internet connection",
        "network connection",
        "network is unreachable",
        "not connected to the internet",
        "appears to be offline",
        "could not connect",
        "timed out",
        "dns lookup",
    )
