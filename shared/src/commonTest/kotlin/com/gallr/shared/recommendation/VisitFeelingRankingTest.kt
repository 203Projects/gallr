package com.gallr.shared.recommendation

import com.gallr.shared.analytics.AnalyticsPlatform
import com.gallr.shared.analytics.AnalyticsSurface
import com.gallr.shared.analytics.MobileAnalyticsEvent
import com.gallr.shared.analytics.MobileAnalyticsEventFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TasteDismissals
import com.gallr.shared.taste.TasteSeed
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 103 US6 (T111): the diary feeling tunes 내 취향 on the device. One guarantee per test group, from
 * contracts/shared-diary-api.md "Recommender": no feeling ranks exactly as 1.15 did; 좋았어요 weighs a visit like a save;
 * 그저 그랬어요 keeps today's visit weight; 아쉬웠어요 adds no evidence and costs half a 덜 보기 step inside the same cap;
 * mood tags on a 좋았어요 visit count like picked terms; ranking is deterministic and nothing of it reaches analytics.
 */
class VisitFeelingRankingTest {
    private val today = LocalDate(2026, 8, 30)
    private val recommender = LocalExhibitionRecommender()
    private val city = term("theme:city", ArtTermCategory.THEME)
    private val nature = term("theme:nature", ArtTermCategory.THEME)
    private val body = term("theme:body", ArtTermCategory.THEME)
    private val archive = term("theme:archive", ArtTermCategory.THEME)
    private val playful = term("mood:playful", ArtTermCategory.MOOD)
    private val monumental = term("mood:monumental", ArtTermCategory.MOOD)
    private val intimate = term("mood:intimate", ArtTermCategory.MOOD)
    private val artist = ExhibitionArtist("artist-park", "박서보", "Park Seo-Bo")

    // Guarantee 1 and 3: a visit with no feeling, or 그저 그랬어요, ranks exactly as a visit ranks today.

    @Test
    fun `a visit with no feeling keeps today's visited weights`() {
        val visited = exhibition("visited", artTerms = listOf(city), artists = listOf(artist))
        val termOnly = exhibition("term-only", artTerms = listOf(city))
        val artistOnly = exhibition("artist-only", artists = listOf(artist))
        val catalogue = listOf(visited, termOnly, artistOnly)

        val result = recommend(catalogue, visits = listOf(visit(visited)))

        // A visited reviewed term is worth 0.25 and a visited artist 0.35 at full overlap (the 1.15 weights).
        assertEquals(bp(0.25), result.scoreOf("term-only"))
        assertEquals(bp(0.35), result.scoreOf("artist-only"))
    }

    @Test
    fun `a fine visit ranks exactly as a visit with no feeling`() {
        val catalogue = paintingCatalogue()
        val visited = catalogue.first()

        assertEquals(
            recommend(catalogue, visits = listOf(visit(visited))),
            recommend(catalogue, visits = listOf(visit(visited, VisitFeeling.FINE))),
        )
    }

    @Test
    fun `tags on a visit with no feeling or a fine visit change nothing`() {
        val visited = exhibition("visited", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city, playful))
        val catalogue = listOf(visited, candidate)
        val plain = recommend(catalogue, visits = listOf(visit(visited)))

        for (feeling in listOf(null, VisitFeeling.FINE)) {
            val tagged = recommend(catalogue, visits = listOf(visit(visited, feeling, VisitTag.PLAYFUL)))
            assertEquals(plain, tagged, "tags on a $feeling visit changed the ranking")
        }
    }

    // Guarantee 2: 좋았어요 weighs a visit like a save of the same show.

    @Test
    fun `a loved visit gives every candidate the basis points a save of the same show gives`() {
        val catalogue = paintingCatalogue()
        val anchor = catalogue.first()

        val loved = recommend(catalogue, visits = listOf(visit(anchor, VisitFeeling.LOVED)))
        val saved = recommend(catalogue, bookmarks = setOf(anchor.id))

        assertTrue(loved.isNotEmpty())
        assertEquals(saved.map(::idAndScore), loved.map(::idAndScore))
    }

    @Test
    fun `a loved visit is still named as a visit`() {
        val visited = exhibition("visited", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist))

        val result = recommend(listOf(visited, candidate), visits = listOf(visit(visited, VisitFeeling.LOVED))).single()

        val reason = result.evidence.first()
        assertIs<RecommendationEvidence.ArtistMatch>(reason)
        assertEquals(RecommendationSignalSource.VISITED, reason.source)
        assertEquals(bp(0.50), result.scoreBasisPoints)
    }

    // Guarantee 4: 아쉬웠어요 adds no evidence and costs half a 덜 보기 step inside the same cap.

    @Test
    fun `a disliked visit no longer lifts similar shows`() {
        val visited = exhibition("visited", artTerms = listOf(city), artists = listOf(artist))
        val candidate = exhibition("candidate", artTerms = listOf(city), artists = listOf(artist))

        val result = recommend(listOf(visited, candidate), visits = listOf(visit(visited, VisitFeeling.NOT_FOR_ME)))

        assertTrue(result.isEmpty(), "a disliked visit alone gave evidence: $result")
    }

    @Test
    fun `a disliked visit costs a shared term an eighth and less than one see less`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val disliked = exhibition("disliked", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val catalogue = listOf(saved, disliked, candidate)

        val before = recommend(catalogue, bookmarks = setOf(saved.id)).scoreOf("candidate")
        val afterVisit =
            recommend(catalogue, bookmarks = setOf(saved.id), visits = listOf(visit(disliked, VisitFeeling.NOT_FOR_ME)))
                .scoreOf("candidate")
        val afterDismissal =
            recommend(
                catalogue,
                bookmarks = setOf(saved.id),
                dismissals = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf(city.id))),
            ).scoreOf("candidate")

        assertEquals(bp(0.35), before)
        assertEquals(bp(0.35 - 0.125), afterVisit)
        assertEquals(bp(0.35 - 0.175), afterDismissal)
        assertTrue(afterVisit > afterDismissal)
    }

    @Test
    fun `a disliked visit costs a credited artist an eighth and less than one see less`() {
        val saved = exhibition("saved", artists = listOf(artist))
        val disliked = exhibition("disliked", artists = listOf(artist))
        val candidate = exhibition("candidate", artists = listOf(artist))
        val catalogue = listOf(saved, disliked, candidate)

        val afterVisit =
            recommend(catalogue, bookmarks = setOf(saved.id), visits = listOf(visit(disliked, VisitFeeling.NOT_FOR_ME)))
                .scoreOf("candidate")
        val afterDismissal =
            recommend(
                catalogue,
                bookmarks = setOf(saved.id),
                dismissals = TasteDismissals.EMPTY.dismiss(dismissed("other", artists = setOf(artist.id))),
            ).scoreOf("candidate")

        assertEquals(bp(0.50 - 0.125), afterVisit)
        assertEquals(bp(0.50 - 0.25), afterDismissal)
        assertTrue(afterVisit > afterDismissal)
    }

    @Test
    fun `a disliked visit and a see less share one cap`() {
        val saved = exhibition("saved", artTerms = listOf(city))
        val dislikedOne = exhibition("disliked-one", artTerms = listOf(city))
        val dislikedTwo = exhibition("disliked-two", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val catalogue = listOf(saved, dislikedOne, dislikedTwo, candidate)
        val seeLess = TasteDismissals.EMPTY.dismiss(dismissed("other", terms = setOf(city.id)))

        val both =
            recommend(
                catalogue,
                bookmarks = setOf(saved.id),
                visits = listOf(visit(dislikedOne, VisitFeeling.NOT_FOR_ME)),
                dismissals = seeLess,
            ).scoreOf("candidate")
        val twoVisits =
            recommend(
                catalogue,
                bookmarks = setOf(saved.id),
                visits =
                    listOf(visit(dislikedOne, VisitFeeling.NOT_FOR_ME), visit(dislikedTwo, VisitFeeling.NOT_FOR_ME)),
            ).scoreOf("candidate")

        // min(0.25 × (1 + 0.5), 0.175) and min(0.25 × (0 + 0.5 × 2), 0.175): the cap a see less alone may cost.
        assertEquals(bp(0.35 - 0.175), both)
        assertEquals(bp(0.35 - 0.175), twoVisits)
    }

    @Test
    fun `a disliked visit floors a score at zero and keeps the card in the personal group`() {
        val saved = exhibition("saved", artTerms = listOf(city, nature, body, archive))
        val disliked = exhibition("disliked", artTerms = listOf(city))
        val candidate = exhibition("candidate", artTerms = listOf(city))
        val editorial = exhibition("editorial", isFeatured = true)
        val catalogue = listOf(saved, disliked, candidate, editorial)

        val result =
            recommend(catalogue, bookmarks = setOf(saved.id), visits = listOf(visit(disliked, VisitFeeling.NOT_FOR_ME)))

        // Saved overlap ¼ × 0.35 = 0.0875 less 0.125 floors at zero; the card still leads the editorial one.
        assertEquals(0, result.scoreOf("candidate"))
        assertEquals(listOf("candidate", "editorial"), result.map { it.exhibition.id })
        assertTrue(result.first().hasPersonalEvidence)
    }

    @Test
    fun `a disliked visit ignores terms read only from text`() {
        val saved = exhibition("saved", descriptionEn = "a minimalist show")
        val disliked = exhibition("disliked", descriptionEn = "a minimalist show")
        val candidate = exhibition("candidate", descriptionEn = "a minimalist show")
        val fillers = (1..8).map { exhibition("filler-$it", descriptionEn = "loud colour $it") }
        val catalogue = listOf(saved, disliked, candidate) + fillers

        val before = recommend(catalogue, bookmarks = setOf(saved.id), visits = listOf(visit(disliked)))
        val after =
            recommend(catalogue, bookmarks = setOf(saved.id), visits = listOf(visit(disliked, VisitFeeling.NOT_FOR_ME)))

        // The neutral visit adds its own text match; without it the saved anchor alone remains, never a penalty.
        val savedOnly = recommend(catalogue, bookmarks = setOf(saved.id)).scoreOf("candidate")
        assertTrue(before.scoreOf("candidate") > savedOnly)
        assertEquals(savedOnly, after.scoreOf("candidate"))
    }

    // Guarantee 5: mood tags on a 좋았어요 visit count like picked terms; the reason names the visit and the term.

    @Test
    fun `a mood tag on a loved visit counts like a picked term`() {
        val visited = exhibition("visited")
        val candidate = exhibition("candidate", artTerms = listOf(playful))
        val catalogue = listOf(visited, candidate)

        val tagged =
            recommend(catalogue, visits = listOf(visit(visited, VisitFeeling.LOVED, VisitTag.PLAYFUL))).single()
        val picked = recommend(catalogue, seed = TasteSeed(termIds = setOf(playful.id))).single()

        assertEquals(bp(0.30), tagged.scoreBasisPoints)
        assertEquals(picked.scoreBasisPoints, tagged.scoreBasisPoints)
        val reason = tagged.evidence.first()
        assertIs<RecommendationEvidence.VisitedMood>(reason)
        assertEquals("visited", reason.anchor.exhibitionId)
        assertEquals(playful, reason.term)
        assertTrue(reason.isPersonal)
    }

    @Test
    fun `each further mood a show carries adds half the step before`() {
        val visited = exhibition("visited")
        val candidate = exhibition("candidate", artTerms = listOf(playful, monumental))

        val result =
            recommend(
                listOf(visited, candidate),
                visits = listOf(visit(visited, VisitFeeling.LOVED, VisitTag.PLAYFUL, VisitTag.OVERWHELMING)),
            )

        assertEquals(bp(0.30 + 0.15), result.scoreOf("candidate"))
    }

    @Test
    fun `a mood both picked and tagged counts once and is named as the pick`() {
        val visited = exhibition("visited")
        val candidate = exhibition("candidate", artTerms = listOf(playful))

        val result =
            recommend(
                listOf(visited, candidate),
                visits = listOf(visit(visited, VisitFeeling.LOVED, VisitTag.PLAYFUL)),
                seed = TasteSeed(termIds = setOf(playful.id)),
            ).single()

        assertEquals(bp(0.30), result.scoreBasisPoints)
        assertIs<RecommendationEvidence.ChosenTerm>(result.evidence.first())
    }

    @Test
    fun `mood tags on a fine or disliked visit and the unmapped tags add nothing`() {
        val visited = exhibition("visited")
        val playfulShow = exhibition("playful-show", artTerms = listOf(playful))
        val intimateShow = exhibition("intimate-show", artTerms = listOf(intimate))
        val catalogue = listOf(visited, playfulShow, intimateShow)

        for (feeling in listOf(VisitFeeling.FINE, VisitFeeling.NOT_FOR_ME, null)) {
            val result = recommend(catalogue, visits = listOf(visit(visited, feeling, VisitTag.PLAYFUL)))
            assertTrue(result.isEmpty(), "a $feeling visit's tag gave evidence: $result")
        }
        val unmapped = visit(visited, VisitFeeling.LOVED, VisitTag.MOVING, VisitTag.THE_WORK)
        val moving = recommend(catalogue, visits = listOf(unmapped))
        assertTrue(moving.isEmpty(), "an unmapped tag gave evidence: $moving")
    }

    @Test
    fun `a mood tag counts even when the loved show has left the catalogue`() {
        val gone = exhibition("gone")
        val candidate = exhibition("candidate", artTerms = listOf(playful))

        val result =
            recommend(listOf(candidate), visits = listOf(visit(gone, VisitFeeling.LOVED, VisitTag.PLAYFUL))).single()

        val reason = result.evidence.first()
        assertIs<RecommendationEvidence.VisitedMood>(reason)
        assertEquals("gone", reason.anchor.exhibitionId)
        assertEquals(gone.nameKo, reason.anchor.nameKo)
    }

    // Guarantee 6: determinism and input-order independence; nothing about the feeling reaches analytics.

    @Test
    fun `ranking is deterministic and independent of visit order`() {
        val catalogue = paintingCatalogue() + exhibition("playful-show", artTerms = listOf(playful))
        val visits =
            listOf(
                visit(catalogue[0], VisitFeeling.LOVED, VisitTag.PLAYFUL),
                visit(catalogue[1], VisitFeeling.NOT_FOR_ME),
                visit(catalogue[2], VisitFeeling.FINE),
            )

        val first = recommend(catalogue, visits = visits)
        assertEquals(first, recommend(catalogue, visits = visits))
        assertEquals(first, recommend(catalogue, visits = visits.reversed()))
        assertEquals(first, recommend(catalogue.reversed(), visits = visits.reversed()))
    }

    @Test
    fun `the analytics event shape cannot carry a feeling a tag or a note`() {
        val descriptor = MobileAnalyticsEvent.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName)

        for (field in fields) {
            assertFalse(listOf("feeling", "tag", "note", "mood").any { it in field }, "analytics field $field")
        }
    }

    @Test
    fun `the recommendations-shown event built after a felt ranking carries no feeling or tag`() {
        val catalogue = paintingCatalogue() + exhibition("playful-show", artTerms = listOf(playful))
        val ranked =
            recommend(
                catalogue,
                visits =
                    listOf(
                        visit(catalogue[0], VisitFeeling.LOVED, VisitTag.PLAYFUL, VisitTag.HEAVY),
                        visit(catalogue[1], VisitFeeling.NOT_FOR_ME, VisitTag.MOVING),
                    ),
            )
        val factory =
            MobileAnalyticsEventFactory(
                platform = AnalyticsPlatform.IOS,
                appMajor = 1,
                eventIdFactory = { "a6000000-0000-4000-8000-000000000001" },
            )

        val event = factory.recommendationsShown(AnalyticsSurface.FEATURED, ranked.size)
        val payload = Json.encodeToString(MobileAnalyticsEvent.serializer(), event)

        for (word in feelingAndTagWords()) assertFalse(word in payload, "analytics payload carries \"$word\": $payload")
    }

    private fun feelingAndTagWords(): List<String> =
        VisitFeeling.entries.flatMap { listOf(it.wire, it.label(AppLanguage.KO), it.label(AppLanguage.EN)) } +
            VisitTag.entries.flatMap { listOf(it.wire, it.label(AppLanguage.KO), it.label(AppLanguage.EN)) }

    /** Three paintings, two by one artist, with overlapping reviewed terms. */
    private fun paintingCatalogue(): List<Exhibition> =
        listOf(
            exhibition("anchor", artTerms = listOf(city, nature), artists = listOf(artist)),
            exhibition("same-artist", artTerms = listOf(city), artists = listOf(artist)),
            exhibition("same-terms", artTerms = listOf(city, nature)),
            exhibition("one-term", artTerms = listOf(nature, body)),
        )

    private fun List<ExhibitionRecommendation>.scoreOf(id: String): Int =
        single { it.exhibition.id == id }.scoreBasisPoints

    private fun idAndScore(recommendation: ExhibitionRecommendation) =
        recommendation.exhibition.id to recommendation.scoreBasisPoints

    private fun recommend(
        catalogue: List<Exhibition>,
        bookmarks: Set<String> = emptySet(),
        visits: List<ExhibitionVisit> = emptyList(),
        dismissals: TasteDismissals = TasteDismissals.EMPTY,
        seed: TasteSeed = TasteSeed.EMPTY,
    ): List<ExhibitionRecommendation> =
        recommender.prepare(catalogue, previous = null).recommend(
            RecommendationContext(
                bookmarkedExhibitionIds = bookmarks,
                visits = visits,
                today = today,
                tasteSeed = seed,
                dismissals = dismissals,
            ),
        )

    private fun visit(
        exhibition: Exhibition,
        feeling: VisitFeeling? = null,
        vararg tags: VisitTag,
    ) = ExhibitionVisit(
        clientRecordId = "visit-${exhibition.id}",
        exhibitionId = exhibition.id,
        snapshot = ExhibitionVisitSnapshot.from(exhibition),
        createdAt = Instant.parse("2026-08-20T10:00:00Z"),
        feelingWire = feeling?.wire,
        tagWires = tags.map(VisitTag::wire),
    )

    private fun dismissed(
        id: String,
        terms: Set<String> = emptySet(),
        artists: Set<String> = emptySet(),
    ) = TasteDismissal(id, LocalDate(2026, 9, 30), terms, artists)

    /** One unrelated syllable per show id, so no two names share a character run the text matcher could read. */
    private val names = mutableMapOf<String, String>()

    /** The ranker's fixed normalisation (sum of its eleven base weights). */
    private fun bp(score: Double): Int = (score / 2.33 * 10_000).roundToInt()

    private fun term(
        id: String,
        category: ArtTermCategory,
    ) = ArtTerm(id, category, id.substringAfter(':'), id.substringAfter(':'))

    private fun exhibition(
        id: String,
        artists: List<ExhibitionArtist> = emptyList(),
        artTerms: List<ArtTerm> = emptyList(),
        isFeatured: Boolean = false,
        descriptionEn: String = "",
    ) = Exhibition(
        id = id,
        nameKo = names.getOrPut(id) { NAME_SYLLABLES[names.size].toString() },
        nameEn = "",
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
        const val NAME_SYLLABLES = "갑을병정무기경신임계자축인묘진사오미유술해"
    }
}
