package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapNearbyPresentationTest {
    private val user = Coordinates(latitude = 37.5219, longitude = 127.0411)

    @Test
    fun adaptive_viewport_contains_user_and_only_nearby_exhibitions() {
        val viewport =
            adaptiveNearbyViewport(
                user = user,
                exhibitions =
                    listOf(
                        exhibition("closest", 37.5229, 127.0411),
                        exhibition("nearby", 37.5400, 127.0200),
                        exhibition("far", 37.6000, 127.2000),
                    ),
            )

        requireNotNull(viewport)
        assertEquals(listOf("closest", "nearby"), viewport.exhibitionIds)
        assertTrue(user.latitude in viewport.south..viewport.north)
        assertTrue(user.longitude in viewport.west..viewport.east)
        assertTrue(37.5400 in viewport.south..viewport.north)
        assertFalse(37.6000 in viewport.south..viewport.north)
        assertTrue(viewport.north - viewport.south >= 0.024)
        assertTrue(viewport.east - viewport.west >= 0.030)
    }

    @Test
    fun adaptive_viewport_is_absent_when_no_exhibition_is_within_five_kilometers() {
        val viewport =
            adaptiveNearbyViewport(
                user = user,
                exhibitions = listOf(exhibition("far", 37.6000, 127.2000)),
            )

        assertNull(viewport)
    }

    @Test
    fun overlap_results_sort_by_distance_without_deduplicating_a_shared_venue() {
        val sharedVenue = exhibition("second", 37.5239, 127.0411)
        val sorted =
            sortOverlapExhibitionsByDistance(
                exhibitions =
                    listOf(
                        sharedVenue,
                        sharedVenue.copy(id = "same-venue", nameKo = "same-venue", nameEn = "same-venue"),
                        exhibition("closest", 37.5229, 127.0411),
                    ),
                user = user,
            )

        assertEquals(listOf("closest", "same-venue", "second"), sorted.map { it.exhibition.id })
        assertEquals(3, sorted.size)
    }

    @Test
    fun overlap_sheet_uses_nearby_copy_and_compact_distance_date_metadata() {
        val item = exhibition("nearby", 37.5329, 127.0411)

        assertEquals("이 주변 전시 3개", overlapSheetTitle(3, AppLanguage.KO))
        assertEquals("3 EXHIBITIONS NEARBY", overlapSheetTitle(3, AppLanguage.EN))
        assertEquals("1.2 KM · 8월 31일까지", overlapMetadata(item, 1.223, AppLanguage.KO))
        assertEquals("1.2 KM · UNTIL AUG 31", overlapMetadata(item, 1.223, AppLanguage.EN))
    }

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "공유 미술관",
        venueNameEn = "Shared Museum",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "강남구",
        regionEn = "Gangnam-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
