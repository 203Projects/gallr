package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.taste.TasteSeed
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Spec 101 FR-007 and FR-008: what the visitor picks in the taste sheet ranks as their own signal. */
class ChosenAnchorTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()
    private val minimal = ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")
    private val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")

    @Test
    fun `a picked term carried as a reviewed term is named as the visitor's pick at full chosen weight`() {
        val match = exhibition("match", artTerms = listOf(minimal))
        val other = exhibition("other", artTerms = listOf(city))

        val result = recommend(listOf(other, match), TasteSeed(termIds = setOf(minimal.id)))

        val top = result.first()
        assertEquals("match", top.exhibition.id)
        assertEquals(RecommendationEvidence.ChosenTerm(minimal), top.evidence.first())
        assertEquals(basisPoints(0.30), top.scoreBasisPoints)
        assertTrue(top.hasPersonalEvidence)
    }

    @Test
    fun `a picked-term match ranks ahead of an editorial pick`() {
        val featured = exhibition("featured", isFeatured = true)
        val match = exhibition("match", artTerms = listOf(minimal))

        val result = recommend(listOf(featured, match), TasteSeed(termIds = setOf(minimal.id)))

        assertEquals(listOf("match", "featured"), result.map { it.exhibition.id })
    }

    @Test
    fun `a picked term found only in the text counts as a weak rarity scaled guess`() {
        val reviewed = exhibition("reviewed", artTerms = listOf(minimal))
        val detected = exhibition("detected", descriptionEn = "A minimalist show of quiet surfaces")
        val catalogue =
            listOf(reviewed, detected) + (1..8).map { exhibition("filler-$it", descriptionEn = "loud colour") }

        val result = recommend(catalogue, TasteSeed(termIds = setOf(minimal.id)))

        val byId = result.associateBy { it.exhibition.id }
        val guess = byId.getValue("detected")
        assertIs<RecommendationEvidence.ChosenTerm>(guess.evidence.first())
        assertTrue(guess.scoreBasisPoints in 1..basisPoints(0.08))
        assertTrue(byId.getValue("reviewed").scoreBasisPoints > guess.scoreBasisPoints)
    }

    @Test
    fun `a picked term that most of the catalogue mentions is never a reason`() {
        val catalogue = (1..6).map { exhibition("photo-$it", descriptionEn = "photography") }

        val result = recommend(catalogue, TasteSeed(termIds = setOf("medium:photography")))

        assertTrue(
            result.none { recommendation -> recommendation.evidence.any { it is RecommendationEvidence.ChosenTerm } },
        )
    }

    @Test
    fun `a picked exhibition leaves the list and anchors matches at six tenths of a save`() {
        val artist = ExhibitionArtist("artist-park", "박서보", "Park Seo-Bo")
        val picked = exhibition("picked", artists = listOf(artist))
        val sameArtist = exhibition("same-artist", artists = listOf(artist))

        val result = recommend(listOf(picked, sameArtist), TasteSeed(exhibitionIds = setOf(picked.id)))

        assertEquals(listOf("same-artist"), result.map { it.exhibition.id })
        val evidence = assertIs<RecommendationEvidence.ArtistMatch>(result.single().evidence.first())
        assertEquals(RecommendationSignalSource.CHOSEN, evidence.source)
        assertEquals(picked.id, evidence.anchor.exhibitionId)
        assertEquals(basisPoints(0.30), result.single().scoreBasisPoints)
    }

    @Test
    fun `a picked exhibition sharing a reviewed term contributes six tenths of a saved term`() {
        val picked = exhibition("picked", artTerms = listOf(city))
        val sameTerm = exhibition("same-term", artTerms = listOf(city))

        val result = recommend(listOf(picked, sameTerm), TasteSeed(exhibitionIds = setOf(picked.id)))

        val evidence = assertIs<RecommendationEvidence.ArtTermMatch>(result.single().evidence.first())
        assertEquals(RecommendationSignalSource.CHOSEN, evidence.source)
        assertEquals(basisPoints(0.21), result.single().scoreBasisPoints)
    }

    @Test
    fun `a pick that is not in the catalogue is ignored without failing`() {
        val result =
            recommend(
                listOf(exhibition("a", isFeatured = true)),
                TasteSeed(termIds = setOf("theme:unknown"), exhibitionIds = setOf("gone")),
            )

        assertEquals(listOf(RecommendationEvidence.Featured), result.single().evidence)
    }

    @Test
    fun `an empty seed changes nothing`() {
        val catalogue = listOf(exhibition("a", artTerms = listOf(minimal), isFeatured = true), exhibition("b"))

        assertEquals(recommend(catalogue, TasteSeed.EMPTY), recommend(catalogue, null))
    }

    @Test
    fun `ranking with a seed does not depend on catalogue order`() {
        val catalogue =
            listOf(
                exhibition("a", artTerms = listOf(minimal)),
                exhibition("b", artTerms = listOf(minimal, city)),
                exhibition("c", artTerms = listOf(city)),
                exhibition("d", isFeatured = true),
            )
        val seed = TasteSeed(termIds = setOf(minimal.id, city.id))

        assertEquals(recommend(catalogue, seed), recommend(catalogue.reversed(), seed))
    }

    private fun recommend(
        catalogue: List<Exhibition>,
        seed: TasteSeed?,
    ): List<ExhibitionRecommendation> {
        val context =
            if (seed == null) {
                RecommendationContext(today = today)
            } else {
                RecommendationContext(today = today, tasteSeed = seed)
            }
        return recommender.prepare(catalogue, previous = null).recommend(context)
    }

    /** The ranker's fixed normalisation (sum of its eleven base weights). */
    private fun basisPoints(score: Double): Int = (score / RANKER_MAX_SCORE * 10_000).roundToInt()

    private fun exhibition(
        id: String,
        descriptionEn: String = "",
        artists: List<ExhibitionArtist> = emptyList(),
        artTerms: List<ArtTerm> = emptyList(),
        isFeatured: Boolean = false,
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

    private companion object {
        const val RANKER_MAX_SCORE = 2.33
    }
}
