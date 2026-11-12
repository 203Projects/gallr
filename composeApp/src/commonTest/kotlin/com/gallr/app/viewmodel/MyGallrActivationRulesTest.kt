package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MyGallrActivationRulesTest {
    @Test
    fun `gallery search groups stable identities and reports visits`() {
        val catalogue =
            listOf(
                exhibition("kukje-one", galleryId = "kukje", venueEn = "Kukje Gallery"),
                exhibition("kukje-two", galleryId = "kukje", venueEn = "Kukje Gallery"),
                exhibition("pkm", galleryId = "pkm", venueEn = "PKM Gallery"),
            )

        val results =
            catalogue.gallerySearchResults(
                query = "KUKJE",
                language = AppLanguage.EN,
                visitedExhibitionIds = setOf("kukje-one"),
            )

        assertEquals(1, results.size)
        assertEquals("kukje", results.single().candidate.galleryId)
        assertEquals(
            2,
            results
                .single()
                .candidate
                .exhibitions
                .size,
        )
        assertEquals(
            1,
            results.single().visitedCount,
        )
    }

    private fun exhibition(
        id: String,
        galleryId: String = "gallery",
        venueEn: String = "Gallery",
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = venueEn,
        venueNameEn = venueEn,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "삼청",
        regionEn = "Samcheong",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = galleryId,
    )
}
