package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.DotMapGeometry
import com.gallr.shared.data.model.map.DotMapProjection
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.model.map.MapMarkState
import com.gallr.shared.data.model.map.MapProjectionItem
import com.gallr.shared.data.model.map.MapScope
import com.gallr.shared.data.model.map.MapScopeId
import com.gallr.shared.data.model.map.MapScopeKind
import com.gallr.shared.data.model.map.NormalizedPoint
import com.gallr.shared.data.model.map.PersonalMapMode
import com.gallr.shared.data.model.map.ProjectedMapMark
import com.gallr.shared.data.model.map.ScopeAggregate
import com.gallr.shared.map.DefaultMapScopeRegistry
import com.gallr.shared.map.DotMapProjector
import com.gallr.shared.map.GeneratedDotMapGeometries
import com.gallr.shared.map.GenericCityDotGeometry
import com.gallr.shared.map.ScopeAggregator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.round
import kotlin.time.Clock

data class MapChildSummary(
    val scope: MapScope,
    val aggregate: ScopeAggregate,
    val displayPoint: NormalizedPoint?,
    val sourcePoint: GeoPoint?,
)

data class PersonalMapUiState(
    val activeScope: MapScope,
    val mode: PersonalMapMode,
    val language: AppLanguage,
    val geometry: DotMapGeometry?,
    val projection: DotMapProjection,
    val childSummaries: List<MapChildSummary>,
    val aggregate: ScopeAggregate,
    val resultExhibitions: List<Exhibition>,
    val savedExhibitionIds: Set<String>,
    val coordinateUnavailableExhibitions: List<Exhibition>,
    val selectedMark: ProjectedMapMark?,
    val selectedChildScope: MapScope?,
    val selectedExhibitions: List<Exhibition>,
    val selectedDistrictExhibitions: List<Exhibition>,
    val selectedDistrictAggregate: ScopeAggregate?,
    val selectedDistrictLabel: String?,
    val selectedDistrictIds: Set<MapScopeId>,
    val selectedDistrictExhibitionCount: Int,
    val isLoading: Boolean,
    val errorMessage: String?,
) {
    val allCount: Int get() = aggregate.activeExhibitionCount
    val toVisitCount: Int get() = aggregate.savedUnvisitedCount
    val visitedCount: Int get() = aggregate.visitedExhibitionCount
}

class PersonalMapViewModel(
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    private val bookmarkedIds: StateFlow<Set<String>>,
    private val language: StateFlow<AppLanguage>,
    private val scopeRegistry: DefaultMapScopeRegistry = DefaultMapScopeRegistry(),
    private val todayProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {
    private val activeScopeId = MutableStateFlow(scopeRegistry.seoulScope.id)
    private val selectedMarkId = MutableStateFlow<String?>(null)
    private val selectedDistrictIds = MutableStateFlow<Set<MapScopeId>>(emptySet())
    private val mode = MutableStateFlow(PersonalMapMode.ALL)

    val uiState: StateFlow<PersonalMapUiState> =
        combine(
            exhibitionsState,
            bookmarkedIds,
            language,
            activeScopeId,
            selectedMarkId,
            selectedDistrictIds,
            mode,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            buildState(
                exhibitionState = values[0] as ExhibitionListState,
                bookmarks = values[1] as Set<String>,
                lang = values[2] as AppLanguage,
                requestedScopeId = values[3] as MapScopeId,
                selectedId = values[4] as String?,
                selectedDistrictIds = values[5] as Set<MapScopeId>,
                selectedMode = values[6] as PersonalMapMode,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyState(scopeRegistry.seoulScope),
        )

    fun setMode(newMode: PersonalMapMode) {
        mode.value = newMode
        selectedMarkId.value = null
    }

    fun selectMark(markId: String?) {
        selectedMarkId.value = markId
    }

    fun openSelected() {
        val child = uiState.value.selectedChildScope ?: return
        activeScopeId.value = child.id
        selectedMarkId.value = null
    }

    fun openScope(scopeId: MapScopeId) {
        activeScopeId.value = scopeId
        selectedMarkId.value = null
    }

    fun selectChildArea(scopeId: MapScopeId) {
        val state = uiState.value
        val child = state.childSummaries.firstOrNull { it.scope.id == scopeId } ?: return
        val districtKey = child.scope.districtKey ?: return
        val matchingIds =
            state.resultExhibitions
                .filter { stableLocationKey(it.regionEn, it.regionKo) == districtKey }
                .mapTo(mutableSetOf()) { it.id }
        selectedMarkId.value =
            state.projection.marks
                .firstOrNull { mark -> mark.itemIds.any { it in matchingIds } }
                ?.id
    }

    fun toggleDistrictSelection(scopeId: MapScopeId) {
        val state = uiState.value
        if (state.activeScope.kind != MapScopeKind.CITY) return
        if (state.childSummaries.none { it.scope.id == scopeId }) return
        selectedDistrictIds.value =
            selectedDistrictIds.value.toMutableSet().apply {
                if (!add(scopeId)) remove(scopeId)
            }
    }

    fun clearDistrictSelection() {
        selectedDistrictIds.value = emptySet()
    }

    fun saveDistrictSelection() {
        selectedMarkId.value = null
    }

    fun goToParent() {
        val parent = uiState.value.activeScope.parentId ?: return
        activeScopeId.value = parent
        selectedMarkId.value = null
    }

    private fun buildState(
        exhibitionState: ExhibitionListState,
        bookmarks: Set<String>,
        lang: AppLanguage,
        requestedScopeId: MapScopeId,
        selectedId: String?,
        selectedDistrictIds: Set<MapScopeId>,
        selectedMode: PersonalMapMode,
    ): PersonalMapUiState {
        val allRows =
            (exhibitionState as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.isVisibleInCatalog(todayProvider()) }
                .orEmpty()
        val activeScope = scopeRegistry.scope(requestedScopeId, allRows) ?: scopeRegistry.rootScope
        val scopeRows = scopeRegistry.exhibitionsInScope(activeScope, allRows)
        val aggregate = ScopeAggregator.aggregate(scopeRows, bookmarks)
        val modeRows =
            when (selectedMode) {
                PersonalMapMode.TO_VISIT -> scopeRows.filter { it.id in bookmarks }
                PersonalMapMode.VISITED -> emptyList()
                PersonalMapMode.ALL -> scopeRows
            }
        val childScopes = scopeRegistry.childScopes(activeScope, allRows)
        val childSummaries =
            childScopes.map { child ->
                val childRows = scopeRegistry.exhibitionsInScope(child, scopeRows)
                val sourcePoint = averagePoint(childRows)
                MapChildSummary(
                    scope = child,
                    aggregate =
                        ScopeAggregator.aggregate(
                            childRows,
                            bookmarks,
                        ),
                    displayPoint =
                        sourcePoint?.let { point ->
                            geometryFor(activeScope)?.bounds?.project(point)
                        },
                    sourcePoint = sourcePoint,
                )
            }
        val geometry = geometryFor(activeScope)
        val projection =
            geometry?.let { mapGeometry ->
                DotMapProjector.project(
                    geometry = mapGeometry,
                    items = projectionItems(activeScope, childScopes, modeRows, bookmarks),
                )
            } ?: DotMapProjection(
                marks = emptyList(),
                coordinateUnavailableItemIds =
                    modeRows
                        .filter { it.latitude == null || it.longitude == null }
                        .map { it.id },
            )
        val selectedMark = projection.marks.firstOrNull { it.id == selectedId }
        val selectedChild = childScopes.firstOrNull { it.id.value == selectedMark?.id }
        val selectedExhibitions =
            selectedMark
                ?.itemIds
                ?.mapNotNull { id -> allRows.firstOrNull { it.id == id } }
                .orEmpty()
        val selectedDistrictKey =
            selectedExhibitions.firstOrNull()?.let {
                stableLocationKey(it.regionEn, it.regionKo)
            }
        val selectedDistrictExhibitions =
            if (selectedDistrictKey == null) {
                emptyList()
            } else {
                scopeRows.filter { stableLocationKey(it.regionEn, it.regionKo) == selectedDistrictKey }
            }
        val unavailableIds = projection.coordinateUnavailableItemIds.toSet()
        val validSelectedDistrictIds =
            selectedDistrictIds.filterTo(linkedSetOf()) { scopeId ->
                scopeRegistry.scope(scopeId, allRows)?.kind == MapScopeKind.DISTRICT
            }
        val selectedDistrictExhibitionCount =
            validSelectedDistrictIds
                .asSequence()
                .mapNotNull { scopeRegistry.scope(it, allRows) }
                .flatMap { scopeRegistry.exhibitionsInScope(it, allRows).asSequence() }
                .map { it.id }
                .distinct()
                .count()

        return PersonalMapUiState(
            activeScope = activeScope,
            mode = selectedMode,
            language = lang,
            geometry = geometry,
            projection = projection,
            childSummaries = childSummaries,
            aggregate = aggregate,
            resultExhibitions =
                modeRows.sortedWith(
                    compareBy({ it.regionEn }, { it.venueNameEn }, { it.nameEn }, { it.id }),
                ),
            savedExhibitionIds = bookmarks,
            coordinateUnavailableExhibitions = modeRows.filter { it.id in unavailableIds },
            selectedMark = selectedMark,
            selectedChildScope = selectedChild,
            selectedExhibitions = selectedExhibitions,
            selectedDistrictExhibitions = selectedDistrictExhibitions,
            selectedDistrictAggregate =
                selectedDistrictKey?.let {
                    ScopeAggregator.aggregate(selectedDistrictExhibitions, bookmarks)
                },
            selectedDistrictLabel =
                selectedExhibitions.firstOrNull()?.let {
                    if (lang == AppLanguage.KO) it.regionKo else it.regionEn.ifBlank { it.regionKo }
                },
            selectedDistrictIds = validSelectedDistrictIds,
            selectedDistrictExhibitionCount = selectedDistrictExhibitionCount,
            isLoading = exhibitionState is ExhibitionListState.Loading,
            errorMessage = (exhibitionState as? ExhibitionListState.Error)?.message,
        )
    }

    private fun projectionItems(
        scope: MapScope,
        childScopes: List<MapScope>,
        rows: List<Exhibition>,
        bookmarks: Set<String>,
    ): List<MapProjectionItem> =
        when (scope.kind) {
            MapScopeKind.COUNTRY -> {
                childScopes.mapNotNull { child ->
                    val childRows = scopeRegistry.exhibitionsInScope(child, rows)
                    val point = averagePoint(childRows) ?: return@mapNotNull null
                    MapProjectionItem(
                        id = child.id.value,
                        groupKey = child.id.value,
                        sourcePoint = point,
                        state =
                            if (childRows.any {
                                    it.id in bookmarks
                                }
                            ) {
                                MapMarkState.SAVED
                            } else {
                                MapMarkState.UNEXPLORED
                            },
                    )
                }
            }

            MapScopeKind.CITY, MapScopeKind.DISTRICT -> {
                rows.map { exhibition ->
                    val point =
                        exhibition.latitude?.let { latitude ->
                            exhibition.longitude?.let { longitude ->
                                runCatching { GeoPoint(latitude, longitude) }.getOrNull()
                            }
                        }
                    MapProjectionItem(
                        id = exhibition.id,
                        groupKey =
                            point?.let { "location:${it.latitude.roundTo4()}:${it.longitude.roundTo4()}" }
                                ?: "missing:${exhibition.id}",
                        sourcePoint = point,
                        state = if (exhibition.id in bookmarks) MapMarkState.SAVED else MapMarkState.UNEXPLORED,
                    )
                }
            }
        }

    private fun geometryFor(scope: MapScope): DotMapGeometry? =
        when {
            scope.geometryKey != null -> {
                GeneratedDotMapGeometries.geometry(scope.geometryKey.orEmpty())
            }

            scope.kind == MapScopeKind.CITY -> {
                GenericCityDotGeometry.create(
                    key = scope.id.value,
                    bounds = scope.geoBounds,
                )
            }

            else -> {
                null
            }
        }

    private fun averagePoint(rows: List<Exhibition>): GeoPoint? {
        val points =
            rows.mapNotNull { row ->
                val latitude = row.latitude ?: return@mapNotNull null
                val longitude = row.longitude ?: return@mapNotNull null
                runCatching { GeoPoint(latitude, longitude) }.getOrNull()
            }
        if (points.isEmpty()) return null
        return GeoPoint(
            latitude = points.sumOf { it.latitude } / points.size,
            longitude = points.sumOf { it.longitude } / points.size,
        )
    }

    private fun emptyState(scope: MapScope) =
        PersonalMapUiState(
            activeScope = scope,
            mode = PersonalMapMode.ALL,
            language = AppLanguage.KO,
            geometry = GeneratedDotMapGeometries.geometry(scope.geometryKey.orEmpty()),
            projection = DotMapProjection(emptyList(), emptyList()),
            childSummaries = emptyList(),
            aggregate = ScopeAggregate(0, 0, 0, 0, 0, 0),
            resultExhibitions = emptyList(),
            savedExhibitionIds = emptySet(),
            coordinateUnavailableExhibitions = emptyList(),
            selectedMark = null,
            selectedChildScope = null,
            selectedExhibitions = emptyList(),
            selectedDistrictExhibitions = emptyList(),
            selectedDistrictAggregate = null,
            selectedDistrictLabel = null,
            selectedDistrictIds = emptySet(),
            selectedDistrictExhibitionCount = 0,
            isLoading = true,
            errorMessage = null,
        )

    companion object {
        fun factory(
            exhibitionsState: StateFlow<ExhibitionListState>,
            bookmarkedIds: StateFlow<Set<String>>,
            language: StateFlow<AppLanguage>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PersonalMapViewModel(exhibitionsState, bookmarkedIds, language)
                }
            }
    }
}

private fun Double.roundTo4(): Long = round(this * 10_000).toLong()

private fun stableLocationKey(
    preferred: String,
    fallback: String,
): String = preferred.ifBlank { fallback }.trim().lowercase()
