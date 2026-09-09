package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.geographicDistanceKm
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Zero-network bilingual content recommender for the current organic catalogue. */
class LocalExhibitionRecommender : ExhibitionRecommender {
    /** Prepares immutable catalogue vectors while reusing an exactly matching prior index. */
    override fun prepare(
        catalogue: List<Exhibition>,
        previous: ExhibitionRecommendationIndex?,
    ): ExhibitionRecommendationIndex {
        val indexable = catalogue.sortedBy(Exhibition::id)
        require(indexable.map(Exhibition::id).distinct().size == indexable.size) {
            "catalogue must not contain duplicate exhibition IDs"
        }
        val key = RecommendationIndexKey(FEATURE_SCHEMA_VERSION, indexable)
        if (previous is LocalExhibitionRecommendationIndex && previous.key == key) return previous

        val rawFeaturesById = indexable.associate { it.id to it.rawFeatures() }
        val vectorizer = LocalContentVectorizer(rawFeaturesById.values)
        val featuresById =
            indexable.associate { exhibition ->
                exhibition.id to
                    PreparedExhibitionFeatures(
                        exhibition = exhibition,
                        vector = vectorizer.vector(rawFeaturesById.getValue(exhibition.id)),
                        diversityFeatures = exhibition.diversityFeatures(),
                        artistIds = exhibition.artists.mapTo(mutableSetOf(), ExhibitionArtist::id),
                        artistsById = exhibition.artists.associateBy(ExhibitionArtist::id),
                        termIds = exhibition.artTerms.mapTo(mutableSetOf(), ArtTerm::id),
                        termsById = exhibition.artTerms.associateBy(ArtTerm::id),
                        evidenceAnchor = RecommendationEvidenceAnchor.from(exhibition),
                    )
            }
        return LocalExhibitionRecommendationIndex(
            key = key,
            featuresById = featuresById,
        )
    }
}

private data class RecommendationIndexKey(
    val featureSchemaVersion: Int,
    val exhibitionsById: List<Exhibition>,
)

private data class PreparedExhibitionFeatures(
    val exhibition: Exhibition,
    val vector: Map<Int, Double>,
    val diversityFeatures: Set<Int>,
    val artistIds: Set<String>,
    val artistsById: Map<String, ExhibitionArtist>,
    val termIds: Set<String>,
    val termsById: Map<String, ArtTerm>,
    val evidenceAnchor: RecommendationEvidenceAnchor,
)

private class LocalExhibitionRecommendationIndex(
    val key: RecommendationIndexKey,
    private val featuresById: Map<String, PreparedExhibitionFeatures>,
) : ExhibitionRecommendationIndex {
    /** Returns deterministic, diverse recommendations without persisting a taste vector. */
    override fun recommend(context: RecommendationContext): List<ExhibitionRecommendation> {
        if (context.limit == 0) return emptyList()
        val indexable = key.exhibitionsById.map { featuresById.getValue(it.id) }
        val eligible =
            indexable
                .filter { it.exhibition.isLocallyDiscoverable(context.today) }
        if (eligible.isEmpty()) return emptyList()

        val visitedIds = context.visits.mapTo(mutableSetOf(), { it.exhibitionId })
        val savedAnchors = indexable.filter { it.exhibition.id in context.bookmarkedExhibitionIds }
        val visitedAnchors = indexable.filter { it.exhibition.id in visitedIds }
        val followedIds = context.followedGalleries.mapNotNullTo(mutableSetOf()) { it.galleryId }
        val followedKeys = context.followedGalleries.mapTo(mutableSetOf()) { it.galleryKey }

        val ranked =
            eligible
                .asSequence()
                .filterNot {
                    it.exhibition.id in context.bookmarkedExhibitionIds || it.exhibition.id in visitedIds
                }.mapNotNull { candidate ->
                    val exhibition = candidate.exhibition
                    val distanceKm = context.origin?.let { origin -> exhibition.distanceFrom(origin) }
                    if (
                        context.maxDistanceKm != null &&
                        (distanceKm == null || distanceKm > context.maxDistanceKm)
                    ) {
                        return@mapNotNull null
                    }
                    val savedArtistMatch =
                        candidate.bestArtistMatch(savedAnchors, RecommendationSignalSource.SAVED)
                    val visitedArtistMatch =
                        candidate.bestArtistMatch(visitedAnchors, RecommendationSignalSource.VISITED)
                    val savedTermMatch =
                        candidate.bestArtTermMatch(savedAnchors, RecommendationSignalSource.SAVED)
                    val visitedTermMatch =
                        candidate.bestArtTermMatch(visitedAnchors, RecommendationSignalSource.VISITED)
                    val savedTextMatch =
                        bestTextMatch(
                            candidateVector = candidate.vector,
                            anchors = savedAnchors,
                            source = RecommendationSignalSource.SAVED,
                        )
                    val visitedTextMatch =
                        bestTextMatch(
                            candidateVector = candidate.vector,
                            anchors = visitedAnchors,
                            source = RecommendationSignalSource.VISITED,
                        )
                    val followed =
                        exhibition.galleryId?.let(followedIds::contains) == true ||
                            galleryKey(exhibition.venueNameKo, exhibition.venueNameEn) in followedKeys
                    val proximity = distanceKm?.let(::proximityScore) ?: 0.0
                    val daysUntilClose = context.today.daysUntil(exhibition.closingDate)
                    val closingSoon = daysUntilClose in 0..7
                    val editorPick = exhibition.editorId != null
                    val followedScore = if (followed) FOLLOWED_GALLERY_WEIGHT else 0.0
                    val featuredScore = if (exhibition.isFeatured) FEATURED_WEIGHT else 0.0
                    val editorScore = if (editorPick) EDITOR_WEIGHT else 0.0
                    val closingScore = if (closingSoon) CLOSING_WEIGHT else 0.0
                    val score =
                        (savedArtistMatch?.strength ?: 0.0) * SAVED_ARTIST_WEIGHT +
                            (visitedArtistMatch?.strength ?: 0.0) * VISITED_ARTIST_WEIGHT +
                            (savedTermMatch?.strength ?: 0.0) * SAVED_ART_TERM_WEIGHT +
                            (visitedTermMatch?.strength ?: 0.0) * VISITED_ART_TERM_WEIGHT +
                            (savedTextMatch?.strength ?: 0.0) * SAVED_TEXT_WEIGHT +
                            (visitedTextMatch?.strength ?: 0.0) * VISITED_TEXT_WEIGHT +
                            followedScore +
                            proximity * PROXIMITY_WEIGHT +
                            featuredScore +
                            editorScore +
                            closingScore
                    val evidence =
                        buildList {
                            savedArtistMatch?.let { add(it.scored(SAVED_ARTIST_WEIGHT)) }
                            visitedArtistMatch?.let { add(it.scored(VISITED_ARTIST_WEIGHT)) }
                            savedTermMatch?.let { add(it.scored(SAVED_ART_TERM_WEIGHT)) }
                            visitedTermMatch?.let { add(it.scored(VISITED_ART_TERM_WEIGHT)) }
                            savedTextMatch?.let { add(it.scored(SAVED_TEXT_WEIGHT)) }
                            visitedTextMatch?.let { add(it.scored(VISITED_TEXT_WEIGHT)) }
                            if (followed) add(ScoredEvidence(RecommendationEvidence.FollowedGallery, followedScore))
                            if (proximity >= NEARBY_REASON_THRESHOLD) {
                                add(ScoredEvidence(RecommendationEvidence.Nearby, proximity * PROXIMITY_WEIGHT))
                            }
                            if (exhibition.isFeatured) {
                                add(ScoredEvidence(RecommendationEvidence.Featured, featuredScore))
                            }
                            if (editorPick) {
                                add(ScoredEvidence(RecommendationEvidence.EditorCurated, editorScore))
                            }
                            if (closingSoon) {
                                add(ScoredEvidence(RecommendationEvidence.ClosingSoon, closingScore))
                            }
                        }.strongestDistinctEvidence()
                    if (evidence.isEmpty()) return@mapNotNull null
                    ExhibitionRecommendation(
                        exhibition = exhibition,
                        scoreBasisPoints = (score / MAX_SCORE * 10_000).roundToInt().coerceIn(0, 10_000),
                        evidence = evidence,
                    )
                }.sortedWith(
                    compareByDescending<ExhibitionRecommendation> { it.scoreBasisPoints }
                        .thenBy { it.exhibition.id },
                ).toList()
        return diversify(ranked, context.limit)
    }

    private fun diversify(
        ranked: List<ExhibitionRecommendation>,
        limit: Int,
    ): List<ExhibitionRecommendation> {
        val galleryCounts = mutableMapOf<String, Int>()
        val result = mutableListOf<ExhibitionRecommendation>()
        for (recommendation in ranked) {
            val gallery = recommendation.exhibition.galleryIdentity()
            if ((galleryCounts[gallery] ?: 0) >= MAX_PER_GALLERY) continue
            val candidateFeatures = featuresById.getValue(recommendation.exhibition.id).diversityFeatures
            val nearDuplicates =
                result.filter { selected ->
                    jaccard(
                        candidateFeatures,
                        featuresById.getValue(selected.exhibition.id).diversityFeatures,
                    ) >=
                        NEAR_DUPLICATE_JACCARD
                }
            if (
                nearDuplicates.any { it.exhibition.galleryIdentity() == gallery } ||
                nearDuplicates.size >= MAX_PER_CONTENT_CLUSTER
            ) {
                continue
            }
            result += recommendation
            galleryCounts[gallery] = (galleryCounts[gallery] ?: 0) + 1
            if (result.size == limit) break
        }
        return result
    }
}

internal fun Exhibition.isLocallyDiscoverable(today: kotlinx.datetime.LocalDate): Boolean =
    closingDate >= today && openingDate <= today.plus(UPCOMING_VISIBILITY_DAYS, DateTimeUnit.DAY)

private class LocalContentVectorizer(
    rawDocuments: Collection<Map<Int, Int>>,
) {
    private val documentCount = rawDocuments.size.toDouble()
    private val inverseDocumentFrequency: Map<Int, Double> =
        rawDocuments
            .flatMap { it.keys }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) -> ln((documentCount + 1.0) / (count + 1.0)) + 1.0 }

    fun vector(rawFeatures: Map<Int, Int>): Map<Int, Double> {
        val weighted =
            rawFeatures.mapValues { (feature, count) ->
                (1.0 + ln(count.toDouble())) * inverseDocumentFrequency.getValue(feature)
            }
        val norm = sqrt(weighted.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else weighted.mapValues { it.value / norm }
    }
}

private fun Exhibition.rawFeatures(): Map<Int, Int> {
    val text =
        listOf(
            nameKo,
            nameEn,
            descriptionKo,
            descriptionEn,
            creditsKo,
            creditsEn,
        ).joinToString(" ")
    val normalized = text.canonicalSearchCodePoints()
    val features = mutableMapOf<Int, Int>()
    for (size in MIN_NGRAM_SIZE..MAX_NGRAM_SIZE) {
        if (normalized.size < size) continue
        for (index in 0..normalized.size - size) {
            val feature = stableFeatureHash(normalized.subList(index, index + size))
            features[feature] = (features[feature] ?: 0) + 1
        }
    }
    return features
}

private data class EvidenceMatch(
    val evidence: RecommendationEvidence,
    val strength: Double,
) {
    init {
        require(strength in 0.0..1.0) { "evidence strength must be between zero and one" }
    }

    fun scored(weight: Double): ScoredEvidence = ScoredEvidence(evidence, strength * weight)
}

private data class ScoredEvidence(
    val evidence: RecommendationEvidence,
    val contribution: Double,
)

private fun PreparedExhibitionFeatures.bestArtistMatch(
    anchors: List<PreparedExhibitionFeatures>,
    source: RecommendationSignalSource,
): EvidenceMatch? {
    if (artistIds.isEmpty()) return null
    var bestAnchor: PreparedExhibitionFeatures? = null
    var bestArtistId: String? = null
    var bestStrength = 0.0
    for (anchor in anchors) {
        val intersectionSize = sharedIdentifierCount(artistIds, anchor.artistIds)
        if (intersectionSize == 0) continue
        val matchedId = firstSharedIdentifier(artistIds, anchor.artistIds) ?: continue
        val strength = symmetricOverlapStrength(artistIds, anchor.artistIds, intersectionSize)
        if (
            strength > bestStrength ||
            (
                strength == bestStrength &&
                    isStableMatchEarlier(
                        anchorId = anchor.exhibition.id,
                        matchedId = matchedId,
                        currentAnchorId = bestAnchor?.exhibition?.id,
                        currentMatchedId = bestArtistId,
                    )
            )
        ) {
            bestAnchor = anchor
            bestArtistId = matchedId
            bestStrength = strength
        }
    }
    val anchor = bestAnchor ?: return null
    val artistId = bestArtistId ?: return null
    return EvidenceMatch(
        evidence =
            RecommendationEvidence.ArtistMatch(
                source = source,
                anchor = anchor.evidenceAnchor,
                artist = artistsById.getValue(artistId),
            ),
        strength = bestStrength,
    )
}

private fun PreparedExhibitionFeatures.bestArtTermMatch(
    anchors: List<PreparedExhibitionFeatures>,
    source: RecommendationSignalSource,
): EvidenceMatch? {
    if (termIds.isEmpty()) return null
    var bestAnchor: PreparedExhibitionFeatures? = null
    var bestTermId: String? = null
    var bestStrength = 0.0
    for (anchor in anchors) {
        val intersectionSize = sharedIdentifierCount(termIds, anchor.termIds)
        if (intersectionSize == 0) continue
        val matchedId = firstSharedIdentifier(termIds, anchor.termIds) ?: continue
        val strength = symmetricOverlapStrength(termIds, anchor.termIds, intersectionSize)
        if (
            strength > bestStrength ||
            (
                strength == bestStrength &&
                    isStableMatchEarlier(
                        anchorId = anchor.exhibition.id,
                        matchedId = matchedId,
                        currentAnchorId = bestAnchor?.exhibition?.id,
                        currentMatchedId = bestTermId,
                    )
            )
        ) {
            bestAnchor = anchor
            bestTermId = matchedId
            bestStrength = strength
        }
    }
    val anchor = bestAnchor ?: return null
    val termId = bestTermId ?: return null
    return EvidenceMatch(
        evidence =
            RecommendationEvidence.ArtTermMatch(
                source = source,
                anchor = anchor.evidenceAnchor,
                term = termsById.getValue(termId),
            ),
        strength = bestStrength,
    )
}

private fun bestTextMatch(
    candidateVector: Map<Int, Double>,
    anchors: List<PreparedExhibitionFeatures>,
    source: RecommendationSignalSource,
): EvidenceMatch? {
    var bestAnchor: PreparedExhibitionFeatures? = null
    var bestStrength = 0.0
    for (anchor in anchors) {
        val similarity = cosine(candidateVector, anchor.vector).coerceIn(0.0, 1.0)
        if (
            similarity > SIMILARITY_REASON_THRESHOLD &&
            (
                similarity > bestStrength ||
                    (
                        similarity == bestStrength &&
                            (bestAnchor == null || anchor.exhibition.id < bestAnchor.exhibition.id)
                    )
            )
        ) {
            bestAnchor = anchor
            bestStrength = similarity
        }
    }
    val anchor = bestAnchor ?: return null
    return EvidenceMatch(
        evidence = RecommendationEvidence.TextSimilarity(source, anchor.evidenceAnchor),
        strength = bestStrength,
    )
}

private fun sharedIdentifierCount(
    first: Set<String>,
    second: Set<String>,
): Int {
    if (first.isEmpty() || second.isEmpty()) return 0
    val smaller = if (first.size <= second.size) first else second
    val larger = if (smaller === first) second else first
    var intersectionSize = 0
    for (id in smaller) {
        if (id in larger) intersectionSize += 1
    }
    return intersectionSize
}

private fun firstSharedIdentifier(
    first: Set<String>,
    second: Set<String>,
): String? {
    val smaller = if (first.size <= second.size) first else second
    val larger = if (smaller === first) second else first
    var firstSharedId: String? = null
    for (id in smaller) {
        if (id in larger && (firstSharedId == null || id < firstSharedId)) firstSharedId = id
    }
    return firstSharedId
}

private fun symmetricOverlapStrength(
    first: Set<String>,
    second: Set<String>,
    intersectionSize: Int,
): Double = intersectionSize.toDouble() / (first.size + second.size - intersectionSize)

private fun isStableMatchEarlier(
    anchorId: String,
    matchedId: String,
    currentAnchorId: String?,
    currentMatchedId: String?,
): Boolean =
    currentAnchorId == null ||
        anchorId < currentAnchorId ||
        (anchorId == currentAnchorId && (currentMatchedId == null || matchedId < currentMatchedId))

private fun List<ScoredEvidence>.strongestDistinctEvidence(): List<RecommendationEvidence> =
    sortedWith(
        compareBy<ScoredEvidence> { it.evidence.evidenceTier() }
            .thenByDescending { it.contribution }
            .thenBy { it.evidence.stableSortKey() },
    ).distinctBy { it.evidence.deduplicationKey() }
        .take(MAX_EVIDENCE)
        .map(ScoredEvidence::evidence)

private fun RecommendationEvidence.evidenceTier(): Int =
    when (this) {
        is RecommendationEvidence.ArtistMatch -> 0
        is RecommendationEvidence.ArtTermMatch -> 1
        else -> 2
    }

private fun RecommendationEvidence.deduplicationKey(): String =
    when (this) {
        is RecommendationEvidence.ArtistMatch -> "artist:${artist.id}"
        is RecommendationEvidence.ArtTermMatch -> "term:${term.id}"
        is RecommendationEvidence.TextSimilarity -> "text:${source.ordinal}"
        RecommendationEvidence.FollowedGallery -> "followed_gallery"
        RecommendationEvidence.Nearby -> "nearby"
        RecommendationEvidence.Featured -> "featured"
        RecommendationEvidence.EditorCurated -> "editor_curated"
        RecommendationEvidence.ClosingSoon -> "closing_soon"
    }

private fun RecommendationEvidence.stableSortKey(): String =
    when (this) {
        is RecommendationEvidence.ArtistMatch -> "0:${source.ordinal}:${artist.id}:${anchor.exhibitionId}"
        is RecommendationEvidence.ArtTermMatch -> "1:${source.ordinal}:${term.id}:${anchor.exhibitionId}"
        is RecommendationEvidence.TextSimilarity -> "2:${source.ordinal}:${anchor.exhibitionId}"
        RecommendationEvidence.FollowedGallery -> "3"
        RecommendationEvidence.Nearby -> "4"
        RecommendationEvidence.Featured -> "5"
        RecommendationEvidence.EditorCurated -> "6"
        RecommendationEvidence.ClosingSoon -> "7"
    }

private fun cosine(
    first: Map<Int, Double>,
    second: Map<Int, Double>,
): Double {
    if (first.isEmpty() || second.isEmpty()) return 0.0
    val smaller = if (first.size <= second.size) first else second
    val larger = if (smaller === first) second else first
    return smaller.entries.sumOf { (feature, weight) -> weight * (larger[feature] ?: 0.0) }
}

private fun Exhibition.distanceFrom(origin: GeoPoint): Double? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return null
    return geographicDistanceKm(origin, point)
}

private fun proximityScore(distanceKm: Double): Double = (1.0 - distanceKm / PROXIMITY_RANGE_KM).coerceIn(0.0, 1.0)

private fun Exhibition.galleryIdentity(): String =
    galleryId ?: "${galleryKey(venueNameKo, venueNameEn)}:$latitude:$longitude"

private const val UPCOMING_VISIBILITY_DAYS = 14
private const val FEATURE_SCHEMA_VERSION = 3
private const val MIN_NGRAM_SIZE = 2
private const val MAX_NGRAM_SIZE = 3
private const val SAVED_ARTIST_WEIGHT = 0.50
private const val VISITED_ARTIST_WEIGHT = 0.35
private const val SAVED_ART_TERM_WEIGHT = 0.35
private const val VISITED_ART_TERM_WEIGHT = 0.25
private const val SAVED_TEXT_WEIGHT = 0.20
private const val VISITED_TEXT_WEIGHT = 0.12
private const val FOLLOWED_GALLERY_WEIGHT = 0.18
private const val PROXIMITY_WEIGHT = 0.20
private const val FEATURED_WEIGHT = 0.08
private const val EDITOR_WEIGHT = 0.05
private const val CLOSING_WEIGHT = 0.05
private const val MAX_SCORE =
    SAVED_ARTIST_WEIGHT + VISITED_ARTIST_WEIGHT + SAVED_ART_TERM_WEIGHT +
        VISITED_ART_TERM_WEIGHT + SAVED_TEXT_WEIGHT + VISITED_TEXT_WEIGHT +
        FOLLOWED_GALLERY_WEIGHT + PROXIMITY_WEIGHT + FEATURED_WEIGHT + EDITOR_WEIGHT +
        CLOSING_WEIGHT
private const val PROXIMITY_RANGE_KM = 5.0
private const val SIMILARITY_REASON_THRESHOLD = 0.05
private const val NEARBY_REASON_THRESHOLD = 0.50
private const val MAX_EVIDENCE = 2
private const val MAX_PER_GALLERY = 2
private const val MAX_PER_CONTENT_CLUSTER = 2
private const val NEAR_DUPLICATE_JACCARD = 0.80

private fun String.canonicalSearchCodePoints(): List<Int> {
    val source = lowercase().toCodePoints()
    val composed = mutableListOf<Int>()
    source.forEach { codePoint ->
        val previous = composed.lastOrNull()
        when {
            previous != null && previous in HANGUL_L_BASE until HANGUL_L_BASE + HANGUL_L_COUNT &&
                codePoint in HANGUL_V_BASE until HANGUL_V_BASE + HANGUL_V_COUNT -> {
                composed[composed.lastIndex] =
                    HANGUL_S_BASE +
                    (previous - HANGUL_L_BASE) * HANGUL_N_COUNT +
                    (codePoint - HANGUL_V_BASE) * HANGUL_T_COUNT
            }

            previous != null && previous in HANGUL_S_BASE until HANGUL_S_BASE + HANGUL_S_COUNT &&
                (previous - HANGUL_S_BASE) % HANGUL_T_COUNT == 0 &&
                codePoint in HANGUL_T_BASE + 1 until HANGUL_T_BASE + HANGUL_T_COUNT -> {
                composed[composed.lastIndex] = previous + codePoint - HANGUL_T_BASE
            }

            codePoint in COMBINING_MARK_START..COMBINING_MARK_END -> {
                // Accent marks are folded into their base letter for search.
            }

            else -> {
                composed += foldLatinCodePoint(codePoint)
            }
        }
    }
    return composed.filter { codePoint ->
        codePoint <= Char.MAX_VALUE.code && codePoint.toChar().isLetterOrDigit()
    }
}

private fun String.toCodePoints(): List<Int> {
    val result = mutableListOf<Int>()
    var index = 0
    while (index < length) {
        val first = this[index]
        if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            val high = first.code - HIGH_SURROGATE_BASE
            val low = this[index + 1].code - LOW_SURROGATE_BASE
            result += SUPPLEMENTARY_BASE + (high shl 10) + low
            index += 2
        } else {
            result += first.code
            index += 1
        }
    }
    return result
}

private fun stableFeatureHash(codePoints: List<Int>): Int =
    codePoints.fold(FNV_OFFSET_BASIS) { hash, codePoint -> (hash xor codePoint) * FNV_PRIME }

private fun Exhibition.diversityFeatures(): Set<Int> {
    val descriptive = listOf(descriptionKo, descriptionEn, creditsKo, creditsEn).joinToString(" ")
    val text = descriptive.ifBlank { listOf(nameKo, nameEn).joinToString(" ") }
    val codePoints = text.canonicalSearchCodePoints()
    if (codePoints.size < MAX_NGRAM_SIZE) return setOf(stableFeatureHash(codePoints))
    return (0..codePoints.size - MAX_NGRAM_SIZE)
        .mapTo(mutableSetOf()) { index ->
            stableFeatureHash(codePoints.subList(index, index + MAX_NGRAM_SIZE))
        }
}

private fun jaccard(
    first: Set<Int>,
    second: Set<Int>,
): Double {
    if (first.isEmpty() && second.isEmpty()) return 1.0
    val intersection = first.count(second::contains)
    val union = first.size + second.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}

private fun foldLatinCodePoint(codePoint: Int): Int =
    when (codePoint.toChar()) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'.code
        'ç', 'ć', 'ĉ', 'ċ', 'č' -> 'c'.code
        'é', 'è', 'ê', 'ë' -> 'e'.code
        'í', 'ì', 'î', 'ï' -> 'i'.code
        'ñ' -> 'n'.code
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'.code
        'ú', 'ù', 'û', 'ü' -> 'u'.code
        'ý', 'ÿ' -> 'y'.code
        'š', 'ś', 'ŝ', 'ş' -> 's'.code
        'ž', 'ź', 'ż' -> 'z'.code
        'ř', 'ŕ' -> 'r'.code
        'ľ', 'ĺ', 'ļ', 'ł' -> 'l'.code
        'ğ', 'ĝ', 'ġ', 'ģ' -> 'g'.code
        else -> codePoint
    }

private const val HANGUL_S_BASE = 0xAC00
private const val HANGUL_L_BASE = 0x1100
private const val HANGUL_V_BASE = 0x1161
private const val HANGUL_T_BASE = 0x11A7
private const val HANGUL_L_COUNT = 19
private const val HANGUL_V_COUNT = 21
private const val HANGUL_T_COUNT = 28
private const val HANGUL_N_COUNT = HANGUL_V_COUNT * HANGUL_T_COUNT
private const val HANGUL_S_COUNT = HANGUL_L_COUNT * HANGUL_N_COUNT
private const val COMBINING_MARK_START = 0x0300
private const val COMBINING_MARK_END = 0x036F
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val SUPPLEMENTARY_BASE = 0x10000
private const val FNV_OFFSET_BASIS = -0x7ee3623b
private const val FNV_PRIME = 16_777_619
