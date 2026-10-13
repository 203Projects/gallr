package com.gallr.shared.recommendation

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.fixture.DiscoveryFixture
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-004 on the published catalogue snapshot.
 *
 * Generic signals are stripped from a copy of the catalogue so the only evidence a candidate can carry
 * is text similarity to the saved anchor; the ranking is read through `rankRouteCandidates` with a
 * radius that covers the whole catalogue because it has no result limit or diversity pass.
 */
class DiscoveryFixtureSimilarityTest {
    private val textOnlyCatalogue: List<Exhibition> =
        DiscoveryFixture.exhibitions.map { exhibition ->
            exhibition.copy(
                isFeatured = false,
                editorId = null,
                closingDate = LocalDate(2027, 12, 31),
            )
        }
    private val index = LocalExhibitionRecommender().prepare(textOnlyCatalogue)

    @Test
    fun textSimilarPairShare() {
        val ids = textOnlyCatalogue.map { it.id }
        var similarPairs = 0
        ids.forEach { savedId ->
            rankFromSave(savedId).forEach { relevance ->
                val isOther = relevance.exhibition.id != savedId
                val isTextSimilar = relevance.evidence.any { it is RecommendationEvidence.TextSimilarity }
                if (isOther && isTextSimilar) similarPairs += 1
            }
        }
        val orderedPairs = ids.size * (ids.size - 1)
        val share = similarPairs.toDouble() / orderedPairs

        assertTrue(share <= 0.10, "text-similar share $share ($similarPairs of $orderedPairs ordered pairs)")
    }

    @Test
    fun sameArtistShowsAreMostSimilar() {
        val ropacBaselitz = "76c3989a-73e3-4521-a5ce-3de0cee4dce4"
        val sehwaBaselitz = "a7d50f6b-d4c7-43f1-99b1-63026e305473"

        assertEquals(sehwaBaselitz, strongestTextMatch(ropacBaselitz))
        assertEquals(ropacBaselitz, strongestTextMatch(sehwaBaselitz))
    }

    private fun strongestTextMatch(savedId: String): String? =
        rankFromSave(savedId)
            .filter { it.exhibition.id != savedId }
            .firstOrNull { it.evidence.any { evidence -> evidence is RecommendationEvidence.TextSimilarity } }
            ?.exhibition
            ?.id

    private fun rankFromSave(savedId: String): List<RouteRelevance> =
        index.rankRouteCandidates(
            RouteRelevanceContext(
                bookmarkedExhibitionIds = setOf(savedId),
                origin = FAR_FROM_EVERY_VENUE,
                maxDistanceKm = WHOLE_CATALOGUE_RADIUS_KM,
                today = DiscoveryFixture.referenceDate,
            ),
        )

    private companion object {
        /** Open sea south-west of Jeju: no venue is within the 5 km proximity window. */
        val FAR_FROM_EVERY_VENUE = GeoPoint(32.0, 124.0)
        const val WHOLE_CATALOGUE_RADIUS_KM = 2_000.0
    }
}
