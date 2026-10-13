package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition

/**
 * The terms that describe a visitor's taste: those recurring most across the exhibitions they saved or
 * visited, ties broken by category (medium, style, theme, mood) then vocabulary order. The first pass
 * takes at most two per category so the tags read as a profile rather than a list of media.
 */
fun tasteTerms(
    anchors: List<Exhibition>,
    limit: Int = DEFAULT_TASTE_TERM_LIMIT,
): List<ArtTerm> {
    if (anchors.isEmpty() || limit <= 0) return emptyList()
    val counts = LinkedHashMap<String, TermCount>()
    anchors.forEach { anchor ->
        anchor.effectiveArtTerms().distinctBy(ArtTerm::id).forEach { term ->
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

private class TermCount(
    val term: ArtTerm,
    val count: Int,
)

private val VOCABULARY_RANK: Map<String, Int> =
    ART_TERM_VOCABULARY.withIndex().associate { it.value.term.id to it.index }

const val DEFAULT_TASTE_TERM_LIMIT = 4
private const val MAX_TASTE_TERMS_PER_CATEGORY = 2
