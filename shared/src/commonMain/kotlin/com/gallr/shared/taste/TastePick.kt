package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.recommendation.isLocallyDiscoverable
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** What the taste sheet offers: term chips and featured covers (spec 101 FR-001). */
data class TastePickChoices(
    val terms: List<ArtTerm>,
    val covers: List<Exhibition>,
)

/**
 * Reviewed terms carried by at least three running or upcoming exhibitions, most common first (then
 * category, then vocabulary order), at most three per category and ten in all; none at all when fewer
 * than four qualify, because a short list reads as broken. Covers are the first six featured exhibitions,
 * in Home order, that are still running or upcoming.
 */
fun tastePickChoices(
    catalogue: List<Exhibition>,
    featuredInHomeOrder: List<Exhibition>,
    today: LocalDate,
): TastePickChoices {
    val ranked =
        catalogue
            .filter { it.isLocallyDiscoverable(today) }
            .flatMap { exhibition -> exhibition.artTerms.distinctBy(ArtTerm::id) }
            .groupBy(ArtTerm::id)
            .values
            .map { occurrences -> occurrences.first() to occurrences.size }
            .filter { (_, count) -> count >= TASTE_PICK_MIN_EXHIBITIONS_PER_TERM }
            .sortedWith(
                compareByDescending<Pair<ArtTerm, Int>> { it.second }
                    .thenBy { it.first.category.ordinal }
                    .thenBy { VOCABULARY_RANK[it.first.id] ?: Int.MAX_VALUE }
                    .thenBy { it.first.id },
            ).map { it.first }
    val perCategory = mutableMapOf<ArtTermCategory, Int>()
    val terms =
        ranked
            .filter { term ->
                val count = perCategory[term.category] ?: 0
                (count < TASTE_PICK_MAX_TERMS_PER_CATEGORY).also { if (it) perCategory[term.category] = count + 1 }
            }.take(TASTE_PICK_MAX_TERMS)
    return TastePickChoices(
        terms = if (terms.size < TASTE_PICK_MIN_TERMS) emptyList() else terms,
        covers = featuredInHomeOrder.filter { it.isLocallyDiscoverable(today) }.take(TASTE_PICK_COVERS),
    )
}

/**
 * The labelled terms for picked ids, in vocabulary order: labels from the catalogue (the server's reviewed
 * names) first, the built-in vocabulary second. An id neither knows is dropped; it still ranks nothing.
 */
fun resolveArtTerms(
    termIds: Collection<String>,
    catalogue: List<Exhibition>,
): List<ArtTerm> {
    if (termIds.isEmpty()) return emptyList()
    val wanted = termIds.toSet()
    val fromCatalogue = catalogue.flatMap { it.artTerms }.filter { it.id in wanted }
    val fromVocabulary = ART_TERM_VOCABULARY.map { it.term }.filter { it.id in wanted }
    return (fromCatalogue + fromVocabulary)
        .distinctBy(ArtTerm::id)
        .sortedWith(compareBy<ArtTerm>({ VOCABULARY_RANK[it.id] ?: Int.MAX_VALUE }, { it.id }))
}

private const val TASTE_PICK_MIN_EXHIBITIONS_PER_TERM = 3
private const val TASTE_PICK_MAX_TERMS_PER_CATEGORY = 3
private const val TASTE_PICK_MAX_TERMS = 10
private const val TASTE_PICK_MIN_TERMS = 4
private const val TASTE_PICK_COVERS = 6

/**
 * What the visitor told gallr they like, on this device only (spec 101 FR-003): reviewed term ids and
 * exhibitions picked in the taste sheet. These are the visitor's own entries, not a derived profile; a
 * derived taste representation is still never stored (spec 101 FR-027).
 */
@Serializable
data class TasteSeed(
    val termIds: Set<String> = emptySet(),
    val exhibitionIds: Set<String> = emptySet(),
) {
    val size: Int get() = termIds.size + exhibitionIds.size

    val isEmpty: Boolean get() = size == 0

    /**
     * How many picks still mean something: every term, and the picked covers still in [catalogueIds]. A cover
     * whose exhibition left the catalogue cannot be shown or unpicked, so it stops counting.
     */
    fun liveSize(catalogueIds: Set<String>): Int = termIds.size + exhibitionIds.count { it in catalogueIds }

    companion object {
        val EMPTY = TasteSeed()
    }
}

/** The fewest selections the taste sheet accepts before it can start (spec 101 FR-002). */
const val TASTE_PICK_MIN_SELECTIONS = 3

/**
 * One exhibition the visitor asked to see less of. The reviewed term and credited artist ids are those it
 * carried when dismissed, so a later metadata edit cannot change which ids an undo releases.
 */
@Serializable
data class TasteDismissal(
    val exhibitionId: String,
    val closingDate: LocalDate,
    val termIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
)

/**
 * Standing dismissals. Per-id counts are derived by counting records, so they can never drop below zero;
 * every transition returns a new value with records ordered by exhibition id. The value is immutable, so the
 * derived sets and counts are computed once: the recommender reads them for every candidate.
 */
data class TasteDismissals(
    val records: List<TasteDismissal> = emptyList(),
) {
    val hiddenExhibitionIds: Set<String> = records.mapTo(mutableSetOf(), TasteDismissal::exhibitionId)

    /** Terms at least one standing record carries: kept out of the taste chips and listed in the sheet. */
    val dismissedTermIds: Set<String> = records.flatMapTo(mutableSetOf(), TasteDismissal::termIds)

    private val termCounts: Map<String, Int> = records.flatMap(TasteDismissal::termIds).groupingBy { it }.eachCount()

    private val artistCounts: Map<String, Int> =
        records.flatMap(TasteDismissal::artistIds).groupingBy { it }.eachCount()

    fun termCount(termId: String): Int = termCounts[termId] ?: 0

    fun artistCount(artistId: String): Int = artistCounts[artistId] ?: 0

    /** Adds a record, replacing any earlier one for the same exhibition. */
    fun dismiss(record: TasteDismissal): TasteDismissals =
        ordered(records.filterNot { it.exhibitionId == record.exhibitionId } + record)

    /** Deletes the records of these exhibitions: the snackbar undo, or a save, visit or follow lifting the hide. */
    fun remove(exhibitionIds: Set<String>): TasteDismissals =
        ordered(records.filterNot { it.exhibitionId in exhibitionIds })

    /** Stops a term counting against anything; the exhibitions stay hidden because the visitor hid the show. */
    fun releaseTerm(termId: String): TasteDismissals = ordered(records.map { it.copy(termIds = it.termIds - termId) })

    /** Drops records whose exhibition closed before [today]. */
    fun expire(today: LocalDate): TasteDismissals = ordered(records.filter { it.closingDate >= today })

    private fun ordered(records: List<TasteDismissal>) = TasteDismissals(records.sortedBy(TasteDismissal::exhibitionId))

    companion object {
        val EMPTY = TasteDismissals()
    }
}

/** Everything the taste features keep on the device. */
data class TastePickState(
    val seed: TasteSeed = TasteSeed.EMPTY,
    val skipped: Boolean = false,
    val dismissals: TasteDismissals = TasteDismissals.EMPTY,
) {
    companion object {
        val EMPTY = TastePickState()
    }
}
