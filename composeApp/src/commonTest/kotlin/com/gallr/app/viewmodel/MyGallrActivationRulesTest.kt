package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MyGallrActivationRulesTest {
    @Test
    fun `visit prompt is limited to ended unrecorded exhibitions`() {
        val ended = exhibition("ended", closingDate = LocalDate(2026, 8, 10))
        val current = exhibition("current", closingDate = LocalDate(2026, 8, 20))
        val today = LocalDate(2026, 8, 14)

        assertTrue(shouldOfferVisitPrompt(ended, today, isVisited = false))
        assertFalse(shouldOfferVisitPrompt(ended, today, isVisited = true))
        assertFalse(shouldOfferVisitPrompt(current, today, isVisited = false))
    }

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
        closingDate: LocalDate = LocalDate(2026, 8, 31),
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
        closingDate = closingDate,
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
