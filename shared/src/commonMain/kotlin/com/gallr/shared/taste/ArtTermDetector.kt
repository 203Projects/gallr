package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.MAX_EXHIBITION_ART_TERMS
import com.gallr.shared.data.model.MAX_EXHIBITION_ART_TERMS_PER_CATEGORY

/**
 * Terms implied by an exhibition's own name and description, in vocabulary order (medium, style, theme,
 * mood). Korean synonyms match as substrings; English synonyms match whole words, ignoring case.
 */
fun detectArtTerms(
    nameKo: String?,
    nameEn: String?,
    descriptionKo: String?,
    descriptionEn: String?,
): List<ArtTerm> {
    val korean = listOfNotNull(nameKo, descriptionKo).joinToString(" ")
    val english = listOfNotNull(nameEn, descriptionEn).joinToString(" ").lowercase()
    if (korean.isBlank() && english.isBlank()) return emptyList()
    return ART_TERM_VOCABULARY
        .filter { pattern ->
            pattern.korean.any { korean.contains(it) } || pattern.english.any { english.containsWord(it) }
        }.map(ArtTermPattern::term)
}

/**
 * The editor's reviewed terms first, then detected terms that add something, within the same caps the
 * catalogue applies to reviewed metadata.
 */
fun Exhibition.effectiveArtTerms(): List<ArtTerm> {
    val terms = artTerms.toMutableList()
    val perCategory = artTerms.groupingBy(ArtTerm::category).eachCount().toMutableMap()
    for (term in detectArtTerms(nameKo, nameEn, descriptionKo, descriptionEn)) {
        if (terms.size >= MAX_EXHIBITION_ART_TERMS) break
        if (terms.any { it.id == term.id }) continue
        val count = perCategory[term.category] ?: 0
        if (count >= MAX_EXHIBITION_ART_TERMS_PER_CATEGORY) continue
        terms += term
        perCategory[term.category] = count + 1
    }
    return terms
}

private fun String.containsWord(word: String): Boolean = wordPattern(word).containsMatchIn(this)

private fun wordPattern(word: String): Regex = Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)")
