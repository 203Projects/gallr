package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.CityWithCount
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.RegionWithCount
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.data.model.toMapPin
import com.gallr.shared.data.model.Event
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

sealed class ExhibitionListState {
    data object Loading : ExhibitionListState()
    data class Success(val exhibitions: List<Exhibition>) : ExhibitionListState()
    data class Error(val message: String) : ExhibitionListState()
}

class TabsViewModel(
    private val exhibitionRepository: ExhibitionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val languageRepository: LanguageRepository,
    private val themeRepository: ThemeRepository,
    private val eventRepository: EventRepository,
) : ViewModel() {

    // ── Theme ─────────────────────────────────────────────────────────────────

    val themeMode: StateFlow<ThemeMode> =
        themeRepository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeRepository.setThemeMode(mode) }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    val language: StateFlow<AppLanguage> =
        languageRepository.observeLanguage()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.KO)

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { languageRepository.setLanguage(lang) }
    }

    fun toggleLanguage() {
        val current = language.value
        setLanguage(if (current == AppLanguage.KO) AppLanguage.EN else AppLanguage.KO)
    }

    // ── Raw data ────────────────────────────────────────────────────────────

    private val _featuredState =
        MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
    val featuredState: StateFlow<ExhibitionListState> = _featuredState

    private val _allExhibitions =
        MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    // ── Active event ─────────────────────────────────────────────────────────

    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent

    private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
    val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById

    private fun loadActiveEvents() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events ->
                    _activeEventsById.value = events.associateBy { it.id }
                    _activeEvent.value = events.firstOrNull()
                }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvents: ${it.message}")
                    _activeEventsById.value = emptyMap()
                    _activeEvent.value = null
                }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ── Filter state ────────────────────────────────────────────────────────

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState

    fun updateFilter(update: FilterState.() -> FilterState) {
        _filterState.value = _filterState.value.update()
    }

    // ── City filter ──────────────────────────────────────────────────────────

    private val _selectedCity = MutableStateFlow<String?>(null) // null = all cities, otherwise cityKo
    val selectedCity: StateFlow<String?> = _selectedCity

    fun setCity(cityKo: String?) {
        _selectedCity.value = cityKo
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }

    val distinctCities: StateFlow<List<CityWithCount>> =
        _allExhibitions.map { state ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.closingDate >= today }
                ?.groupBy { it.cityKo to it.cityEn }
                ?.map { (city, exhs) -> CityWithCount(city.first, city.second, exhs.size) }
                ?.sortedByDescending { it.count }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val distinctRegions: StateFlow<List<RegionWithCount>> =
        combine(_allExhibitions, _selectedCity) { state, city ->
            if (city == null) return@combine emptyList()
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.closingDate >= today && it.cityKo == city }
                ?.groupBy { it.regionKo to it.regionEn }
                ?.map { (region, exhs) -> RegionWithCount(region.first, region.second, exhs.size) }
                ?.sortedByDescending { it.count }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleRegion(regionKo: String) {
        _filterState.value = _filterState.value.let { current ->
            if (regionKo in current.regions) {
                current.copy(regions = current.regions - regionKo)
            } else {
                current.copy(regions = current.regions + regionKo)
            }
        }
    }

    fun clearRegions() {
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }

    // ── My List filter ────────────────────────────────────────────────────────

    private val _showMyListOnly = MutableStateFlow(false)
    val showMyListOnly: StateFlow<Boolean> = _showMyListOnly

    fun setShowMyListOnly(enabled: Boolean) {
        _showMyListOnly.value = enabled
    }

    fun clearAllFilters() {
        _filterState.value = FilterState()
        _selectedCity.value = null
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    val bookmarkedIds: StateFlow<Set<String>> =
        bookmarkRepository.observeBookmarkedIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleBookmark(exhibitionId: String) {
        viewModelScope.launch {
            if (bookmarkRepository.isBookmarked(exhibitionId)) {
                bookmarkRepository.removeBookmark(exhibitionId)
            } else {
                bookmarkRepository.addBookmark(exhibitionId)
            }
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch { bookmarkRepository.clearAll() }
    }

    // ── Filtered exhibitions ─────────────────────────────────────────────────

    val filteredExhibitions: StateFlow<ExhibitionListState> =
        combine(
            _allExhibitions, _filterState, _selectedCity, _showMyListOnly, bookmarkedIds, _searchQuery, _activeEvent,
        ) { values ->
            val state = values[0] as ExhibitionListState
            val filter = values[1] as FilterState
            val city = values[2] as String?
            @Suppress("UNCHECKED_CAST")
            val myListOnly = values[3] as Boolean
            @Suppress("UNCHECKED_CAST")
            val bookmarked = values[4] as Set<String>
            val query = (values[5] as String).trim().lowercase()
            val activeEvent = values[6] as Event?
            when (state) {
                is ExhibitionListState.Loading -> ExhibitionListState.Loading
                is ExhibitionListState.Error -> state
                is ExhibitionListState.Success -> {
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    val filtered = state.exhibitions
                        .filter { it.closingDate >= today }  // hide ended exhibitions
                        .filter { city == null || it.cityKo == city }
                        .filter { filter.matches(it) }
                        .filter { !myListOnly || it.id in bookmarked }
                        .filter {
                            query.isEmpty() ||
                                it.nameKo.lowercase().contains(query) ||
                                it.nameEn.lowercase().contains(query) ||
                                it.venueNameKo.lowercase().contains(query) ||
                                it.venueNameEn.lowercase().contains(query)
                        }
                        .filter {
                            // Phase 2b — event-only filter. Short-circuits when activeEvent is null
                            // so stale eventOnly state doesn't transiently empty the list while the
                            // auto-reset collector (init block) clears it.
                            !filter.eventOnly || activeEvent == null || it.eventId == activeEvent.id
                        }
                    ExhibitionListState.Success(filtered)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExhibitionListState.Loading,
        )

    // ── Map display mode + pins ──────────────────────────────────────────────

    private val _mapDisplayMode = MutableStateFlow(MapDisplayMode.MY_LIST)
    val mapDisplayMode: StateFlow<MapDisplayMode> = _mapDisplayMode

    fun setMapDisplayMode(mode: MapDisplayMode) {
        _mapDisplayMode.value = mode
    }

    val myListMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, bookmarkedIds, language, _activeEventsById) { state, bookmarked, lang, eventsById ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.id in bookmarked }
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, language, _activeEventsById) { state, lang, eventsById ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Exhibition lookup ───────────────────────────────────────────────────

    fun findExhibitionById(id: String): Exhibition? =
        (_allExhibitions.value as? ExhibitionListState.Success)
            ?.exhibitions
            ?.firstOrNull { it.id == id }

    // ── Data loading ────────────────────────────────────────────────────────

    fun loadFeaturedExhibitions() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _featuredState.value = ExhibitionListState.Loading
            exhibitionRepository.getFeaturedExhibitions()
                .onSuccess { exhibitions ->
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    _featuredState.value = ExhibitionListState.Success(
                        exhibitions.filter { it.closingDate >= today }
                    )
                }
                .onFailure {
                    val msg = classifyError(it)
                    println("ERROR [TabsViewModel] loadFeaturedExhibitions: ${it.message}")
                    _featuredState.value = ExhibitionListState.Error(msg)
                }
            _isRefreshing.value = false
        }
    }

    fun loadAllExhibitions() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _allExhibitions.value = ExhibitionListState.Loading
            exhibitionRepository.getExhibitions()
                .onSuccess { _allExhibitions.value = ExhibitionListState.Success(it) }
                .onFailure {
                    val msg = classifyError(it)
                    println("ERROR [TabsViewModel] loadAllExhibitions: ${it.message}")
                    _allExhibitions.value = ExhibitionListState.Error(msg)
                }
            _isRefreshing.value = false
        }
    }

    fun refresh() {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
    }

    private fun classifyError(e: Throwable): String {
        val name = e::class.simpleName ?: ""
        return if (name.contains("UnknownHost") || name.contains("Connect") || name.contains("Timeout") || name.contains("NoRoute")) {
            "network"
        } else {
            "server"
        }
    }

    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()

        // Phase 2b — when the active event disappears (expired, deactivated, network
        // failure on refresh), silently clear any stranded eventOnly filter so the
        // List tab doesn't show an empty feed with no way to recover.
        viewModelScope.launch {
            _activeEvent.collect { event ->
                if (event == null && _filterState.value.eventOnly) {
                    _filterState.value = _filterState.value.copy(eventOnly = false)
                }
            }
        }
    }

    // ── Factory ─────────────────────────────────────────────────────────────

    companion object {
        fun factory(
            exhibitionRepository: ExhibitionRepository,
            bookmarkRepository: BookmarkRepository,
            languageRepository: LanguageRepository,
            themeRepository: ThemeRepository,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TabsViewModel(
                    exhibitionRepository,
                    bookmarkRepository,
                    languageRepository,
                    themeRepository,
                    eventRepository,
                )
            }
        }
    }
}
