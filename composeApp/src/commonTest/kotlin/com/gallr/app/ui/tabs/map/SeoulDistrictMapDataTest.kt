package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeoulDistrictMapDataTest {
    @Test
    fun `maps every valid exhibition coordinate nationwide`() {
        val pins =
            exhibitionMapPins(
                exhibitions =
                    listOf(
                        exhibition("one", latitude = 37.570001, longitude = 126.980001),
                        exhibition("two", latitude = 37.570002, longitude = 126.980002),
                        exhibition("busan", latitude = 35.18, longitude = 129.08),
                        exhibition("missing", latitude = null, longitude = null),
                        exhibition("invalid-latitude", latitude = 95.0, longitude = 129.0),
                        exhibition("invalid-longitude", latitude = 35.0, longitude = 200.0),
                    ),
            )

        assertEquals(3, pins.size)
        assertEquals(listOf("one", "two", "busan"), pins.map { it.exhibition.id })
        assertEquals(3, pins.map { it.position }.distinct().size)
    }

    @Test
    fun `groups only exhibitions with exactly equal source coordinates`() {
        val groups =
            groupPinsByExactPosition(
                exhibitionMapPins(
                    listOf(
                        exhibition("one", latitude = 37.570001, longitude = 126.980001),
                        exhibition("two", latitude = 37.570001, longitude = 126.980001),
                        exhibition("nearby", latitude = 37.570002, longitude = 126.980002),
                    ),
                ),
            )

        assertEquals(
            listOf(listOf("one", "two"), listOf("nearby")),
            groups.map { group ->
                group.pins.map { it.exhibition.id }
            },
        )
        assertEquals(37.570001, groups.first().position.latitude)
        assertEquals(126.980001, groups.first().position.longitude)
    }

    @Test
    fun `exact coordinate groups preserve catalogue and group order`() {
        val groups =
            groupPinsByExactPosition(
                exhibitionMapPins(
                    listOf(
                        exhibition("first-a", latitude = 37.5, longitude = 127.0),
                        exhibition("second", latitude = 37.6, longitude = 127.1),
                        exhibition("first-b", latitude = 37.5, longitude = 127.0),
                    ),
                ),
            )

        assertEquals(
            listOf(listOf("first-a", "first-b"), listOf("second")),
            groups.map { group ->
                group.pins.map { it.exhibition.id }
            },
        )
    }

    @Test
    fun `zoom controls step and clamp across the full map range`() {
        assertEquals(13.5, steppedMapZoom(12.5, direction = 1))
        assertEquals(11.5, steppedMapZoom(12.5, direction = -1))
        assertEquals(MAP_MIN_ZOOM, steppedMapZoom(MAP_MIN_ZOOM, direction = -1))
        assertEquals(MAP_MAX_ZOOM, steppedMapZoom(MAP_MAX_ZOOM, direction = 1))
        assertTrue(MAP_MIN_ZOOM <= 5.0, "The whole Korean peninsula must fit at the minimum zoom")
    }

    @Test
    fun `pin target is rendered only when its marker and title fit inside the viewport`() {
        assertTrue(
            isPinTargetFullyVisible(
                xPx = 180f,
                yPx = 200f,
                viewportWidthPx = 360f,
                viewportHeightPx = 640f,
            ),
        )
        assertFalse(
            isPinTargetFullyVisible(
                xPx = 20f,
                yPx = 200f,
                viewportWidthPx = 360f,
                viewportHeightPx = 640f,
            ),
        )
        assertFalse(
            isPinTargetFullyVisible(
                xPx = 180f,
                yPx = 20f,
                viewportWidthPx = 360f,
                viewportHeightPx = 640f,
            ),
        )
        assertFalse(
            isPinTargetFullyVisible(
                xPx = 180f,
                yPx = 630f,
                viewportWidthPx = 360f,
                viewportHeightPx = 640f,
            ),
        )
    }

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "미술관",
        venueNameEn = "Museum",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 1, 1),
        closingDate = LocalDate(2026, 12, 31),
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
