package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyExhibitionsTest {
    @Test
    fun `nearest exhibitions sorts by geographic distance and respects limit`() {
        val nearby =
            nearestExhibitions(
                exhibitions =
                    listOf(
                        exhibition("far", latitude = 37.6, longitude = 127.2),
                        exhibition("closest", latitude = 37.5666, longitude = 126.9781),
                        exhibition("second", latitude = 37.57, longitude = 126.99),
                    ),
                origin = GeoPoint(37.5665, 126.9780),
                limit = 2,
            )

        assertEquals(listOf("closest", "second"), nearby.map { it.exhibition.id })
        assertTrue(nearby[0].distanceKm < nearby[1].distanceKm)
    }

    @Test
    fun `nearest exhibitions excludes rows without coordinates`() {
        val result =
            nearestExhibitions(
                exhibitions = listOf(exhibition("missing", latitude = null, longitude = null)),
                origin = GeoPoint(37.5665, 126.9780),
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `nearest exhibitions keep nearby venues distinct`() {
        val first = exhibition("a-first", latitude = 37.5666, longitude = 126.9781)
        val duplicateVenue =
            exhibition("b-duplicate", latitude = 37.5666, longitude = 126.9781).copy(
                venueNameKo = first.venueNameKo,
                venueNameEn = first.venueNameEn,
            )
        val secondVenue = exhibition("c-second", latitude = 37.57, longitude = 126.99)

        val result =
            nearestExhibitions(
                exhibitions = listOf(first, duplicateVenue, secondVenue),
                origin = GeoPoint(37.5665, 126.9780),
                limit = 2,
            )

        assertEquals(listOf("a-first", "c-second"), result.map { it.exhibition.id })
    }

    @Test
    fun `geographic distance is reusable without removing exhibitions at the same venue`() {
        val origin = GeoPoint(37.5219, 127.0411)

        val distance = geographicDistanceKm(origin, GeoPoint(37.5229, 127.0411))

        assertTrue(distance in 0.10..0.12)
    }

    private fun exhibition(
        id: String,
        latitude: Double?,
        longitude: Double?,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = id,
        venueNameEn = id,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
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
