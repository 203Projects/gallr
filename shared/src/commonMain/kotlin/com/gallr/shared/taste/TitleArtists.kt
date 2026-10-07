package com.gallr.shared.taste

/**
 * An artist named in an exhibition's title. [keys] are the normalised names in each language, so two
 * titles name the same person when any key is shared ("게오르그 바젤리츠" or "georg baselitz").
 */
data class TitleArtist(
    val nameKo: String,
    val nameEn: String,
    val keys: Set<String>,
) {
    init {
        require(nameKo.isNotBlank() || nameEn.isNotBlank()) { "a title artist needs a name" }
        require(keys.isNotEmpty()) { "a title artist needs a key" }
    }
}

/**
 * The catalogue writes "전시명 | 작가, 작가": everything after the last pipe names the artists. Korean and
 * English lists pair by position when they have the same length; otherwise each name stands alone.
 */
fun titleArtists(
    nameKo: String,
    nameEn: String,
): List<TitleArtist> {
    val korean = artistSegment(nameKo)
    val english = artistSegment(nameEn)
    if (korean.size == english.size) {
        return korean.zip(english) { ko, en -> TitleArtist(ko, en, setOf(artistNameKey(ko), artistNameKey(en))) }
    }
    return korean.map { TitleArtist(it, "", setOf(artistNameKey(it))) } +
        english.map { TitleArtist("", it, setOf(artistNameKey(it))) }
}

/**
 * A museum show is often titled by the artist alone ("🏛️ 게오르그 바젤리츠") or leads with the name
 * ("곽훈: 근작"). Such a title names that artist only when the name is already known from a pipe
 * title elsewhere in the catalogue, so an ordinary title is never mistaken for a person.
 */
fun titleAsKnownArtist(
    nameKo: String,
    nameEn: String,
    knownKeys: Set<String>,
): TitleArtist? {
    val korean = leadingName(nameKo)?.takeIf { artistNameKey(it) in knownKeys }
    val english = leadingName(nameEn)?.takeIf { artistNameKey(it) in knownKeys }
    if (korean == null && english == null) return null
    val keys = listOfNotNull(korean, english).mapTo(mutableSetOf(), ::artistNameKey)
    // The other language's leading name rides along when the known one confirms the title is a person.
    val pairedKo = korean ?: leadingName(nameKo)
    val pairedEn = english ?: leadingName(nameEn)
    listOfNotNull(pairedKo, pairedEn).forEach { keys += artistNameKey(it) }
    return TitleArtist(pairedKo.orEmpty(), pairedEn.orEmpty(), keys)
}

/** Lower-cased, single-spaced, trimmed; the same for both languages. */
fun artistNameKey(name: String): String =
    name
        .trim()
        .lowercase()
        .split(WHITESPACE)
        .joinToString(" ")

private fun artistSegment(title: String): List<String> {
    val stripped = stripMuseumPrefix(title)
    val pipe = stripped.lastIndexOf('|')
    if (pipe < 0) return emptyList()
    return stripped
        .substring(pipe + 1)
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
}

private fun leadingName(title: String): String? {
    val stripped = stripMuseumPrefix(title)
    if (stripped.isBlank() || '|' in stripped) return null
    return stripped.substringBefore(':').trim().takeIf(String::isNotEmpty)
}

private fun stripMuseumPrefix(title: String): String =
    title
        .trim()
        .removePrefix(MUSEUM_PREFIX_WITH_SELECTOR)
        .removePrefix(MUSEUM_PREFIX)
        .trim()

private val WHITESPACE = Regex("\\s+")
private const val MUSEUM_PREFIX_WITH_SELECTOR = "🏛️"
private const val MUSEUM_PREFIX = "🏛"
