package com.gallr.shared.recommendation

import com.gallr.shared.fixture.DiscoveryFixture
import kotlin.test.Test
import kotlin.test.assertTrue

/** SC-005 and SC-006: taste scenarios on the published catalogue snapshot. */
class DiscoveryFixtureTasteTest {
    private val index = LocalExhibitionRecommender().prepare(DiscoveryFixture.exhibitions)

    @Test
    fun savingOneBaselitzShowSurfacesTheOther() {
        assertScenario(saved = setOf(ROPAC_BASELITZ), expectedInTopThree = SEHWA_BASELITZ)
        assertScenario(saved = setOf(SEHWA_BASELITZ), expectedInTopThree = ROPAC_BASELITZ)
    }

    @Test
    fun aTermOnNearlyHalfTheCatalogueIsNeverTheReason() {
        val results = recommend(saved = setOf(SEHWA_BASELITZ)) + recommend(saved = setOf(ROPAC_BASELITZ))

        // Both Baselitz shows read as 회화 from their text, as do 35 of 78 published shows.
        val paintingReasons =
            results.flatMap { it.evidence }.filter { evidence ->
                evidence is RecommendationEvidence.ArtTermMatch && evidence.term.id == "medium:painting"
            }
        assertTrue(paintingReasons.isEmpty(), paintingReasons.toString())
    }

    @Test
    fun savingOneParkSeoBoShowSurfacesTheOther() {
        assertScenario(saved = setOf(KUKJE_PARK_SEO_BO), expectedInTopThree = PARKSEOBO_MUSEUM)
    }

    @Test
    fun savingATaggedPaintingShowSurfacesAnotherTaggedPaintingShow() {
        val results = recommend(saved = setOf(TAGGED_PAINTING_SHOW))
        val tagged = results.filter { it.exhibition.id in TAGGED_PAINTING_PEERS }

        assertTrue(tagged.isNotEmpty(), results.map { it.exhibition.id }.toString())
        assertTrue(
            tagged.all { recommendation ->
                recommendation.evidence.any { it is RecommendationEvidence.ArtTermMatch }
            },
            tagged.map { it.evidence }.toString(),
        )
        assertReasonsAreGrounded(results, saved = setOf(TAGGED_PAINTING_SHOW))
    }

    @Test
    fun noHistoryShowsNoTasteReason() {
        val results = recommend(saved = emptySet())

        assertTrue(results.isNotEmpty())
        results.forEach { recommendation ->
            assertTrue(recommendation.evidence.none { it.isTasteEvidence() }, recommendation.evidence.toString())
        }
    }

    private fun assertScenario(
        saved: Set<String>,
        expectedInTopThree: String,
    ) {
        val results = recommend(saved)
        val topThree = results.take(3).map { it.exhibition.id }

        assertTrue(expectedInTopThree in topThree, "saved=$saved top=$topThree")
        assertReasonsAreGrounded(results, saved)
    }

    /** SC-006: every taste reason points at a source the visitor actually saved. */
    private fun assertReasonsAreGrounded(
        results: List<ExhibitionRecommendation>,
        saved: Set<String>,
    ) {
        results.forEach { recommendation ->
            recommendation.evidence.forEach { evidence ->
                val anchor =
                    when (evidence) {
                        is RecommendationEvidence.ArtistMatch -> evidence.anchor.exhibitionId
                        is RecommendationEvidence.ArtTermMatch -> evidence.anchor.exhibitionId
                        is RecommendationEvidence.TextSimilarity -> evidence.anchor.exhibitionId
                        else -> null
                    } ?: return@forEach
                assertTrue(anchor in saved, "$evidence is not grounded in $saved")
            }
        }
    }

    private fun recommend(saved: Set<String>): List<ExhibitionRecommendation> =
        index.recommend(
            RecommendationContext(
                bookmarkedExhibitionIds = saved,
                today = DiscoveryFixture.referenceDate,
                limit = 6,
            ),
        )

    private fun RecommendationEvidence.isTasteEvidence(): Boolean =
        this is RecommendationEvidence.ArtistMatch ||
            this is RecommendationEvidence.ArtTermMatch ||
            this is RecommendationEvidence.TextSimilarity

    private companion object {
        const val ROPAC_BASELITZ = "76c3989a-73e3-4521-a5ce-3de0cee4dce4"
        const val SEHWA_BASELITZ = "a7d50f6b-d4c7-43f1-99b1-63026e305473"
        const val KUKJE_PARK_SEO_BO = "12109f7b-e29d-4342-ac6b-440760ab0130"
        const val PARKSEOBO_MUSEUM = "127630bb-7a83-4fd2-95f0-e4ca3bdad48b"
        const val TAGGED_PAINTING_SHOW = "ad149377-67a8-474b-9f35-48855007cde3"
        val TAGGED_PAINTING_PEERS =
            setOf(
                "c81c8e08-abd4-4290-8cb1-cde6aeaf50a9",
                "e17f7cb3-f10a-44bb-80b1-0a6010d3470a",
            )
    }
}
