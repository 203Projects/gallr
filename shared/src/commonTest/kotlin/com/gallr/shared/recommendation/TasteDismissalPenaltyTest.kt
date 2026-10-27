package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TasteDismissals
import com.gallr.shared.taste.TasteSeed
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Spec 101 FR-012–FR-014: 덜 보기 lowers what it touches by a bounded amount and never flips a signal. */
class TasteDismissalPenaltyTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()
    private val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
    private val artist = ExhibitionArtist("artist-park", "박서보", "Park Seo-Bo")

    private fun dismissed(
        id: String,
        terms: Set<String> = emptySet(),
        artists: Set<String> = emptySet(),
    ) = TasteDismissal(id, LocalDate(2026, 9, 30), terms, artists)

    @Test
    fun `one dismissal of a term lowers a card carrying it by exactly half a saved term`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val dismissals = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf(city.id)))

        val before = score(listOf(saved, candidate), "candidate", TasteDismissals.EMPTY)
        val after = score(listOf(saved, candidate), "candidate", dismissals)

        assertEquals(bp(0.35), before)
        assertEquals(bp(0.175), after)
    }

    @Test
    fun `a second dismissal of the same term adds nothing`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val twice =
            TasteDismissals.EMPTY
                .dismiss(dismissed("one", terms = setOf(city.id)))
                .dismiss(dismissed("two", terms = setOf(city.id)))

        assertEquals(bp(0.175), score(listOf(saved, candidate), "candidate", twice))
    }

    @Test
    fun `one dismissal naming an artist lowers a card crediting them by a quarter and a second adds nothing`() {
        val saved = exhibition("saved", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist))
        val once = TasteDismissals.EMPTY.dismiss(dismissed("one", artists = setOf(artist.id)))
        val twice = once.dismiss(dismissed("two", artists = setOf(artist.id)))

        assertEquals(bp(0.50), score(listOf(saved, candidate), "candidate", TasteDismissals.EMPTY))
        assertEquals(bp(0.25), score(listOf(saved, candidate), "candidate", once))
        assertEquals(bp(0.25), score(listOf(saved, candidate), "candidate", twice))
    }

    @Test
    fun `an artist costs a quarter and penalties add up and a score never goes below zero`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city), artists = listOf(artist))
        val both = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf(city.id), artists = setOf(artist.id)))

        assertEquals(0, score(listOf(saved, candidate), "candidate", both))
    }

    @Test
    fun `a penalised card keeps its personal tier above editorial cards`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city), artists = listOf(artist))
        val editorial = exhibition("editorial", isFeatured = true)
        val both = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf(city.id), artists = setOf(artist.id)))

        val result = recommend(listOf(saved, candidate, editorial), both, bookmarks = setOf(saved.id))

        assertEquals(listOf("candidate", "editorial"), result.map { it.exhibition.id })
        assertTrue(result.first().hasPersonalEvidence)
    }

    @Test
    fun `a dismissed exhibition is hidden from the list`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val hidden = TasteDismissals.EMPTY.dismiss(dismissed("candidate", terms = setOf(city.id)))

        assertTrue(recommend(listOf(saved, candidate), hidden, bookmarks = setOf(saved.id)).isEmpty())
    }

    @Test
    fun `a term found only in the text is not penalised`() {
        val detected = exhibition("detected", descriptionEn = "a minimalist show")
        val catalogue = listOf(detected) + (1..8).map { exhibition("filler-$it", descriptionEn = "loud colour $it") }
        val seed = TasteSeed(termIds = setOf("style:minimalist"))
        val dismissals = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf("style:minimalist")))

        val before = recommend(catalogue, TasteDismissals.EMPTY, seed = seed).single { it.exhibition.id == "detected" }
        val after = recommend(catalogue, dismissals, seed = seed).single { it.exhibition.id == "detected" }

        // The detected pick must score in the first place, or an unchanged score proves nothing.
        assertIs<RecommendationEvidence.ChosenTerm>(before.evidence.first())
        assertTrue(before.scoreBasisPoints > 0)
        assertEquals(before.scoreBasisPoints, after.scoreBasisPoints)
    }

    private fun score(
        catalogue: List<Exhibition>,
        id: String,
        dismissals: TasteDismissals,
    ): Int =
        recommend(catalogue, dismissals, bookmarks = setOf("saved")).single { it.exhibition.id == id }.scoreBasisPoints

    private fun recommend(
        catalogue: List<Exhibition>,
        dismissals: TasteDismissals,
        bookmarks: Set<String> = emptySet(),
        seed: TasteSeed = TasteSeed.EMPTY,
    ): List<ExhibitionRecommendation> =
        recommender.prepare(catalogue, previous = null).recommend(
            RecommendationContext(
                bookmarkedExhibitionIds = bookmarks,
                today = today,
                tasteSeed = seed,
                dismissals = dismissals,
            ),
        )

    /** The ranker's fixed normalisation (sum of its eleven base weights). */
    private fun bp(score: Double): Int = (score / 2.33 * 10_000).roundToInt()

    private fun exhibition(
        id: String,
        artists: List<ExhibitionArtist> = emptyList(),
        artTerms: List<ArtTerm> = emptyList(),
        isFeatured: Boolean = false,
        descriptionEn: String = "",
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 9, 30),
        isFeatured = isFeatured,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = descriptionEn,
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = "gallery-$id",
        artists = artists,
        artTerms = artTerms,
    )
}
