package com.gallr.app

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.map.ExternalMapDestination
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalMapLauncherTest {
    @Test
    fun `valid exhibition delegates one localized destination`() {
        val destinations = mutableListOf<ExternalMapDestination>()
        val launcher =
            ExternalMapLauncher { destination ->
                destinations += destination
                Result.success(Unit)
            }

        val result = openExhibitionInMap(exhibition(37.5665, 126.9780), AppLanguage.EN, launcher)

        assertTrue(result?.isSuccess == true)
        assertEquals(listOf("Exhibition — Gallery"), destinations.map { it.label })
    }

    @Test
    fun `invalid exhibition never reaches the platform launcher`() {
        var launches = 0
        val launcher =
            ExternalMapLauncher {
                launches += 1
                Result.success(Unit)
            }

        val result = openExhibitionInMap(exhibition(null, 126.9780), AppLanguage.EN, launcher)

        assertNull(result)
        assertEquals(0, launches)
    }

    @Test
    fun `platform failure remains explicit`() {
        val failure = IllegalStateException("unavailable")
        val launcher = ExternalMapLauncher { Result.failure(failure) }

        val result = openExhibitionInMap(exhibition(37.5665, 126.9780), AppLanguage.KO, launcher)

        assertEquals(failure, result?.exceptionOrNull())
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
