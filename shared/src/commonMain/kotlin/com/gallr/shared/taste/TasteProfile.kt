package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.VisitFeeling

/**
 * The terms that describe a visitor's taste: those recurring most across the exhibitions they saved or
 * visited and the terms they picked in the taste sheet (each pick counts like one anchor), ties broken by
 * category (medium, style, theme, mood) then vocabulary order. The first pass takes at most two per
 * category so the tags read as a profile rather than a list of media. [excluded] terms (those the visitor
 * asked to see less of) never appear.
 */
fun tasteTerms(
    anchors: List<Exhibition>,
    limit: Int = DEFAULT_TASTE_TERM_LIMIT,
    pickedTerms: List<ArtTerm> = emptyList(),
    excluded: Set<String> = emptySet(),
): List<ArtTerm> {
    if ((anchors.isEmpty() && pickedTerms.isEmpty()) || limit <= 0) return emptyList()
    val counts = LinkedHashMap<String, TermCount>()
    val sources = anchors.map { it.effectiveArtTerms() } + pickedTerms.map(::listOf)
    sources.forEach { terms ->
        terms.distinctBy(ArtTerm::id).filterNot { it.id in excluded }.forEach { term ->
            val current = counts[term.id]
            counts[term.id] = TermCount(term, (current?.count ?: 0) + 1)
        }
    }
    val ranked =
        counts.values
            .sortedWith(
                compareByDescending<TermCount> { it.count }
                    .thenBy { it.term.category.ordinal }
                    .thenBy { VOCABULARY_RANK[it.term.id] ?: Int.MAX_VALUE }
                    .thenBy { it.term.id },
            ).map(TermCount::term)
    val picked = mutableListOf<ArtTerm>()
    val perCategory = mutableMapOf<ArtTermCategory, Int>()
    for (term in ranked) {
        if (picked.size == limit) break
        val count = perCategory[term.category] ?: 0
        if (count >= MAX_TASTE_TERMS_PER_CATEGORY) continue
        picked += term
        perCategory[term.category] = count + 1
    }
    for (term in ranked) {
        if (picked.size == limit) break
        if (picked.none { it.id == term.id }) picked += term
    }
    return picked
}

/**
 * The exhibitions in [catalogue] whose text describes the visitor's taste: those saved, picked in the taste sheet or
 * visited. A visit marked 아쉬웠어요 is not one of them (spec 103 FR-028): it only lowers similar shows, so it never
 * names a taste term or a "because you…" line; the show still counts if it is also saved or picked.
 */
fun tasteAnchors(
    catalogue: List<Exhibition>,
    bookmarkedIds: Set<String>,
    visits: List<ExhibitionVisit>,
    pickedIds: Set<String>,
): List<Exhibition> {
    val visitedIds =
        visits
            .filterNot { it.feeling == VisitFeeling.NOT_FOR_ME }
            .mapTo(mutableSetOf()) { it.exhibitionId }
    return catalogue.filter { it.id in bookmarkedIds || it.id in visitedIds || it.id in pickedIds }
}

private class TermCount(
    val term: ArtTerm,
    val count: Int,
)

internal val VOCABULARY_RANK: Map<String, Int> =
    ART_TERM_VOCABULARY.withIndex().associate { it.value.term.id to it.index }

const val DEFAULT_TASTE_TERM_LIMIT = 4
private const val MAX_TASTE_TERMS_PER_CATEGORY = 2
