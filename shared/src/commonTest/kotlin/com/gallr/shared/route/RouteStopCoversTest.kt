package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteStopCoversTest {
    @Test
    fun `stops take their cover from the catalogue by exhibition id`() {
        val catalogue =
            listOf(
                exhibition("a", "a.jpg"),
                exhibition("b", null),
                exhibition("blank", " "),
                exhibition("not-a-stop", "x.jpg"),
            )

        val covers = routeStopCovers(listOf(stop("a"), stop("b"), stop("gone"), stop("blank")), catalogue)

        assertEquals(mapOf("a" to "a.jpg"), covers)
    }

    @Test
    fun `no stops means no covers`() {
        assertEquals(emptyMap(), routeStopCovers(emptyList(), listOf(exhibition("a", "a.jpg"))))
    }

    private fun stop(id: String) =
        PersonalRouteStop(
            exhibitionId = id,
            nameKo = id,
            nameEn = id,
            venueNameKo = "장소",
            venueNameEn = "Venue",
            point = GeoPoint(37.57, 126.98),
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            cityKo = "서울",
        )

    private fun exhibition(
        id: String,
        cover: String?,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소",
        venueNameEn = "Venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 12, 1),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = cover,
    )
}
