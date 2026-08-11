package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeoulDistrictMapDataTest {
    @Test
    fun `parses district polygon and geographic bounds`() {
        val polygons = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"종로구"},"geometry":{"type":"Polygon","coordinates":[[[126.9,37.5],[127.1,37.5],[127.1,37.7],[126.9,37.5]]]}}]}"""

        val shapes = parseDistrictShapes(polygons)

        assertEquals("종로구", shapes.districts.single().nameKo)
        assertEquals(126.9, shapes.west)
        assertEquals(127.1, shapes.east)
        assertEquals(37.5, shapes.south)
        assertEquals(37.7, shapes.north)
    }

    @Test
    fun `keeps exhibitions at the same venue independently tappable and excludes invalid locations`() {
        val shapes = parseDistrictShapes(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"종로구"},"geometry":{"type":"Polygon","coordinates":[[[126.9,37.5],[127.1,37.5],[127.1,37.7],[126.9,37.5]]]}}]}""",
        )

        val pins = exhibitionMapPins(
            exhibitions = listOf(
                exhibition("one", latitude = 37.570001, longitude = 126.980001),
                exhibition("two", latitude = 37.570002, longitude = 126.980002),
                exhibition("outside", latitude = 35.0, longitude = 129.0),
                exhibition("missing", latitude = null, longitude = null),
            ),
            bounds = shapes,
        )

        assertEquals(2, pins.size)
        assertEquals(listOf("one", "two"), pins.map { it.exhibition.id })
        assertEquals(2, pins.map { it.position }.distinct().size)
    }

    @Test
    fun `groups only pins whose titles become unreadable`() {
        val candidates = listOf(
            PinVisualCandidate("saved", xPx = 100f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("overlap", xPx = 148f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("separate", xPx = 320f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
        )

        val groups = groupPinsWithUnreadableTitles(candidates)

        assertEquals(listOf(listOf("saved", "overlap"), listOf("separate")), groups.map { it.ids })
        assertEquals(124f, groups.first().xPx)
        assertEquals(100f, groups.first().yPx)
    }

    @Test
    fun `overlap groups resolve to individual pins after zoom separation`() {
        val candidates = listOf(
            PinVisualCandidate("one", xPx = 80f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("two", xPx = 240f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("three", xPx = 400f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
        )

        val groups = groupPinsWithUnreadableTitles(candidates)

        assertEquals(listOf(listOf("one"), listOf("two"), listOf("three")), groups.map { it.ids })
    }

    @Test
    fun `overlap grouping is transitive for a dense label run`() {
        val candidates = listOf(
            PinVisualCandidate("one", xPx = 100f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("two", xPx = 148f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
            PinVisualCandidate("three", xPx = 196f, yPx = 100f, labelWidthPx = 64f, labelHeightPx = 16f),
        )

        val groups = groupPinsWithUnreadableTitles(candidates)

        assertEquals(listOf(listOf("one", "two", "three")), groups.map { it.ids })
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

    private fun exhibition(id: String, latitude: Double?, longitude: Double?) = Exhibition(
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
