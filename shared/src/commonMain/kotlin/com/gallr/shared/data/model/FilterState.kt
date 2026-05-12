package com.gallr.shared.data.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // Phase 2b — filter list to active-event-linked exhibitions
    val showGuestPick: Boolean = false, // Spec 040 — user-toggled guest-editor filter
    val activeGuestEditorId: String? = null, // Spec 040 — ambient context set by ViewModel
) {
    /**
     * Returns true if [exhibition] satisfies all active filters.
     *
     * Logic (per spec edge case):
     * - regions: OR within list; empty = no region restriction
     * - showFeatured, showEditorsPick: each ANDed with the result
     * - openingThisWeek / closingThisWeek: OR'd with each other, then ANDed with rest
     */
    fun matches(exhibition: Exhibition): Boolean {
        val today = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val weekEnd = today.plus(6, DateTimeUnit.DAY)

        val regionsMatch = regions.isEmpty() || exhibition.regionKo in regions
        val featuredMatch = !showFeatured || exhibition.isFeatured
        val picksMatch = !showEditorsPick
        val weekMatch = (!openingThisWeek && !closingThisWeek) ||
            (openingThisWeek && exhibition.openingDate in today..weekEnd) ||
            (closingThisWeek && exhibition.closingDate in today..weekEnd)

        val guestPickMatch = !showGuestPick ||
            (activeGuestEditorId != null && exhibition.editorId == activeGuestEditorId)

        return regionsMatch && featuredMatch && picksMatch && weekMatch && guestPickMatch
    }
}
