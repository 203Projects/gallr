package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.toMapPin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

internal class MapWorkflow(
    scope: CoroutineScope,
    allExhibitions: StateFlow<ExhibitionListState>,
    bookmarkedIds: StateFlow<Set<String>>,
    language: StateFlow<AppLanguage>,
    activeEventsById: StateFlow<Map<String, Event>>,
    private val todayProvider: () -> LocalDate,
) {
    private val _displayMode = MutableStateFlow(MapDisplayMode.MY_LIST)
    val displayMode: StateFlow<MapDisplayMode> = _displayMode

    val myListPins: StateFlow<List<ExhibitionMapPin>> =
        combine(allExhibitions, bookmarkedIds, language, activeEventsById) { state, bookmarked, lang, eventsById ->
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.id in bookmarked }
                ?.filter { it.isVisibleInCatalog(todayProvider()) }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allPins: StateFlow<List<ExhibitionMapPin>> =
        combine(allExhibitions, language, activeEventsById) { state, lang, eventsById ->
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.isVisibleInCatalog(todayProvider()) }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDisplayMode(mode: MapDisplayMode) {
        _displayMode.value = mode
    }
}
