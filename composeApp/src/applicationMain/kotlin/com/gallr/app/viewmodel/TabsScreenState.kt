package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.CityWithCount
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.data.model.MapDisplayMode
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.RegionWithCount

data class ListScreenUiState(
    val exhibitions: ExhibitionListState = ExhibitionListState.Loading,
    val filter: FilterState = FilterState(),
    val bookmarkedIds: Set<String> = emptySet(),
    val language: AppLanguage = AppLanguage.KO,
    val selectedCity: String? = null,
    val cities: List<CityWithCount> = emptyList(),
    val regions: List<RegionWithCount> = emptyList(),
    val tabExhibitionCount: Int = 0,
    val showMyListOnly: Boolean = false,
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val activeEvents: List<Event> = emptyList(),
    val promotedExhibition: PromotedExhibition? = null,
)

data class MapScreenUiState(
    val displayMode: MapDisplayMode = MapDisplayMode.MY_LIST,
    val myListPins: List<ExhibitionMapPin> = emptyList(),
    val allPins: List<ExhibitionMapPin> = emptyList(),
    val language: AppLanguage = AppLanguage.KO,
    val activeEvents: List<Event> = emptyList(),
)
