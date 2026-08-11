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
    fun `keeps nearby pins separate when only their titles overlap`() {
        val candidates =
            listOf(
                PinVisualCandidate("saved", xPx = 100f, yPx = 100f),
                PinVisualCandidate("nearby", xPx = 148f, yPx = 100f),
                PinVisualCandidate("separate", xPx = 320f, yPx = 100f),
            )

        val groups = groupNearlyCoincidentPins(candidates, proximityThresholdPx = 16f)

        assertEquals(
            listOf(listOf("saved"), listOf("nearby"), listOf("separate")),
            groups.map { it.ids },
        )
    }

    @Test
    fun `groups only pins whose locations are almost identical on screen`() {
        val candidates =
            listOf(
                PinVisualCandidate("one", xPx = 100f, yPx = 100f),
                PinVisualCandidate("two", xPx = 110f, yPx = 106f),
                PinVisualCandidate("three", xPx = 240f, yPx = 100f),
            )

        val groups = groupNearlyCoincidentPins(candidates, proximityThresholdPx = 16f)

        assertEquals(listOf(listOf("one", "two"), listOf("three")), groups.map { it.ids })
        assertEquals(105f, groups.first().xPx)
        assertEquals(103f, groups.first().yPx)
    }

    @Test
    fun `near coincident grouping remains transitive`() {
        val candidates =
            listOf(
                PinVisualCandidate("one", xPx = 100f, yPx = 100f),
                PinVisualCandidate("two", xPx = 114f, yPx = 100f),
                PinVisualCandidate("three", xPx = 128f, yPx = 100f),
            )

        val groups = groupNearlyCoincidentPins(candidates, proximityThresholdPx = 16f)

        assertEquals(listOf(listOf("one", "two", "three")), groups.map { it.ids })
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
