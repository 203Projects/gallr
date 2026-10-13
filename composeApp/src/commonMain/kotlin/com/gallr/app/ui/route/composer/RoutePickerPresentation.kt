package com.gallr.app.ui.route.composer

import com.gallr.app.viewmodel.isVisibleInCatalog
import com.gallr.app.viewmodel.matchesSearchQuery
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.route.MAX_ROUTE_STOPS
import kotlinx.datetime.LocalDate

/** How a picker row behaves for the current route and selection (DR-D9, RO5). */
internal enum class PickerRowState(
    val isEnabled: Boolean,
) {
    SELECTABLE(isEnabled = true),
    SELECTED(isEnabled = true),

    /** Already a stop of the route. */
    IN_ROUTE(isEnabled = false),

    /** The exhibition has no map location, so it cannot be timed or drawn. */
    NO_LOCATION(isEnabled = false),

    /** The route plus the selection already holds the maximum number of stops. */
    AT_CAPACITY(isEnabled = false),
}

internal data class PickerCta(
    val label: String,
    val enabled: Boolean,
)

/** Exhibitions the picker offers: the List tab's current and upcoming shows; ended ones are left out. */
internal fun pickerCandidates(
    exhibitions: List<Exhibition>,
    today: LocalDate,
): List<Exhibition> = exhibitions.filter { it.isVisibleInCatalog(today) }

/** Candidates matching the List tab search [query] and, when chosen, the district [region]. */
internal fun pickerResults(
    candidates: List<Exhibition>,
    query: String,
    region: String?,
): List<Exhibition> {
    val normalizedQuery = query.trim().lowercase()
    return candidates.filter { exhibition ->
        exhibition.matchesSearchQuery(normalizedQuery) && (region == null || exhibition.regionKo.trim() == region)
    }
}

/** A district chip: filters by the Korean name, which every row carries, and reads in the app language. */
internal data class PickerRegion(
    val key: String,
    val label: String,
)

/** The districts the candidates are in, in first-seen order, for the filter chips. */
internal fun pickerRegions(
    candidates: List<Exhibition>,
    language: AppLanguage,
): List<PickerRegion> =
    candidates
        .filter { it.regionKo.isNotBlank() }
        .distinctBy { it.regionKo.trim() }
        .map { PickerRegion(key = it.regionKo.trim(), label = it.localizedRegion(language).trim()) }

internal fun pickerRowState(
    exhibition: Exhibition,
    selectedIds: Set<String>,
    routeIds: Set<String>,
): PickerRowState =
    when {
        exhibition.id in routeIds -> PickerRowState.IN_ROUTE
        exhibition.id in selectedIds -> PickerRowState.SELECTED
        exhibition.latitude == null || exhibition.longitude == null -> PickerRowState.NO_LOCATION
        routeIds.size + selectedIds.size >= MAX_ROUTE_STOPS -> PickerRowState.AT_CAPACITY
        else -> PickerRowState.SELECTABLE
    }

/** The selection after tapping [exhibition]; rows that are not enabled leave it unchanged. */
internal fun toggledSelection(
    selectedIds: Set<String>,
    exhibition: Exhibition,
    routeIds: Set<String>,
): Set<String> =
    when (pickerRowState(exhibition, selectedIds, routeIds)) {
        PickerRowState.SELECTED -> selectedIds - exhibition.id
        PickerRowState.SELECTABLE -> selectedIds + exhibition.id
        else -> selectedIds
    }

/** The trailing label explaining why a row cannot be selected, or null. */
internal fun pickerRowLabel(
    state: PickerRowState,
    language: AppLanguage,
): String? =
    when (state) {
        PickerRowState.IN_ROUTE -> if (language == AppLanguage.KO) "이미 추가됨" else "ALREADY ADDED"
        PickerRowState.NO_LOCATION -> if (language == AppLanguage.KO) "위치 정보 없음" else "NO LOCATION"
        else -> null
    }

/** The add button: how many are selected and how full the route is, or the limit once it is reached. */
internal fun pickerCta(
    selectedCount: Int,
    routeStopCount: Int,
    language: AppLanguage,
): PickerCta {
    val atLimit = routeStopCount + selectedCount >= MAX_ROUTE_STOPS
    val limit = if (language == AppLanguage.KO) "최대 ${MAX_ROUTE_STOPS}곳까지 담을 수 있어요" else "UP TO $MAX_ROUTE_STOPS STOPS"
    val fullness =
        if (language == AppLanguage.KO) {
            "현재 $routeStopCount/$MAX_ROUTE_STOPS"
        } else {
            "$routeStopCount/$MAX_ROUTE_STOPS IN ROUTE"
        }
    if (selectedCount == 0) {
        val prompt = if (language == AppLanguage.KO) "추가할 전시를 고르세요" else "SELECT EXHIBITIONS"
        return PickerCta(if (atLimit) "$fullness · $limit" else "$prompt · $fullness", enabled = false)
    }
    val add = if (language == AppLanguage.KO) "${selectedCount}개 추가" else "ADD $selectedCount"
    return PickerCta("$add · ${if (atLimit) limit else fullness}", enabled = true)
}

internal fun pickerHelperText(language: AppLanguage): String =
    if (language == AppLanguage.KO) "현재·예정 전시만 보여요" else "CURRENT AND UPCOMING SHOWS ONLY"

internal fun pickerSelectedAnnouncement(language: AppLanguage): String =
    if (language == AppLanguage.KO) "선택됨" else "Selected"

internal fun pickerTitle(language: AppLanguage): String = if (language == AppLanguage.KO) "전시 추가" else "ADD EXHIBITIONS"
