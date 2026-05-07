package com.gallr.shared.data.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterStateTest {

    private val today = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    private val yesterday = today.plus(-1, DateTimeUnit.DAY)
    private val inThreeDays = today.plus(3, DateTimeUnit.DAY)
    private val inTenDays = today.plus(10, DateTimeUnit.DAY)

    private fun exhibition(
        region: String = "London",
        isFeatured: Boolean = false,
        isEditorsPick: Boolean = false,
        openingDate: kotlinx.datetime.LocalDate = yesterday,
        closingDate: kotlinx.datetime.LocalDate = inTenDays,
    ) = Exhibition(
        id = "x",
        nameKo = "Test",
        nameEn = "Test",
        venueNameKo = "Venue",
        venueNameEn = "Venue",
        cityKo = "London",
        cityEn = "London",
        regionKo = region,
        regionEn = region,
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = isFeatured,
        isEditorsPick = isEditorsPick,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    @Test
    fun `default FilterState matches all exhibitions`() {
        assertTrue(FilterState().matches(exhibition()))
        assertTrue(FilterState().matches(exhibition(isFeatured = true)))
        assertTrue(FilterState().matches(exhibition(isEditorsPick = true)))
    }

    @Test
    fun `showFeatured true only matches featured exhibitions`() {
        val filter = FilterState(showFeatured = true)
        assertFalse(filter.matches(exhibition(isFeatured = false)))
        assertTrue(filter.matches(exhibition(isFeatured = true)))
    }

    @Test
    fun `showEditorsPick true only matches editors pick exhibitions`() {
        val filter = FilterState(showEditorsPick = true)
        assertFalse(filter.matches(exhibition(isEditorsPick = false)))
        assertTrue(filter.matches(exhibition(isEditorsPick = true)))
    }

    @Test
    fun `regions filter matches exhibitions in selected regions`() {
        val filter = FilterState(regions = listOf("London", "Paris"))
        assertTrue(filter.matches(exhibition(region = "London")))
        assertTrue(filter.matches(exhibition(region = "Paris")))
        assertFalse(filter.matches(exhibition(region = "Berlin")))
    }

    @Test
    fun `empty regions filter matches all regions`() {
        val filter = FilterState(regions = emptyList())
        assertTrue(filter.matches(exhibition(region = "Anywhere")))
    }

    @Test
    fun `openingThisWeek matches exhibitions opening within next 7 days`() {
        val filter = FilterState(openingThisWeek = true)
        assertTrue(filter.matches(exhibition(openingDate = today)))
        assertTrue(filter.matches(exhibition(openingDate = inThreeDays)))
        assertFalse(filter.matches(exhibition(openingDate = inTenDays)))
        assertFalse(filter.matches(exhibition(openingDate = yesterday)))
    }

    @Test
    fun `closingThisWeek matches exhibitions closing within next 7 days`() {
        val filter = FilterState(closingThisWeek = true)
        assertTrue(filter.matches(exhibition(closingDate = inThreeDays)))
        assertFalse(filter.matches(exhibition(closingDate = inTenDays)))
    }

    @Test
    fun `openingThisWeek and closingThisWeek are OR logic`() {
        val filter = FilterState(openingThisWeek = true, closingThisWeek = true)
        // Opening soon but closing far away
        assertTrue(filter.matches(exhibition(openingDate = inThreeDays, closingDate = inTenDays)))
        // Closing soon but opened in the past
        assertTrue(filter.matches(exhibition(openingDate = yesterday, closingDate = inThreeDays)))
        // Neither
        assertFalse(filter.matches(exhibition(openingDate = yesterday, closingDate = inTenDays)))
    }

    @Test
    fun `multiple active filters are ANDed`() {
        val filter = FilterState(
            regions = listOf("London"),
            showFeatured = true,
        )
        // Featured in London — matches
        assertTrue(filter.matches(exhibition(region = "London", isFeatured = true)))
        // Featured but wrong region — no match
        assertFalse(filter.matches(exhibition(region = "Paris", isFeatured = true)))
        // Right region but not featured — no match
        assertFalse(filter.matches(exhibition(region = "London", isFeatured = false)))
    }

    @Test
    fun `default FilterState has eventOnly false`() {
        val filter = FilterState()
        kotlin.test.assertEquals(false, filter.eventOnly)
    }
}
