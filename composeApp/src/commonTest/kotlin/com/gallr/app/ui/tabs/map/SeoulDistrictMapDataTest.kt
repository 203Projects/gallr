package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import io.github.dellisd.spatialk.geojson.Position
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
    fun `keeps pins separate when projected anchors exceed the 1_8_0 threshold`() {
        val groups =
            groupNearlyCoincidentPins(
                candidates =
                    listOf(
                        PinVisualCandidate("saved", xPx = 100f, yPx = 100f),
                        PinVisualCandidate("nearby", xPx = 117f, yPx = 100f),
                        PinVisualCandidate("separate", xPx = 320f, yPx = 100f),
                    ),
                proximityThresholdPx = 16f,
            )

        assertEquals(
            listOf(listOf("saved"), listOf("nearby"), listOf("separate")),
            groups.map { it.ids },
        )
    }

    @Test
    fun `groups projected pins within the 1_8_0 threshold at their centroid`() {
        val groups =
            groupNearlyCoincidentPins(
                candidates =
                    listOf(
                        PinVisualCandidate("one", xPx = 100f, yPx = 100f),
                        PinVisualCandidate("two", xPx = 110f, yPx = 106f),
                        PinVisualCandidate("three", xPx = 240f, yPx = 100f),
                    ),
                proximityThresholdPx = 16f,
            )

        assertEquals(listOf(listOf("one", "two"), listOf("three")), groups.map { it.ids })
        assertEquals(105f, groups.first().xPx)
        assertEquals(103f, groups.first().yPx)
    }

    @Test
    fun `projected pin grouping is transitive and preserves catalogue order`() {
        val groups =
            groupNearlyCoincidentPins(
                candidates =
                    listOf(
                        PinVisualCandidate("one", xPx = 100f, yPx = 100f),
                        PinVisualCandidate("two", xPx = 114f, yPx = 100f),
                        PinVisualCandidate("three", xPx = 128f, yPx = 100f),
                    ),
                proximityThresholdPx = 16f,
            )

        assertEquals(listOf(listOf("one", "two", "three")), groups.map { it.ids })
        assertEquals(114f, groups.single().xPx)
        assertEquals(100f, groups.single().yPx)
    }

    @Test
    fun `coincident source locations still form one projected group`() {
        val groups =
            groupNearlyCoincidentPins(
                candidates =
                    listOf(
                        PinVisualCandidate("one", xPx = 100f, yPx = 100f),
                        PinVisualCandidate("two", xPx = 100f, yPx = 100f),
                    ),
                proximityThresholdPx = 16f,
            )

        assertEquals(listOf("one", "two"), groups.single().ids)
    }

    @Test
    fun `multi-location pin is highlighted when any exhibition is saved`() {
        val group =
            groupPinsByExactPosition(
                exhibitionMapPins(
                    listOf(
                        exhibition("saved", latitude = 37.57, longitude = 126.98),
                        exhibition("unsaved", latitude = 37.57, longitude = 126.98),
                    ),
                ),
            ).single()

        assertTrue(groupContainsSavedExhibition(group, setOf("saved")))
        assertFalse(groupContainsSavedExhibition(group, emptySet()))
    }

    @Test
    fun `map pin titles stay on one compact line`() {
        assertEquals("Palm\u00A0to\u00A0Palm\u00A0|…", compactMapPinTitle("Palm to Palm | 김윤서"))
        assertEquals("움직임의\u00A0궤적\u00A0|…", compactMapPinTitle("움직임의 궤적 | 윤장호, 임윤서"))
        assertEquals("Polyphony", compactMapPinTitle("Polyphony"))
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
    fun `route origin uses the current valid map camera target`() {
        assertEquals(
            GeoPoint(37.5665, 126.9780),
            mapRouteOrigin(Position(latitude = 37.5665, longitude = 126.9780)),
        )
        assertEquals(
            null,
            mapRouteOrigin(Position(latitude = 95.0, longitude = 126.9780)),
        )
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
