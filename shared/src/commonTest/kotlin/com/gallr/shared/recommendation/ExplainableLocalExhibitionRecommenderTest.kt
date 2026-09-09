package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ExplainableLocalExhibitionRecommenderTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()

    @Test
    fun `exact canonical artist leads free text and carries a saved anchor`() {
        val artist = ExhibitionArtist("artist-kimsooja", "김수자", "Kimsooja")
        val saved = exhibition("saved", descriptionEn = "ceramic vessels", artists = listOf(artist))
        val sameArtist = exhibition("same-artist", descriptionEn = "unrelated light", artists = listOf(artist))
        val sameText = exhibition("same-text", descriptionEn = "ceramic vessels")

        val result = recommend(listOf(sameText, sameArtist, saved), bookmarks = setOf(saved.id))

        assertEquals(sameArtist.id, result.first().exhibition.id)
        val evidence = assertIs<RecommendationEvidence.ArtistMatch>(result.first().evidence.first())
        assertEquals(RecommendationSignalSource.SAVED, evidence.source)
        assertEquals(saved.id, evidence.anchor.exhibitionId)
        assertEquals(artist, evidence.artist)
    }

    @Test
    fun `controlled term identity leads text fallback and preserves category`() {
        val term = ArtTerm("mood:quiet", ArtTermCategory.MOOD, "고요함", "Quiet / meditative")
        val saved = exhibition("saved", descriptionEn = "bright painting", artTerms = listOf(term))
        val termMatch = exhibition("term-match", descriptionEn = "video", artTerms = listOf(term))
        val textMatch = exhibition("text-match", descriptionEn = "bright painting")

        val result = recommend(listOf(textMatch, termMatch, saved), bookmarks = setOf(saved.id))

        assertEquals(termMatch.id, result.first().exhibition.id)
        val evidence = assertIs<RecommendationEvidence.ArtTermMatch>(result.first().evidence.first())
        assertEquals(term, evidence.term)
        assertEquals(saved.id, evidence.anchor.exhibitionId)
    }

    @Test
    fun `one artist in a group show receives less credit than a solo exact match`() {
        val shared = ExhibitionArtist("artist-shared", "공통", "Shared")
        val saved = exhibition("saved", artists = listOf(shared))
        val solo = exhibition("solo", artists = listOf(shared), galleryId = "solo-gallery")
        val group =
            exhibition(
                "group",
                artists =
                    listOf(
                        shared,
                        ExhibitionArtist("artist-2", "둘", "Two"),
                        ExhibitionArtist("artist-3", "셋", "Three"),
                        ExhibitionArtist("artist-4", "넷", "Four"),
                    ),
                galleryId = "group-gallery",
            )

        val result = recommend(listOf(group, solo, saved), bookmarks = setOf(saved.id))

        assertEquals(listOf("solo", "group"), result.map { it.exhibition.id })
        assertTrue(result.first().scoreBasisPoints > result.last().scoreBasisPoints)
    }

    @Test
    fun `saved group to candidate solo artist overlap is symmetrically diluted`() {
        val shared = ExhibitionArtist("artist-shared", "공통", "Shared")
        val groupAnchor =
            exhibition(
                "qqqq",
                artists =
                    listOf(
                        shared,
                        ExhibitionArtist("artist-2", "둘", "Two"),
                        ExhibitionArtist("artist-3", "셋", "Three"),
                        ExhibitionArtist("artist-4", "넷", "Four"),
                    ),
            )
        val soloAnchor = exhibition("rrrr", artists = listOf(shared))
        val candidate = exhibition("ssss", artists = listOf(shared))
        val prepared = recommender.prepare(listOf(candidate, groupAnchor, soloAnchor))

        val groupScore =
            prepared
                .recommend(context(bookmarks = setOf(groupAnchor.id)))
                .first { it.exhibition.id == candidate.id }
                .scoreBasisPoints
        val soloScore =
            prepared
                .recommend(context(bookmarks = setOf(soloAnchor.id)))
                .first { it.exhibition.id == candidate.id }
                .scoreBasisPoints
        val reverseScore =
            prepared
                .recommend(context(bookmarks = setOf(candidate.id)))
                .first { it.exhibition.id == groupAnchor.id }
                .scoreBasisPoints

        assertTrue(groupScore < soloScore, "group=$groupScore solo=$soloScore")
        assertEquals(groupScore, reverseScore)
    }

    @Test
    fun `saved broad taxonomy to candidate single term overlap is symmetrically diluted`() {
        val shared = ArtTerm("mood:shared", ArtTermCategory.MOOD, "공통", "Shared")
        val broadAnchor =
            exhibition(
                "tttt",
                artTerms =
                    listOf(
                        shared,
                        ArtTerm("mood:two", ArtTermCategory.MOOD, "둘", "Two"),
                        ArtTerm("mood:three", ArtTermCategory.MOOD, "셋", "Three"),
                        ArtTerm("mood:four", ArtTermCategory.MOOD, "넷", "Four"),
                    ),
            )
        val singleAnchor = exhibition("uuuu", artTerms = listOf(shared))
        val candidate = exhibition("vvvv", artTerms = listOf(shared))
        val prepared = recommender.prepare(listOf(candidate, broadAnchor, singleAnchor))

        val broadScore =
            prepared
                .recommend(context(bookmarks = setOf(broadAnchor.id)))
                .first { it.exhibition.id == candidate.id }
                .scoreBasisPoints
        val singleScore =
            prepared
                .recommend(context(bookmarks = setOf(singleAnchor.id)))
                .first { it.exhibition.id == candidate.id }
                .scoreBasisPoints
        val reverseScore =
            prepared
                .recommend(context(bookmarks = setOf(candidate.id)))
                .first { it.exhibition.id == broadAnchor.id }
                .scoreBasisPoints

        assertTrue(broadScore < singleScore, "broad=$broadScore single=$singleScore")
        assertEquals(broadScore, reverseScore)
    }

    @Test
    fun `structured evidence remains ahead of text after symmetric dilution`() {
        val shared = ArtTerm("style:shared", ArtTermCategory.STYLE, "공통", "Shared")
        val saved =
            exhibition(
                "saved",
                descriptionEn = "identical repeated content for a strong text similarity",
                artTerms =
                    listOf(
                        shared,
                        ArtTerm("style:two", ArtTermCategory.STYLE, "둘", "Two"),
                        ArtTerm("style:three", ArtTermCategory.STYLE, "셋", "Three"),
                        ArtTerm("style:four", ArtTermCategory.STYLE, "넷", "Four"),
                    ),
            )
        val candidate =
            exhibition(
                "candidate",
                descriptionEn = "identical repeated content for a strong text similarity",
                artTerms = listOf(shared),
            )

        val result = recommend(listOf(candidate, saved), bookmarks = setOf(saved.id)).single()

        assertIs<RecommendationEvidence.ArtTermMatch>(result.evidence.first())
        assertTrue(result.evidence.any { it is RecommendationEvidence.TextSimilarity })
    }

    @Test
    fun `prepared metadata prevents candidate history cross product reads during rerank`() {
        val artist = ExhibitionArtist("artist-shared", "공통", "Shared")
        val term = ArtTerm("theme:shared", ArtTermCategory.THEME, "공통", "Shared")
        val artistLists = mutableListOf<CountingList<ExhibitionArtist>>()
        val termLists = mutableListOf<CountingList<ArtTerm>>()
        val catalogue =
            (0 until 24).map { index ->
                val artists =
                    CountingList(
                        listOf(
                            artist,
                            ExhibitionArtist("artist-$index", "작가 $index", "Artist $index"),
                        ),
                    ).also(artistLists::add)
                val terms =
                    CountingList(
                        listOf(
                            term,
                            ArtTerm("theme:item-$index", ArtTermCategory.THEME, "주제 $index", "Theme $index"),
                        ),
                    ).also(termLists::add)
                exhibition(
                    id = "item-$index",
                    artists = artists,
                    artTerms = terms,
                )
            }
        val prepared = recommender.prepare(catalogue)
        (artistLists + termLists).forEach(CountingList<*>::resetReads)

        prepared.recommend(
            context(bookmarks = catalogue.take(12).mapTo(mutableSetOf(), Exhibition::id)),
        )

        val rerankReads = (artistLists + termLists).sumOf(CountingList<*>::reads)
        assertTrue(rerankReads <= catalogue.size * 4, "metadata reads during rerank=$rerankReads")
    }

    @Test
    fun `text similarity remains truthful generic evidence`() {
        val saved = exhibition("saved", descriptionEn = "experimental cyanotype photography")
        val candidate = exhibition("candidate", descriptionEn = "cyanotype photographic experiment")

        val result = recommend(listOf(candidate, saved), bookmarks = setOf(saved.id)).single()

        val evidence = assertIs<RecommendationEvidence.TextSimilarity>(result.evidence.single())
        assertEquals(saved.id, evidence.anchor.exhibitionId)
        assertTrue(result.evidence.none { it is RecommendationEvidence.ArtistMatch })
        assertTrue(result.evidence.none { it is RecommendationEvidence.ArtTermMatch })
    }

    @Test
    fun `zero evidence candidates are omitted instead of receiving id tie breaks`() {
        val unexplained = exhibition("a-unexplained")
        val featured = exhibition("z-featured", isFeatured = true)

        val result = recommend(listOf(unexplained, featured))

        assertEquals(listOf(featured.id), result.map { it.exhibition.id })
        assertEquals(listOf(RecommendationEvidence.Featured), result.single().evidence)
        assertTrue(result.all { it.evidence.size in 1..2 })
    }

    @Test
    fun `matching labels without stable term identity do not claim a term match`() {
        val saved =
            exhibition(
                "saved",
                artTerms = listOf(ArtTerm("style:a", ArtTermCategory.STYLE, "추상", "Abstract")),
            )
        val candidate =
            exhibition(
                "candidate",
                artTerms = listOf(ArtTerm("style:b", ArtTermCategory.STYLE, "추상", "Abstract")),
            )

        val result = recommend(listOf(candidate, saved), bookmarks = setOf(saved.id))

        assertTrue(result.flatMap { it.evidence }.none { it is RecommendationEvidence.ArtTermMatch })
    }

    @Test
    fun `metadata change invalidates the prepared index`() {
        val catalogue = listOf(exhibition("one"), exhibition("two"))
        val prepared = recommender.prepare(catalogue)
        val changed =
            catalogue.map { exhibition ->
                if (exhibition.id == "one") {
                    exhibition.copy(
                        artists = listOf(ExhibitionArtist("artist-new", "새 작가", "New Artist")),
                    )
                } else {
                    exhibition
                }
            }

        assertNotSame(prepared, recommender.prepare(changed, prepared))
    }

    @Test
    fun `catalogue input order preserves ranked evidence anchors and scores`() {
        val artist = ExhibitionArtist("artist", "작가", "Artist")
        val saved = exhibition("saved", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist))
        val forward = recommend(listOf(saved, candidate), bookmarks = setOf(saved.id))
        val reverse = recommend(listOf(candidate, saved), bookmarks = setOf(saved.id))

        assertEquals(forward, reverse)
    }

    private fun recommend(
        catalogue: List<Exhibition>,
        bookmarks: Set<String> = emptySet(),
    ): List<ExhibitionRecommendation> =
        recommender.prepare(catalogue).recommend(
            context(bookmarks),
        )

    private fun context(bookmarks: Set<String> = emptySet()) =
        RecommendationContext(
            bookmarkedExhibitionIds = bookmarks,
            today = today,
        )

    private fun exhibition(
        id: String,
        descriptionEn: String = "",
        artists: List<ExhibitionArtist> = emptyList(),
        artTerms: List<ArtTerm> = emptyList(),
        galleryId: String = "gallery-$id",
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
        galleryId = galleryId,
        artists = artists,
        artTerms = artTerms,
    )

    private class CountingList<T>(
        private val values: List<T>,
    ) : AbstractList<T>() {
        var reads: Int = 0
            private set

        override val size: Int get() = values.size

        override fun get(index: Int): T {
            reads += 1
            return values[index]
        }

        fun resetReads() {
            reads = 0
        }
    }
}
