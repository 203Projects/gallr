package com.gallr.shared.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalMapDestinationTest {
    @Test
    fun `valid coordinates create a localized provider neutral destination`() {
        val exhibition = exhibition(latitude = 37.5665, longitude = 126.9780)

        assertEquals(
            ExternalMapDestination(
                latitude = 37.5665,
                longitude = 126.9780,
                label = "Exhibition — Gallery",
            ),
            exhibition.toExternalMapDestination(AppLanguage.EN),
        )
        assertEquals(
            "전시 — 갤러리",
            exhibition.toExternalMapDestination(AppLanguage.KO)?.label,
        )
    }

    @Test
    fun `missing non finite and out of range coordinates are rejected`() {
        listOf(
            null to 126.9780,
            37.5665 to null,
            Double.NaN to 126.9780,
            37.5665 to Double.POSITIVE_INFINITY,
            -90.0001 to 126.9780,
            90.0001 to 126.9780,
            37.5665 to -180.0001,
            37.5665 to 180.0001,
        ).forEach { (latitude, longitude) ->
            assertNull(exhibition(latitude, longitude).toExternalMapDestination(AppLanguage.EN))
        }
    }

    @Test
    fun `legal coordinate boundaries remain valid`() {
        assertEquals(-90.0, exhibition(-90.0, -180.0).toExternalMapDestination(AppLanguage.EN)?.latitude)
        assertEquals(180.0, exhibition(90.0, 180.0).toExternalMapDestination(AppLanguage.EN)?.longitude)
    }

    private fun exhibition(
        latitude: Double?,
        longitude: Double?,
    ) = Exhibition(
        id = "exhibition-one",
        nameKo = "전시",
        nameEn = "Exhibition",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
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
