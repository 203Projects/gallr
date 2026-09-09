package com.gallr.app.viewmodel

import com.gallr.shared.data.model.CityWithCount
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.RegionWithCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

internal class FilterWorkflow(
    scope: CoroutineScope,
    allExhibitions: StateFlow<ExhibitionListState>,
    bookmarkedIds: StateFlow<Set<String>>,
    private val todayProvider: () -> LocalDate,
) {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState

    private val _selectedCity = MutableStateFlow<String?>(null)
    val selectedCity: StateFlow<String?> = _selectedCity

    private val _showMyListOnly = MutableStateFlow(false)
    val showMyListOnly: StateFlow<Boolean> = _showMyListOnly

    private val tabExhibitions: StateFlow<List<Exhibition>?> =
        combine(allExhibitions, _showMyListOnly, bookmarkedIds) { state, myListOnly, bookmarked ->
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.isVisibleInCatalog(todayProvider()) }
                ?.filter { !myListOnly || it.id in bookmarked }
        }.onEach { exhibitions -> exhibitions?.let(::reconcileLocationFilters) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val tabExhibitionCount: StateFlow<Int> =
        tabExhibitions
            .map { it.orEmpty().size }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    val distinctCities: StateFlow<List<CityWithCount>> =
        tabExhibitions
            .map { exhibitions ->
                exhibitions
                    .orEmpty()
                    .groupBy { canonicalLocationKey(it.cityKo) }
                    .mapNotNull { (_, exhibitions) -> cityWithCount(exhibitions) }
                    .sortedByDescending { it.count }
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val distinctRegions: StateFlow<List<RegionWithCount>> =
        combine(tabExhibitions, _selectedCity) { exhibitions, city ->
            if (city == null) return@combine emptyList()
            exhibitions
                .orEmpty()
                .filter { canonicalLocationKey(it.cityKo) == canonicalLocationKey(city) }
                .groupBy { canonicalLocationKey(it.regionKo) }
                .mapNotNull { (_, exhibitions) -> regionWithCount(exhibitions) }
                .sortedByDescending { it.count }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredExhibitions: StateFlow<ExhibitionListState> =
        combine(
            allExhibitions,
            _filterState,
            _selectedCity,
            _showMyListOnly,
            bookmarkedIds,
            _searchQuery,
        ) { values ->
            filterExhibitions(
                state = values[0] as ExhibitionListState,
                filter = values[1] as FilterState,
                city = values[2] as String?,
                myListOnly = values[3] as Boolean,
                bookmarked = values[4].asBookmarkIds(),
                query = values[5] as String,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ExhibitionListState.Loading)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFilter(update: FilterState.() -> FilterState) {
        _filterState.value = _filterState.value.update()
    }

    fun toggleEventFilter(eventId: String) {
        _filterState.value =
            _filterState.value.let { current ->
                current.copy(selectedEventId = if (current.selectedEventId == eventId) null else eventId)
            }
    }

    fun setCity(cityKo: String?) {
        _selectedCity.value = cityKo
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }

    fun toggleRegion(regionKo: String) {
        _filterState.value =
            _filterState.value.let { current ->
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

    fun setShowMyListOnly(enabled: Boolean) {
        _showMyListOnly.value = enabled
    }

    fun clearAllFilters() {
        _filterState.value = FilterState()
        _selectedCity.value = null
    }

    fun clearInactiveEvent(activeEventIds: Set<String>) {
        val selected = _filterState.value.selectedEventId
        if (selected != null && selected !in activeEventIds) {
            _filterState.value = _filterState.value.copy(selectedEventId = null)
        }
    }

    private fun reconcileLocationFilters(exhibitions: List<Exhibition>) {
        val city = _selectedCity.value ?: return
        val cityKey = canonicalLocationKey(city)
        val cityExhibitions = exhibitions.filter { canonicalLocationKey(it.cityKo) == cityKey }
        if (cityExhibitions.isEmpty()) {
            setCity(null)
            return
        }

        val availableRegions =
            cityExhibitions
                .map { canonicalLocationKey(it.regionKo) }
                .filter { it.isNotEmpty() }
                .toSet()
        val currentFilter = _filterState.value
        val retainedRegions =
            currentFilter.regions.filter { canonicalLocationKey(it) in availableRegions }
        if (retainedRegions != currentFilter.regions) {
            _filterState.value = currentFilter.copy(regions = retainedRegions)
        }
    }

    private fun filterExhibitions(
        state: ExhibitionListState,
        filter: FilterState,
        city: String?,
        myListOnly: Boolean,
        bookmarked: Set<String>,
        query: String,
    ): ExhibitionListState {
        if (state !is ExhibitionListState.Success) return state
        val normalizedQuery = query.trim().lowercase()
        return ExhibitionListState.Success(
            state.exhibitions
                .filter { it.isVisibleInCatalog(todayProvider()) }
                .filter { city == null || canonicalLocationKey(it.cityKo) == canonicalLocationKey(city) }
                .filter(filter::matches)
                .filter { !myListOnly || it.id in bookmarked }
                .filter { exhibition -> exhibition.matches(normalizedQuery) }
                .filter { filter.selectedEventId == null || it.eventId == filter.selectedEventId },
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asBookmarkIds(): Set<String> = this as Set<String>

private fun Exhibition.matches(query: String): Boolean =
    query.isEmpty() ||
        nameKo.lowercase().contains(query) ||
        nameEn.lowercase().contains(query) ||
        venueNameKo.lowercase().contains(query) ||
        venueNameEn.lowercase().contains(query)

private fun cityWithCount(exhibitions: List<Exhibition>): CityWithCount? {
    val cityKo = preferredLocationLabel(exhibitions.map { it.cityKo })
    if (cityKo.isEmpty()) return null
    return CityWithCount(
        cityKo = cityKo,
        cityEn = preferredLocationLabel(exhibitions.map { it.cityEn }),
        count = exhibitions.size,
    )
}

private fun regionWithCount(exhibitions: List<Exhibition>): RegionWithCount? {
    val regionKo = preferredLocationLabel(exhibitions.map { it.regionKo })
    if (regionKo.isEmpty()) return null
    return RegionWithCount(
        regionKo = regionKo,
        regionEn = preferredLocationLabel(exhibitions.map { it.regionEn }),
        count = exhibitions.size,
    )
}
