package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import com.gallr.shared.recommendation.TasteProfileCatalogue.exhibitions
import com.gallr.shared.recommendation.TasteProfileCatalogue.today
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TasteDismissals
import com.gallr.shared.taste.TasteSeed
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Spec 101 US6 (FR-025, T071): made-up visitors over the fixed catalogue in [TasteProfileCatalogue]. Each profile
 * names the shows that must fill the first places, worked out by hand from what they share with the profile
 * (owner decision 2026-11-04: this replaces owner-labelled scenarios). A boundary tie is never part of an
 * expectation: a profile names fewer than three shows when the third place is a tie. Every leading card's first
 * reason must name something in the profile. The exact dismissal arithmetic is in [TasteDismissalPenaltyTest].
 * Spec 103 US6 (T112) adds visitors whose diary says how a visit felt; the feeling arithmetic is in
 * [VisitFeelingRankingTest].
 */
class TasteProfileRankingTest {
    private val recommender = LocalExhibitionRecommender()

    private class Profile(
        val name: String,
        val pickedTerms: Set<String> = emptySet(),
        val pickedShows: Set<String> = emptySet(),
        val saved: Set<String> = emptySet(),
        val dismissed: List<TasteDismissal> = emptyList(),
        /** Diary entries (spec 103 US6): how each visited show felt and what was noticed. */
        val visits: List<ExhibitionVisit> = emptyList(),
        /** The shows that fill the first places, in any order. */
        val leaders: Set<String>,
        /** The show that must come first, when one clearly leads. */
        val first: String? = null,
    )

    private val profiles =
        listOf(
            Profile(
                "picked painting and abstract",
                pickedTerms = setOf("medium:painting", "style:abstract"),
                leaders = setOf("monochrome", "gesture", "woven"),
            ),
            Profile(
                "picked photography",
                pickedTerms = setOf("medium:photography"),
                leaders = setOf("city-photos", "portraits", "coastline"),
            ),
            Profile(
                "picked sculpture and installation",
                pickedTerms = setOf("medium:sculpture", "medium:installation"),
                leaders = setOf("forest-room", "bronze", "tapes"),
                first = "forest-room",
            ),
            Profile(
                "picked dansaekhwa and quiet",
                pickedTerms = setOf("style:dansaekhwa", "mood:quiet-meditative"),
                leaders = setOf("monochrome", "ink-mountains"),
                first = "monochrome",
            ),
            Profile(
                "picked archive and memory",
                pickedTerms = setOf("theme:archive", "theme:memory"),
                leaders = setOf("tapes", "documents"),
            ),
            Profile(
                "picked identity and gender",
                pickedTerms = setOf("theme:identity", "theme:gender"),
                leaders = setOf("portraits", "figures", "bodies"),
                first = "portraits",
            ),
            Profile(
                "picked drawing and intimate",
                pickedTerms = setOf("medium:drawing", "mood:intimate"),
                leaders = setOf("diary", "figures", "vessels"),
                first = "diary",
            ),
            Profile(
                "picked korean painting and landscape",
                pickedTerms = setOf("style:korean-painting", "theme:landscape"),
                leaders = setOf("ink-mountains", "coastline"),
                first = "ink-mountains",
            ),
            Profile(
                "saved a show by an artist with another show",
                saved = setOf("gesture"),
                leaders = setOf("woven", "monochrome"),
                first = "woven",
            ),
            Profile(
                "picked a show whose photographer has another show",
                pickedShows = setOf("city-photos"),
                leaders = setOf("coastline"),
                first = "coastline",
            ),
            Profile(
                "picked photography and saved a photographer's show",
                pickedTerms = setOf("medium:photography"),
                saved = setOf("coastline"),
                leaders = setOf("city-photos", "portraits"),
                first = "city-photos",
            ),
            Profile(
                "picked painting and abstract and saw less of one painting",
                pickedTerms = setOf("medium:painting", "style:abstract"),
                dismissed = listOf(dismissal("gesture", terms = setOf("medium:painting", "style:abstract"))),
                // Each seen-less term costs half a saved term (0.175): carrying both sinks a 0.30 pick to zero.
                leaders = setOf("monochrome", "woven", "figures"),
                first = "figures",
            ),
            Profile(
                "saved a painting and saw less of the artist's other show",
                saved = setOf("gesture"),
                dismissed =
                    listOf(
                        dismissal(
                            "woven",
                            terms = setOf("medium:painting", "style:abstract"),
                            artists = setOf(TasteProfileCatalogue.kim.id),
                        ),
                    ),
                // Saved overlap is 0.35 × shared/union terms: monochrome 0.14 and figures 0.07 lose 0.175 per
                // seen-less term and floor at zero, bronze keeps 0.07 from 기념비적.
                leaders = setOf("monochrome", "bronze", "figures"),
                first = "bronze",
            ),
            Profile(
                "loved a show by an artist with another show",
                visits = listOf(visit("gesture", VisitFeeling.LOVED)),
                // Weighed like the save above: woven 0.50 for 김하늘 + 0.35 × 2/4 terms, monochrome 0.35 × 2/5.
                leaders = setOf("woven", "monochrome"),
                first = "woven",
            ),
            Profile(
                "picked painting and abstract and did not like a painting",
                pickedTerms = setOf("medium:painting", "style:abstract"),
                visits = listOf(visit("gesture", VisitFeeling.NOT_FOR_ME)),
                // Each term shared with the disliked show costs 0.125 and 김하늘 another 0.125: monochrome
                // 0.45 − 0.25 = 0.20 leads figures 0.30 − 0.125 = 0.175, and woven falls to 0.45 − 0.375 = 0.075.
                // One 덜 보기 on the same terms would put figures first (the profile above).
                leaders = setOf("monochrome", "figures", "woven"),
                first = "monochrome",
            ),
            Profile(
                "loved a quiet show tagged calm to linger",
                visits = listOf(visit("ink-mountains", VisitFeeling.LOVED, VisitTag.CALM_TO_LINGER)),
                // Saved-weight overlap alone puts coastline (0.35 × 1/5) above monochrome (0.35 × 1/6); the tag
                // counts 고요함·명상적 like a picked term (0.30), so monochrome leads.
                leaders = setOf("monochrome", "coastline"),
                first = "monochrome",
            ),
        )

    @Test
    fun `every profile's first places are the shows worked out by hand`() {
        val misses =
            profiles.mapNotNull { profile ->
                val ids = recommend(profile).map { it.exhibition.id }
                val top = ids.take(profile.leaders.size).toSet()
                val firstMissed = profile.first != null && ids.first() != profile.first
                when {
                    top != profile.leaders -> "${profile.name}: expected ${profile.leaders}, got ${ids.take(4)}"
                    firstMissed -> "${profile.name}: expected ${profile.first} first, got ${ids.take(4)}"
                    else -> null
                }
            }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

    @Test
    fun `every leading card's first reason names something in the profile`() {
        for (profile in profiles) {
            for (card in recommend(profile).take(profile.leaders.size)) {
                val reason = card.evidence.firstOrNull() ?: fail("${profile.name}: ${card.exhibition.id} has no reason")
                val anchors = profile.pickedShows + profile.saved + profile.visits.map { it.exhibitionId }
                val named =
                    when (reason) {
                        is RecommendationEvidence.ChosenTerm -> reason.term.id in profile.pickedTerms
                        is RecommendationEvidence.VisitedMood -> reason.term.id in profile.lovedMoodTermIds()
                        is RecommendationEvidence.ArtTermMatch -> reason.anchor.exhibitionId in anchors
                        is RecommendationEvidence.ArtistMatch -> reason.anchor.exhibitionId in anchors
                        is RecommendationEvidence.TextSimilarity -> reason.anchor.exhibitionId in anchors
                        else -> false
                    }
                assertTrue(named, "${profile.name}: ${card.exhibition.id} leads with $reason")
            }
        }
    }

    @Test
    fun `no profile is shown what it picked or saved or saw less of`() {
        for (profile in profiles) {
            val shown = recommend(profile).map { it.exhibition.id }.toSet()
            val excluded =
                profile.pickedShows + profile.saved + profile.dismissed.map { it.exhibitionId } +
                    profile.visits.map { it.exhibitionId }
            assertTrue(shown.intersect(excluded).isEmpty(), "${profile.name} shows ${shown.intersect(excluded)}")
        }
    }

    @Test
    fun `undoing every dismissal restores the profile's ranking exactly`() {
        for (profile in profiles.filter { it.dismissed.isNotEmpty() }) {
            val undone = dismissalsOf(profile).remove(profile.dismissed.map { it.exhibitionId }.toSet())
            assertEquals(
                recommend(profile, TasteDismissals.EMPTY),
                recommend(profile, undone),
                "${profile.name} kept a trace after undo",
            )
        }
    }

    private fun recommend(
        profile: Profile,
        dismissals: TasteDismissals = dismissalsOf(profile),
    ): List<ExhibitionRecommendation> =
        recommender.prepare(exhibitions, previous = null).recommend(
            RecommendationContext(
                bookmarkedExhibitionIds = profile.saved,
                visits = profile.visits,
                today = today,
                tasteSeed = TasteSeed(termIds = profile.pickedTerms, exhibitionIds = profile.pickedShows),
                dismissals = dismissals,
            ),
        )

    private fun dismissalsOf(profile: Profile): TasteDismissals =
        profile.dismissed.fold(TasteDismissals.EMPTY) { dismissals, dismissal -> dismissals.dismiss(dismissal) }

    private fun Profile.lovedMoodTermIds(): Set<String> =
        visits
            .filter { it.feeling == VisitFeeling.LOVED }
            .flatMap { visit -> visit.tags.mapNotNull(VisitTag::moodTermId) }
            .toSet()

    private companion object {
        fun visit(
            id: String,
            feeling: VisitFeeling,
            vararg tags: VisitTag,
        ) = ExhibitionVisit(
            clientRecordId = "visit-$id",
            exhibitionId = id,
            snapshot = ExhibitionVisitSnapshot.from(exhibitions.first { it.id == id }),
            createdAt = Instant.parse("2026-11-01T10:00:00Z"),
            feelingWire = feeling.wire,
            tagWires = tags.map(VisitTag::wire),
        )

        fun dismissal(
            id: String,
            terms: Set<String> = emptySet(),
            artists: Set<String> = emptySet(),
        ) = TasteDismissal(id, LocalDate(2026, 12, 4), terms, artists)
    }
}
