package com.gallr.shared.recommendation

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTime

class LocalExhibitionRecommenderTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()

    private companion object {
        const val VENUE_BOILERPLATE =
            "The gallery is located on the second floor and is open to the public free of charge. " +
                "Guided tours are offered every Saturday afternoon and group visits can be arranged by email."
        const val CATALOGUE_BOILERPLATE =
            "Admission is free. Opening reception with the artist on the first evening. " +
                "Photography permitted without flash. The exhibition is accompanied by a catalogue."
    }

    @Test
    fun `bilingual thematic content outranks a generic same city candidate`() {
        val saved = exhibition("saved", nameKo = "빛의 사진", descriptionEn = "light photography installation")
        val thematic = exhibition("thematic", descriptionKo = "빛과 사진 설치 작업", descriptionEn = "photographic light")
        val generic = exhibition("generic", descriptionKo = "도자 공예", descriptionEn = "ceramic craft")

        val result = recommend(listOf(saved, generic, thematic), context(bookmarks = setOf("saved")))

        assertEquals("thematic", result.first().exhibition.id)
        assertTrue(
            result.first().evidence.any {
                it is RecommendationEvidence.TextSimilarity && it.source == RecommendationSignalSource.SAVED
            },
        )
    }

    @Test
    fun `visited and bookmarked exhibitions are excluded from discovery results`() {
        // The candidate shares text with the save only: wording present in every catalogue entry is
        // treated as catalogue noise and would leave the candidate without evidence (spec 088).
        val saved = exhibition("saved", descriptionEn = "oil painting")
        val visited = exhibition("visited", descriptionEn = "bronze sculpture")
        val candidate = exhibition("candidate", descriptionEn = "oil painting")

        val result =
            recommend(
                listOf(saved, visited, candidate),
                context(
                    bookmarks = setOf(saved.id),
                    visits = listOf(visit(visited)),
                ),
            )

        assertEquals(listOf("candidate"), result.map { it.exhibition.id })
        assertFalse(result.any { it.exhibition.id in setOf(saved.id, visited.id) })
    }

    @Test
    fun `ended visit still seeds taste for an active thematic candidate`() {
        val endedVisit =
            exhibition(
                "ended-visit",
                descriptionEn = "experimental cyanotype photography",
                openingDate = LocalDate(2026, 6, 1),
                closingDate = LocalDate(2026, 7, 1),
            )
        val thematic = exhibition("thematic", descriptionEn = "cyanotype photographic experiment")
        val unrelated = exhibition("unrelated", descriptionEn = "traditional ceramic vessels")

        val result =
            recommend(
                listOf(unrelated, thematic, endedVisit),
                context(visits = listOf(visit(endedVisit))),
            )

        assertEquals("thematic", result.first().exhibition.id)
        assertTrue(
            result.first().evidence.any {
                it is RecommendationEvidence.TextSimilarity && it.source == RecommendationSignalSource.VISITED
            },
        )
    }

    @Test
    fun `followed gallery is an explicit independently explained boost`() {
        val followed = exhibition("followed", galleryId = "gallery-one", descriptionEn = "abstract")
        val other = exhibition("other", galleryId = "gallery-two", descriptionEn = "abstract")

        val result =
            recommend(
                listOf(other, followed),
                context(follows = listOf(follow("gallery-one"))),
            )

        assertEquals("followed", result.first().exhibition.id)
        assertTrue(RecommendationEvidence.FollowedGallery in result.first().evidence)
    }

    @Test
    fun `cold start uses nearby editorial and timing signals without claiming similarity`() {
        val nearbyFeatured = exhibition("nearby", latitude = 37.5666, longitude = 126.9781, isFeatured = true)
        val far = exhibition("far", latitude = 37.7, longitude = 127.2)

        val result =
            recommend(
                listOf(far, nearbyFeatured),
                context(origin = GeoPoint(37.5665, 126.9780)),
            )

        assertEquals("nearby", result.first().exhibition.id)
        assertTrue(RecommendationEvidence.Nearby in result.first().evidence)
        assertTrue(RecommendationEvidence.Featured in result.first().evidence)
        assertFalse(result.first().evidence.any { it is RecommendationEvidence.TextSimilarity })
    }

    @Test
    fun `ended and too far upcoming exhibitions never appear`() {
        val ended = exhibition("ended", openingDate = LocalDate(2026, 7, 1), closingDate = LocalDate(2026, 8, 29))
        val tooFarUpcoming =
            exhibition(
                "future",
                openingDate = LocalDate(2026, 9, 20),
                closingDate = LocalDate(2026, 10, 20),
            )
        val visible = exhibition("visible", isFeatured = true)

        assertEquals(
            listOf("visible"),
            recommend(listOf(ended, tooFarUpcoming, visible), context()).map { it.exhibition.id },
        )
    }

    @Test
    fun `diversity limits one gallery to two results when alternatives exist`() {
        val catalogue =
            listOf(
                exhibition("a1", galleryId = "same", descriptionEn = "light photo", isFeatured = true),
                exhibition("a2", galleryId = "same", descriptionEn = "light photo", isFeatured = true),
                exhibition("a3", galleryId = "same", descriptionEn = "light photo", isFeatured = true),
                exhibition("b1", galleryId = "other", descriptionEn = "light photo", isFeatured = true),
            )

        val result = recommend(catalogue, context(limit = 4))

        assertTrue(result.count { it.exhibition.galleryId == "same" } <= 2)
        assertTrue(result.any { it.exhibition.id == "b1" })
    }

    @Test
    fun `near duplicate content from different galleries cannot dominate results`() {
        val duplicates =
            (1..6).map { index ->
                exhibition(
                    id = "duplicate-$index",
                    galleryId = "gallery-$index",
                    descriptionEn = "immersive blue light photography installation",
                    isFeatured = true,
                )
            }
        val alternative =
            exhibition(
                id = "alternative",
                galleryId = "different-gallery",
                descriptionEn = "hand built ceramic vessels and clay sculpture",
                isFeatured = true,
            )

        val result = recommend(duplicates + alternative, context(limit = 4))

        assertTrue(result.any { it.exhibition.id == alternative.id })
        assertTrue(result.count { it.exhibition.id.startsWith("duplicate-") } <= 2)
    }

    @Test
    fun `canonical Korean and Latin forms produce equivalent relevance`() {
        // Each form is scored in its own catalogue against the same save and the same unrelated entry:
        // text shared by every catalogue entry is treated as noise, so the two forms cannot share one
        // three-entry catalogue (spec 088). Equal scores across the runs prove canonical equivalence.
        val saved = exhibition("saved", descriptionKo = "가 카페 cafe 가 카페 cafe 가 카페 cafe")
        val unrelated = exhibition("unrelated", descriptionEn = "bronze figurative sculpture")
        val composed =
            exhibition(
                "form",
                nameKo = "형태",
                nameEn = "Form",
                descriptionKo = "가 카페 café 가 카페 café 가 카페 café",
                galleryId = "gallery-form",
            ).copy(venueNameKo = "동일", venueNameEn = "Same")
        val decomposed = composed.copy(descriptionKo = "가 카페 cafe\u0301 가 카페 cafe\u0301 가 카페 cafe\u0301")

        val composedResult = recommend(listOf(saved, unrelated, composed), context(bookmarks = setOf("saved")))
        val decomposedResult = recommend(listOf(decomposed, unrelated, saved), context(bookmarks = setOf("saved")))

        assertEquals(listOf("form"), composedResult.map { it.exhibition.id })
        assertEquals(listOf("form"), decomposedResult.map { it.exhibition.id })
        assertEquals(composedResult.single().scoreBasisPoints, decomposedResult.single().scoreBasisPoints)
        assertTrue(composedResult.single().evidence.any { it is RecommendationEvidence.TextSimilarity })

        val caronSaved = exhibition("caron-saved", descriptionEn = "české umění české umění české umění")
        val caronComposed =
            exhibition(
                "czech",
                nameKo = "체코 예술",
                nameEn = "Czech art",
                descriptionEn = "české umění české umění české umění",
                galleryId = "c1",
            ).copy(venueNameKo = "동일", venueNameEn = "Same")
        val caronDecomposed =
            caronComposed.copy(descriptionEn = "c\u030Ceské umění c\u030Ceské umění c\u030Ceské umění")

        val caronComposedResult =
            recommend(listOf(caronSaved, unrelated, caronComposed), context(bookmarks = setOf(caronSaved.id)))
        val caronDecomposedResult =
            recommend(listOf(caronDecomposed, unrelated, caronSaved), context(bookmarks = setOf(caronSaved.id)))
        assertEquals(caronComposedResult.single().scoreBasisPoints, caronDecomposedResult.single().scoreBasisPoints)
    }

    @Test
    fun `explanations keep the strongest measured contributions`() {
        val saved = exhibition("saved", descriptionEn = "loosely related light")
        val visited = exhibition("visited", descriptionEn = "loosely related light")
        val followed =
            exhibition(
                "followed",
                descriptionEn = "loosely related light",
                galleryId = "followed-gallery",
                isFeatured = true,
            )

        val result =
            recommend(
                listOf(saved, visited, followed),
                context(
                    bookmarks = setOf(saved.id),
                    visits = listOf(visit(visited)),
                    follows = listOf(follow("followed-gallery")),
                ),
            ).single()

        assertTrue(RecommendationEvidence.FollowedGallery in result.evidence)
    }

    @Test
    fun `equal quantized scores use exhibition id as the tie breaker`() {
        val laterIdFirst = exhibition("a", closingDate = LocalDate(2026, 9, 20), isFeatured = true)
        val earlierClosing = exhibition("z", closingDate = LocalDate(2026, 9, 15), isFeatured = true)

        val result = recommend(listOf(earlierClosing, laterIdFirst), context())

        assertEquals(listOf("a", "z"), result.map { it.exhibition.id })
    }

    @Test
    fun `input order does not affect ranked ids reasons or quantized scores`() {
        val catalogue =
            listOf(
                exhibition("c", descriptionEn = "video installation"),
                exhibition("a", descriptionEn = "video art"),
                exhibition("b", descriptionEn = "sculpture"),
            )
        val recommendationContext = context(bookmarks = setOf("c"))

        val forward = recommend(catalogue, recommendationContext)
        val reverse = recommend(catalogue.reversed(), recommendationContext)

        assertEquals(
            forward.map { Triple(it.exhibition.id, it.scoreBasisPoints, it.evidence) },
            reverse.map { Triple(it.exhibition.id, it.scoreBasisPoints, it.evidence) },
        )
    }

    @Test
    fun `representative catalogue preparation and repeated reranking remain bounded`() {
        val catalogue =
            (0 until 1_205).map { index ->
                exhibition(
                    id = "exhibition-$index",
                    descriptionKo = "전시 주제 ${index % 31} 사진 설치 조각",
                    descriptionEn = "theme ${index % 31} photography installation sculpture",
                    galleryId = "gallery-${index % 80}",
                )
            }
        var result: List<ExhibitionRecommendation> = emptyList()
        lateinit var prepared: ExhibitionRecommendationIndex

        val preparationElapsed =
            measureTime {
                prepared = recommender.prepare(catalogue)
            }
        val rerankElapsed =
            measureTime {
                repeat(20) { index ->
                    result =
                        prepared.recommend(
                            context(bookmarks = setOf("exhibition-${index % 10}")),
                        )
                }
            }

        assertTrue(result.isNotEmpty())
        assertTrue(preparationElapsed < 10.seconds, "catalogue preparation took $preparationElapsed")
        assertTrue(rerankElapsed < 10.seconds, "20 prepared reranks took $rerankElapsed")
    }

    @Test
    fun `equal reconstructed and reordered catalogue reuses prepared index`() {
        val catalogue =
            listOf(
                exhibition("a", descriptionEn = "video installation"),
                exhibition("b", descriptionEn = "ceramic sculpture"),
            )

        val prepared = recommender.prepare(catalogue)
        val reconstructed = catalogue.map { it.copy() }.reversed()
        val reused = recommender.prepare(reconstructed, previous = prepared)

        assertSame(prepared, reused)
    }

    @Test
    fun `catalogue changes and duplicate ids invalidate or reject preparation`() {
        val catalogue =
            listOf(
                exhibition("a", descriptionEn = "video installation"),
                exhibition("b", descriptionEn = "ceramic sculpture"),
            )
        val prepared = recommender.prepare(catalogue)

        assertNotSame(
            prepared,
            recommender.prepare(
                catalogue.map { if (it.id == "a") it.copy(descriptionEn = "changed") else it },
                previous = prepared,
            ),
        )
        assertNotSame(
            prepared,
            recommender.prepare(
                catalogue.map { if (it.id == "a") it.copy(coverImageUrl = "https://example.com/new.jpg") else it },
                previous = prepared,
            ),
        )
        assertNotSame(
            prepared,
            recommender.prepare(
                catalogue + exhibition("c"),
                previous = prepared,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            recommender.prepare(listOf(exhibition("duplicate"), exhibition("duplicate")))
        }
    }

    @Test
    fun `signal and date changes rerank one prepared catalogue`() {
        val saved = exhibition("saved", descriptionEn = "light photography")
        val thematic = exhibition("thematic", descriptionEn = "photographic light")
        val expiring =
            exhibition(
                "expiring",
                descriptionEn = "ceramic",
                closingDate = LocalDate(2026, 8, 30),
            )
        val prepared = recommender.prepare(listOf(expiring, thematic, saved))

        val coldStart = prepared.recommend(context())
        val personalized = prepared.recommend(context(bookmarks = setOf(saved.id)))
        val nextDay = prepared.recommend(context(today = LocalDate(2026, 8, 31)))

        assertTrue(coldStart.any { it.exhibition.id == expiring.id })
        assertEquals(thematic.id, personalized.first().exhibition.id)
        assertFalse(nextDay.any { it.exhibition.id == expiring.id })
    }

    @Test
    fun `prepared catalogue supports concurrent deterministic reranks`() =
        runTest {
            val catalogue =
                (0 until 80).map { index ->
                    exhibition(
                        id = "exhibition-$index",
                        descriptionEn = "theme ${index % 7} photography installation",
                        galleryId = "gallery-${index % 10}",
                    )
                }
            val prepared = recommender.prepare(catalogue)
            val recommendationContext = context(bookmarks = setOf("exhibition-0"))
            val expected = prepared.recommend(recommendationContext)

            val concurrent =
                (0 until 8)
                    .map {
                        async(Dispatchers.Default) { prepared.recommend(recommendationContext) }
                    }.awaitAll()

            concurrent.forEach { assertEquals(expected, it) }
        }

    @Test
    fun `recommendation limit is bounded to twenty`() {
        assertFailsWith<IllegalArgumentException> { context(limit = -1) }
        assertFailsWith<IllegalArgumentException> { context(limit = 21) }
    }

    // Text-similarity calibration (spec 088, US3).

    @Test
    fun `wording shared across a venue's exhibitions does not create text similarity`() {
        val saved =
            exhibition(
                "saved",
                galleryId = "same-venue",
                descriptionEn = "lacquer vessels by Haneul Kwon $VENUE_BOILERPLATE",
            )
        val venueMate =
            exhibition(
                "mate",
                galleryId = "same-venue",
                descriptionEn = "steel kinetic machines $VENUE_BOILERPLATE",
            )
        val elsewhere = exhibition("elsewhere", galleryId = "other-venue", descriptionEn = "watercolour landscapes")

        val result = recommend(listOf(venueMate, elsewhere, saved), context(bookmarks = setOf(saved.id)))

        assertFalse(result.flatMap { it.evidence }.any { it is RecommendationEvidence.TextSimilarity })
    }

    @Test
    fun `shared artistic content across venues still creates text similarity`() {
        val saved = exhibition("saved", galleryId = "gallery-one", descriptionEn = "experimental cyanotype photography")
        val related =
            exhibition("related", galleryId = "gallery-two", descriptionEn = "cyanotype photographic experiment")
        val unrelated =
            exhibition("unrelated", galleryId = "gallery-three", descriptionEn = "bronze figurative sculpture")

        val result = recommend(listOf(unrelated, related, saved), context(bookmarks = setOf(saved.id)))

        assertEquals("related", result.first().exhibition.id)
        assertTrue(result.first().evidence.any { it is RecommendationEvidence.TextSimilarity })
        val unrelatedResult = result.firstOrNull { it.exhibition.id == "unrelated" }
        assertTrue(unrelatedResult?.evidence.orEmpty().none { it is RecommendationEvidence.TextSimilarity })
    }

    @Test
    fun `wording present across most of the catalogue does not create text similarity`() {
        val uniqueWords = listOf("가나", "다라", "마바", "사아", "자차", "카타", "파하", "거너", "더러", "머버")
        val catalogue =
            uniqueWords.mapIndexed { index, word ->
                exhibition(
                    "ex-$index",
                    galleryId = "gallery-$index",
                    descriptionEn = "$CATALOGUE_BOILERPLATE $word",
                )
            }

        val result = recommend(catalogue, context(bookmarks = setOf("ex-0")))

        assertTrue(result.isEmpty(), result.map { it.exhibition.id to it.evidence }.toString())
    }

    @Test
    fun `a tiny catalogue keeps similarity between two near identical descriptions`() {
        val saved =
            exhibition("saved", galleryId = "a", descriptionEn = "monumental charcoal drawings of harbour cranes")
        val twin =
            exhibition(
                "twin",
                galleryId = "b",
                descriptionEn = "monumental charcoal drawings of harbour cranes at dusk",
            )
        val other = exhibition("other", galleryId = "c", descriptionEn = "ceramic tea bowls")

        val result = recommend(listOf(other, twin, saved), context(bookmarks = setOf(saved.id)))

        assertEquals("twin", result.first().exhibition.id)
        assertTrue(result.first().evidence.any { it is RecommendationEvidence.TextSimilarity })
    }

    // Evidence aggregation across anchors (spec 088, US3).

    @Test
    fun `repeated artist affinity across saves outranks a single unrelated save`() {
        val repeated = ExhibitionArtist("artist-repeated", "반복", "Repeated")
        val single = ExhibitionArtist("artist-single", "단일", "Single")
        val savedRepeated =
            (1..3).map { index ->
                exhibition(
                    "saved-repeated-$index",
                    galleryId = "saved-gallery-$index",
                    artists = listOf(repeated, ExhibitionArtist("artist-filler-$index", "작가 $index", "Filler $index")),
                )
            }
        val savedSingle =
            exhibition(
                "saved-single",
                galleryId = "saved-gallery-single",
                artists = listOf(single, ExhibitionArtist("artist-filler-single", "작가", "Filler")),
            )
        val repeatedCandidate = exhibition("candidate-repeated", galleryId = "candidate-a", artists = listOf(repeated))
        val singleCandidate = exhibition("candidate-single", galleryId = "candidate-b", artists = listOf(single))
        val catalogue = savedRepeated + savedSingle + singleCandidate + repeatedCandidate
        val bookmarks = (savedRepeated + savedSingle).mapTo(mutableSetOf()) { it.id }

        val result = recommend(catalogue, context(bookmarks = bookmarks))

        assertEquals(listOf("candidate-repeated", "candidate-single"), result.map { it.exhibition.id })
        assertTrue(result[0].scoreBasisPoints > result[1].scoreBasisPoints)
    }

    @Test
    fun `aggregated taste strength is independent of anchor order`() {
        val shared = ExhibitionArtist("artist-shared", "공통", "Shared")
        val saves =
            (1..4).map { index ->
                exhibition(
                    "saved-$index",
                    galleryId = "g-$index",
                    artists = listOf(shared, ExhibitionArtist("artist-$index", "작가 $index", "Artist $index")),
                )
            }
        val candidate = exhibition("candidate", galleryId = "candidate", artists = listOf(shared))
        val bookmarks = saves.mapTo(mutableSetOf()) { it.id }

        val forward = recommend(saves + candidate, context(bookmarks = bookmarks))
        val reversed = recommend((saves + candidate).reversed(), context(bookmarks = bookmarks))

        assertEquals(forward, reversed)
    }

    @Test
    fun `visible evidence names the strongest single anchor`() {
        val shared = ExhibitionArtist("artist-shared", "공통", "Shared")
        val weakAnchor =
            exhibition(
                "weak",
                galleryId = "g-weak",
                artists =
                    listOf(
                        shared,
                        ExhibitionArtist("artist-a", "에이", "A"),
                        ExhibitionArtist("artist-b", "비", "B"),
                        ExhibitionArtist("artist-c", "씨", "C"),
                    ),
            )
        val strongAnchor =
            exhibition(
                "strong",
                galleryId = "g-strong",
                artists = listOf(shared, ExhibitionArtist("artist-d", "디", "D")),
            )
        val candidate = exhibition("candidate", galleryId = "g-candidate", artists = listOf(shared))

        val result =
            recommend(
                listOf(candidate, weakAnchor, strongAnchor),
                context(bookmarks = setOf(weakAnchor.id, strongAnchor.id)),
            ).single()

        val evidence = assertIs<RecommendationEvidence.ArtistMatch>(result.evidence.first())
        assertEquals("strong", evidence.anchor.exhibitionId)
    }

    private fun recommend(
        catalogue: List<Exhibition>,
        context: RecommendationContext,
    ): List<ExhibitionRecommendation> = recommender.prepare(catalogue).recommend(context)

    private fun context(
        bookmarks: Set<String> = emptySet(),
        visits: List<ExhibitionVisit> = emptyList(),
        follows: List<FollowedGallery> = emptyList(),
        origin: GeoPoint? = null,
        limit: Int = 6,
        today: LocalDate = this.today,
    ) = RecommendationContext(
        bookmarkedExhibitionIds = bookmarks,
        visits = visits,
        followedGalleries = follows,
        origin = origin,
        today = today,
        limit = limit,
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
        nameKo: String = id,
        nameEn: String = id,
        descriptionKo: String = "",
        descriptionEn: String = "",
        galleryId: String? = null,
        latitude: Double? = 37.57,
        longitude: Double? = 126.98,
        isFeatured: Boolean = false,
        editorId: String? = null,
        openingDate: LocalDate = LocalDate(2026, 8, 1),
        closingDate: LocalDate = LocalDate(2026, 9, 15),
        artists: List<ExhibitionArtist> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = nameKo,
        nameEn = nameEn,
        // Each exhibition is its own venue unless a gallery is given: wording shared within one venue is
        // treated as venue boilerplate and never counts as text similarity (spec 088).
        venueNameKo = "갤러리 ${galleryId ?: id}",
        venueNameEn = "Gallery ${galleryId ?: id}",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = isFeatured,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = descriptionKo,
        descriptionEn = descriptionEn,
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
        galleryId = galleryId,
        artists = artists,
    )
}
