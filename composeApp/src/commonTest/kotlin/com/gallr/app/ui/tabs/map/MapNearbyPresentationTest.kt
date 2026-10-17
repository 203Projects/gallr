package com.gallr.app.ui.tabs.map

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
