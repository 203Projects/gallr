package com.gallr.shared.taste

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.galleryKey
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 101 US3: only a save, a visit or a follow that is new since the hide brings a hidden show back. */
class TasteLiftTest {
    private val hidden = exhibition("hidden", venue = "갤러리 하나", galleryId = "g-1")
    private val other = exhibition("other", venue = "갤러리 둘", galleryId = "g-2")
    private val catalogue = listOf(hidden, other)
    private val hiddenKey = galleryKey(hidden.venueNameKo, hidden.venueNameEn)
    private val before = signals()

    @Test
    fun aNewSaveVisitOrFollowLiftsTheHide() {
        listOf(
            signals(bookmarks = setOf("hidden")),
            signals(visits = setOf("hidden")),
            signals(follows = mapOf(hiddenKey to "g-1")),
            signals(follows = mapOf("another-key" to "g-1")),
        ).forEach { now ->
            assertEquals(setOf("hidden"), liftedDismissals(before, now, setOf("hidden")))
        }
    }

    @Test
    fun signalsThatWereAlreadyThereLiftNothing() {
        val already = signals(bookmarks = setOf("hidden"), follows = mapOf(hiddenKey to null))

        assertTrue(liftedDismissals(already, already, setOf("hidden")).isEmpty())
    }

    @Test
    fun aFollowThatOnlyGainsItsGalleryIdIsTheSameFollow() {
        val withoutId = signals(follows = mapOf(hiddenKey to null))
        val withId = signals(follows = mapOf(hiddenKey to "g-1"))

        assertTrue(liftedDismissals(withoutId, withId, setOf("hidden")).isEmpty())
    }

    @Test
    fun aSignalForAnotherExhibitionOrNothingHiddenLiftsNothing() {
        val now = signals(bookmarks = setOf("other"), follows = mapOf("x" to "g-2"))

        assertTrue(liftedDismissals(before, now, setOf("hidden")).isEmpty())
        assertTrue(liftedDismissals(before, signals(bookmarks = setOf("hidden")), emptySet()).isEmpty())
    }

    @Test
    fun whatTheAccountBroughtIsNotNewHoweverItReachesTheList() {
        val arrived =
            ArrivedHistory(bookmarkedIds = setOf("hidden"), visitedIds = setOf("hidden"), followKeys = setOf(hiddenKey))

        listOf(
            signals(bookmarks = setOf("hidden")),
            signals(visits = setOf("hidden")),
            signals(follows = mapOf(hiddenKey to "g-1")),
        ).forEach { now ->
            assertTrue(liftedDismissals(before, now, setOf("hidden"), arrived).isEmpty())
        }
        // The visitor's own save still lifts beside an arrival that brought something else.
        assertEquals(
            setOf("hidden"),
            liftedDismissals(
                before,
                signals(bookmarks = setOf("hidden"), follows = mapOf(hiddenKey to "g-1")),
                setOf("hidden"),
                ArrivedHistory(followKeys = setOf("another-key")),
            ),
        )
    }

    private fun signals(
        bookmarks: Set<String> = emptySet(),
        visits: Set<String> = emptySet(),
        follows: Map<String, String?> = emptyMap(),
    ) = LiftSignals(catalogue = catalogue, bookmarkedIds = bookmarks, visitedIds = visits, follows = follows)

    private fun exhibition(
        id: String,
        venue: String,
        galleryId: String,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = venue,
        venueNameEn = venue,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 9, 1),
        closingDate = LocalDate(2026, 12, 1),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = galleryId,
    )
}
