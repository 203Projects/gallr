package com.gallr.shared.taste

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 101 FR-012–FR-016: one record per dismissed exhibition; counts are derived, never stored. */
class TasteDismissalsTest {
    private val today = LocalDate(2026, 10, 26)

    private fun record(
        id: String,
        terms: Set<String> = emptySet(),
        artists: Set<String> = emptySet(),
        closing: LocalDate = LocalDate(2026, 11, 30),
    ) = TasteDismissal(exhibitionId = id, closingDate = closing, termIds = terms, artistIds = artists)

    @Test
    fun countsAreDerivedFromStandingRecords() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("a", terms = setOf("theme:body", "medium:painting"), artists = setOf("artist-1")))
                .dismiss(record("b", terms = setOf("theme:body")))

        assertEquals(2, dismissals.termCount("theme:body"))
        assertEquals(1, dismissals.termCount("medium:painting"))
        assertEquals(1, dismissals.artistCount("artist-1"))
        assertEquals(0, dismissals.termCount("mood:quiet-meditative"))
        assertEquals(setOf("a", "b"), dismissals.hiddenExhibitionIds)
        assertEquals(setOf("theme:body", "medium:painting"), dismissals.dismissedTermIds)
    }

    @Test
    fun dismissingTheSameExhibitionAgainReplacesItsRecord() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("a", terms = setOf("theme:body")))
                .dismiss(record("a", terms = setOf("theme:city")))

        assertEquals(1, dismissals.records.size)
        assertEquals(0, dismissals.termCount("theme:body"))
        assertEquals(1, dismissals.termCount("theme:city"))
    }

    @Test
    fun removingARecordUndoesOrLiftsItEntirely() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("a", terms = setOf("theme:body"), artists = setOf("artist-1")))
                .dismiss(record("b", terms = setOf("theme:body")))
                .remove(setOf("a"))

        assertEquals(setOf("b"), dismissals.hiddenExhibitionIds)
        assertEquals(1, dismissals.termCount("theme:body"))
        assertEquals(0, dismissals.artistCount("artist-1"))
    }

    @Test
    fun releasingATermClearsItEverywhereAndKeepsTheHides() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("a", terms = setOf("theme:body", "medium:painting")))
                .dismiss(record("b", terms = setOf("theme:body")))
                .releaseTerm("theme:body")

        assertEquals(0, dismissals.termCount("theme:body"))
        assertEquals(1, dismissals.termCount("medium:painting"))
        assertEquals(setOf("a", "b"), dismissals.hiddenExhibitionIds)
        assertEquals(setOf("medium:painting"), dismissals.dismissedTermIds)
    }

    @Test
    fun aLiftAfterAReleaseNeverLeavesANegativeCount() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("a", terms = setOf("theme:body")))
                .releaseTerm("theme:body")
                .remove(setOf("a"))

        assertEquals(0, dismissals.termCount("theme:body"))
        assertTrue(dismissals.records.isEmpty())
    }

    @Test
    fun recordsExpireTheDayAfterTheExhibitionCloses() {
        val dismissals =
            TasteDismissals.EMPTY
                .dismiss(record("closed", terms = setOf("theme:body"), closing = LocalDate(2026, 10, 25)))
                .dismiss(record("last-day", terms = setOf("theme:city"), closing = today))
                .expire(today)

        assertEquals(setOf("last-day"), dismissals.hiddenExhibitionIds)
        assertEquals(0, dismissals.termCount("theme:body"))
    }

    @Test
    fun recordOrderIsStableWhateverTheOrderOfDismissal() {
        val forward = TasteDismissals.EMPTY.dismiss(record("a")).dismiss(record("b"))
        val backward = TasteDismissals.EMPTY.dismiss(record("b")).dismiss(record("a"))

        assertEquals(forward, backward)
    }

    @Test
    fun theStoredCountsFollowEveryTransition() {
        val terms = listOf("theme:body", "theme:city", "medium:painting")
        val artists = listOf("artist-1", "artist-2")
        val steps =
            listOf<(TasteDismissals) -> TasteDismissals>(
                { it.dismiss(record("a", terms = setOf("theme:body", "theme:city"), artists = setOf("artist-1"))) },
                { it.dismiss(record("b", terms = setOf("theme:body"), artists = setOf("artist-1", "artist-2"))) },
                { it.dismiss(record("c", terms = setOf("medium:painting"), closing = LocalDate(2026, 10, 25))) },
                { it.dismiss(record("a", terms = setOf("theme:city"))) },
                { it.releaseTerm("theme:city") },
                { it.expire(today) },
                { it.remove(setOf("b")) },
            )
        val expected =
            listOf(
                Counts(setOf("a"), mapOf("theme:body" to 1, "theme:city" to 1), mapOf("artist-1" to 1)),
                Counts(
                    setOf("a", "b"),
                    mapOf("theme:body" to 2, "theme:city" to 1),
                    mapOf("artist-1" to 2, "artist-2" to 1),
                ),
                Counts(
                    setOf("a", "b", "c"),
                    mapOf("theme:body" to 2, "theme:city" to 1, "medium:painting" to 1),
                    mapOf("artist-1" to 2, "artist-2" to 1),
                ),
                Counts(
                    setOf("a", "b", "c"),
                    mapOf("theme:body" to 1, "theme:city" to 1, "medium:painting" to 1),
                    mapOf("artist-1" to 1, "artist-2" to 1),
                ),
                Counts(
                    setOf("a", "b", "c"),
                    mapOf("theme:body" to 1, "medium:painting" to 1),
                    mapOf("artist-1" to 1, "artist-2" to 1),
                ),
                Counts(setOf("a", "b"), mapOf("theme:body" to 1), mapOf("artist-1" to 1, "artist-2" to 1)),
                Counts(setOf("a"), emptyMap(), emptyMap()),
            )

        var dismissals = TasteDismissals.EMPTY
        steps.zip(expected).forEachIndexed { index, (step, want) ->
            dismissals = step(dismissals)
            assertEquals(want.hidden, dismissals.hiddenExhibitionIds, "hidden after step $index")
            terms.forEach { id ->
                assertEquals(want.terms[id] ?: 0, dismissals.termCount(id), "term $id after step $index")
            }
            artists.forEach { id ->
                assertEquals(want.artists[id] ?: 0, dismissals.artistCount(id), "artist $id after step $index")
            }
            assertEquals(want.terms.keys, dismissals.dismissedTermIds, "dismissed terms after step $index")
        }
    }

    private data class Counts(
        val hidden: Set<String>,
        val terms: Map<String, Int>,
        val artists: Map<String, Int>,
    )

    @Test
    fun aSeedCountsItsSelections() {
        assertTrue(TasteSeed.EMPTY.isEmpty)
        assertEquals(3, TasteSeed(termIds = setOf("theme:body", "style:minimalist"), exhibitionIds = setOf("x")).size)
    }
}
