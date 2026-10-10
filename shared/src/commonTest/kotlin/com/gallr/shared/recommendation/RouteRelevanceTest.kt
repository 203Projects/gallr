package com.gallr.shared.recommendation

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Route candidate ranking is the second query on the prepared index. Unlike `recommend()` it keeps
 * saved and zero-evidence exhibitions, so a route can always be filled from what is open nearby.
 * The contract has no access to promotion state by construction.
 */
class RouteRelevanceTest {
    private val today = LocalDate(2026, 8, 30)
    private val origin = GeoPoint(37.5665, 126.9780)
    private val recommender = LocalExhibitionRecommender()

    @Test
    fun `only exhibitions within the radius and visible today are ranked`() {
        val near = exhibition("near", latitude = 37.5670)
        val far = exhibition("far", latitude = 37.7000)
        val ended = exhibition("ended", latitude = 37.5671, closingDate = LocalDate(2026, 8, 29))
        val tooFarAhead = exhibition("future", latitude = 37.5672, openingDate = LocalDate(2026, 9, 20))

        val ranked = rank(listOf(far, ended, tooFarAhead, near), context())

        assertEquals(listOf("near"), ranked.map { it.exhibition.id })
    }

    @Test
    fun `bookmarked exhibitions are kept as saved stops without inferred taste evidence`() {
        val artist = ExhibitionArtist("artist-shared", "공통", "Shared")
        val saved = exhibition("saved", latitude = 37.5670, artists = listOf(artist))
        val sameArtist = exhibition("same-artist", latitude = 37.5671, artists = listOf(artist))

        val ranked = rank(listOf(sameArtist, saved), context(bookmarks = setOf(saved.id)))

        val savedRelevance = ranked.first { it.exhibition.id == saved.id }
        assertEquals(RecommendationEvidence.Saved, savedRelevance.evidence.first())
        assertFalse(savedRelevance.evidence.any { it.isTasteEvidence() })
        assertTrue(savedRelevance.hasPersonalEvidence)

        val matchRelevance = ranked.first { it.exhibition.id == sameArtist.id }
        assertIs<RecommendationEvidence.ArtistMatch>(matchRelevance.evidence.first())
        assertTrue(matchRelevance.hasPersonalEvidence)
    }

    @Test
    fun `visited exhibitions are excluded`() {
        val visited = exhibition("visited", latitude = 37.5670)
        val other = exhibition("other", latitude = 37.5671)

        val ranked = rank(listOf(visited, other), context(visits = listOf(visit(visited))))

        assertEquals(listOf("other"), ranked.map { it.exhibition.id })
    }

    @Test
    fun `candidates without any evidence are returned as non personal fillers`() {
        val plain = exhibition("plain", latitude = 37.5965)

        val ranked = rank(listOf(plain), context())

        val relevance = ranked.single()
        assertTrue(relevance.evidence.isEmpty())
        assertFalse(relevance.hasPersonalEvidence)
        assertTrue(relevance.scoreBasisPoints in 0..10_000)
    }

    @Test
    fun `followed gallery and generic signals mark personal evidence truthfully`() {
        val followed = exhibition("followed", latitude = 37.5670, galleryId = "gallery-one")
        val featured = exhibition("featured", latitude = 37.5671, galleryId = "gallery-two", isFeatured = true)

        val ranked = rank(listOf(featured, followed), context(follows = listOf(follow("gallery-one"))))

        val followedRelevance = ranked.first { it.exhibition.id == followed.id }
        assertTrue(RecommendationEvidence.FollowedGallery in followedRelevance.evidence)
        assertTrue(followedRelevance.hasPersonalEvidence)

        val featuredRelevance = ranked.first { it.exhibition.id == featured.id }
        assertTrue(RecommendationEvidence.Featured in featuredRelevance.evidence)
        assertFalse(featuredRelevance.hasPersonalEvidence)
    }

    @Test
    fun `evidence is capped at two entries and saved comes first`() {
        val saved =
            exhibition(
                "saved",
                latitude = 37.5666,
                isFeatured = true,
                editorId = "editor",
                closingDate = LocalDate(2026, 9, 2),
            )

        val relevance = rank(listOf(saved), context(bookmarks = setOf(saved.id))).single()

        assertEquals(2, relevance.evidence.size)
        assertEquals(RecommendationEvidence.Saved, relevance.evidence.first())
    }

    @Test
    fun `ranking is score descending then id and independent of input order`() {
        val catalogue =
            listOf(
                exhibition("c", latitude = 37.5670, isFeatured = true),
                exhibition("a", latitude = 37.5671),
                exhibition("b", latitude = 37.5671),
                exhibition("d", latitude = 37.5672, editorId = "editor"),
            )

        val forward = rank(catalogue, context())
        val reversed = rank(catalogue.reversed(), context())

        assertEquals(forward, reversed)
        assertEquals("c", forward.first().exhibition.id)
        val scores = forward.map { it.scoreBasisPoints }
        assertEquals(scores.sortedDescending(), scores)
        val tied = forward.filter { it.exhibition.id in setOf("a", "b") }
        assertEquals(listOf("a", "b"), tied.map { it.exhibition.id })
    }

    @Test
    fun `route relevance context rejects a non positive radius`() {
        assertFailsWith<IllegalArgumentException> { context(maxDistanceKm = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            RouteRelevance(
                exhibition = exhibition("x"),
                scoreBasisPoints = 10_001,
                evidence = emptyList(),
                hasPersonalEvidence = false,
            )
        }
    }

    private fun RecommendationEvidence.isTasteEvidence(): Boolean =
        this is RecommendationEvidence.ArtistMatch ||
            this is RecommendationEvidence.ArtTermMatch ||
            this is RecommendationEvidence.TextSimilarity

    private fun rank(
        catalogue: List<Exhibition>,
        context: RouteRelevanceContext,
    ): List<RouteRelevance> = recommender.prepare(catalogue).rankRouteCandidates(context)

    private fun context(
        bookmarks: Set<String> = emptySet(),
        visits: List<ExhibitionVisit> = emptyList(),
        follows: List<FollowedGallery> = emptyList(),
        maxDistanceKm: Double = 5.0,
    ) = RouteRelevanceContext(
        bookmarkedExhibitionIds = bookmarks,
        visits = visits,
        followedGalleries = follows,
        origin = origin,
        maxDistanceKm = maxDistanceKm,
        today = today,
    )

    private fun visit(exhibition: Exhibition) =
        ExhibitionVisit(
            clientRecordId = "visit-${exhibition.id}",
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
        )

    private fun follow(galleryId: String) =
        FollowedGallery(
            galleryKey = "gallery\u001fgallery",
            snapshot = FollowedGallerySnapshot("갤러리", "Gallery", "서울", "Seoul", "종로구", "Jongno"),
            knownExhibitionIds = emptySet(),
            followedAt = Instant.parse("2026-08-20T00:00:00Z"),
            galleryId = galleryId,
        )

    private fun exhibition(
        id: String,
        latitude: Double = 37.5670,
        longitude: Double = 126.9780,
        galleryId: String? = "gallery-$id",
        isFeatured: Boolean = false,
        editorId: String? = null,
        openingDate: LocalDate = LocalDate(2026, 8, 1),
        closingDate: LocalDate = LocalDate(2026, 9, 30),
        artists: List<ExhibitionArtist> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리 $galleryId",
        venueNameEn = "Gallery $galleryId",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = isFeatured,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
        galleryId = galleryId,
        artists = artists,
    )
}
