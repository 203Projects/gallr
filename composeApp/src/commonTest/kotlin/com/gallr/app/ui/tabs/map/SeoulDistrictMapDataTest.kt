package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position
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

    @Test
    fun `two exhibitions at one place become two features with their own covers at neighbourhood zoom`() {
        val pins =
            exhibitionMapPins(
                listOf(
                    exhibition("a", latitude = 37.53, longitude = 127.0, coverImageUrl = "https://img/a.jpg"),
                    exhibition("b", latitude = 37.53, longitude = 127.0, coverImageUrl = "https://img/b.jpg"),
                ),
            )
        val arranged =
            arrangeMapPins(
                candidates = pins.map { PinVisualCandidate(it.exhibition.id, xPx = 100f, yPx = 100f) },
                zoom = PIN_SPREAD_FROM_ZOOM,
                groupingThresholdPx = 28f,
                columnPx = 52f,
                rowPx = 56f,
            )

        val groups =
            pinGroupsFromArrangement(arranged, pins.associateBy { it.exhibition.id }) { x, y ->
                Position(latitude = y.toDouble(), longitude = x.toDouble())
            }
        val features = pinFeatureCollection(groups.map { it.group }, emptySet(), AppLanguage.KO).features

        assertEquals(2, groups.size)
        assertEquals(2, features.map { it.id }.distinct().size)
        val images = features.map { it.properties.getValue("image").jsonPrimitive.content }
        assertTrue(images.any { "https://img/a.jpg" in it } && images.any { "https://img/b.jpg" in it }, "$images")
        assertTrue(features.none { it.properties.getValue("is_group").jsonPrimitive.boolean })
        assertEquals(groups.map { it.xPx }.distinct().size, 2, "the two pins are drawn apart")
    }

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
        coverImageUrl: String? = null,
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
        coverImageUrl = coverImageUrl,
    )
}
