package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.taste.TasteSeed
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Spec 101 FR-010: the visitor's picks are named first, then saves, then visits; within one, artist, term, text. */
class ReasonOrderTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()
    private val artist = ExhibitionArtist("artist-park", "박서보", "Park Seo-Bo")
    private val city = ArtTerm("theme:city", ArtTermCategory.THEME, "도시", "City")
    private val minimal = ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")

    @Test
    fun `a pick is named before a save even when the save weighs more`() {
        val saved = exhibition("saved", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist), artTerms = listOf(minimal))

        val evidence =
            recommend(
                listOf(saved, candidate),
                bookmarks = setOf(saved.id),
                seed = TasteSeed(termIds = setOf(minimal.id)),
            ).single().evidence

        assertEquals(RecommendationEvidence.ChosenTerm(minimal), evidence.first())
        val second = assertIs<RecommendationEvidence.ArtistMatch>(evidence[1])
        assertEquals(RecommendationSignalSource.SAVED, second.source)
    }

    @Test
    fun `a save is named before a visit`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val visited = exhibition("visited", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist), artTerms = listOf(city))

        val evidence =
            recommend(
                listOf(saved, visited, candidate),
                bookmarks = setOf(saved.id),
                visits = listOf(visited),
            ).single().evidence

        val first = assertIs<RecommendationEvidence.ArtTermMatch>(evidence.first())
        assertEquals(RecommendationSignalSource.SAVED, first.source)
        val second = assertIs<RecommendationEvidence.ArtistMatch>(evidence[1])
        assertEquals(RecommendationSignalSource.VISITED, second.source)
    }

    @Test
    fun `within one source an artist comes before a term`() {
        val saved = exhibition("saved", artists = listOf(artist), artTerms = listOf(city))
        val candidate = exhibition("candidate", artists = listOf(artist), artTerms = listOf(city))

        val evidence = recommend(listOf(saved, candidate), bookmarks = setOf(saved.id)).single().evidence

        assertIs<RecommendationEvidence.ArtistMatch>(evidence.first())
        assertIs<RecommendationEvidence.ArtTermMatch>(evidence[1])
        assertTrue(evidence.size <= 2)
    }

    @Test
    fun `a followed gallery is named before editorial reasons`() {
        val candidate = exhibition("candidate", isFeatured = true, galleryId = "gallery-followed")

        val evidence =
            recommender
                .prepare(listOf(candidate), previous = null)
                .recommend(
                    RecommendationContext(
                        today = today,
                        followedGalleries =
                            listOf(
                                com.gallr.shared.data.model.FollowedGallery(
                                    galleryKey = "gallery-followed",
                                    galleryId = "gallery-followed",
                                    snapshot =
                                        com.gallr.shared.data.model.FollowedGallerySnapshot(
                                            nameKo = "갤러리",
                                            nameEn = "Gallery",
                                            cityKo = "서울",
                                            cityEn = "Seoul",
                                            regionKo = "종로구",
                                            regionEn = "Jongno-gu",
                                        ),
                                    knownExhibitionIds = emptySet(),
                                    followedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                                ),
                            ),
                    ),
                ).single()
                .evidence

        assertEquals(listOf(RecommendationEvidence.FollowedGallery, RecommendationEvidence.Featured), evidence)
    }

    private fun recommend(
        catalogue: List<Exhibition>,
        bookmarks: Set<String> = emptySet(),
        visits: List<Exhibition> = emptyList(),
        seed: TasteSeed = TasteSeed.EMPTY,
    ): List<ExhibitionRecommendation> =
        recommender.prepare(catalogue, previous = null).recommend(
            RecommendationContext(
                bookmarkedExhibitionIds = bookmarks,
                visits =
                    visits.map {
                        com.gallr.shared.data.model.ExhibitionVisit(
                            clientRecordId = "visit-${it.id}",
                            exhibitionId = it.id,
                            snapshot =
                                com.gallr.shared.data.model.ExhibitionVisitSnapshot
                                    .from(it),
                            createdAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                        )
                    },
                today = today,
                tasteSeed = seed,
            ),
        )

    private fun exhibition(
        id: String,
        artists: List<ExhibitionArtist> = emptyList(),
        artTerms: List<ArtTerm> = emptyList(),
        isFeatured: Boolean = false,
        galleryId: String = "gallery-$id",
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
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = galleryId,
        artists = artists,
        artTerms = artTerms,
    )
}
