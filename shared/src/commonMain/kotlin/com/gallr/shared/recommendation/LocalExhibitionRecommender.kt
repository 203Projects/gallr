package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.geographicDistanceKm
import com.gallr.shared.taste.effectiveArtTerms
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

        val rawFeaturesById = indexable.associate { it.id to it.rawFeatures() }.withoutBoilerplate(indexable)
        val vectorizer = LocalContentVectorizer(rawFeaturesById.values)
        val detectedTermsById = indexable.associate { it.id to it.detectedArtTerms() }
        val termRarity = termRarity(detectedTermsById.values, catalogueSize = indexable.size)
        val featuresById =
            indexable.associate { exhibition ->
                val detected = detectedTermsById.getValue(exhibition.id)
                exhibition.id to
                    PreparedExhibitionFeatures(
                        exhibition = exhibition,
                        vector = vectorizer.vector(rawFeaturesById.getValue(exhibition.id)),
                        diversityFeatures = exhibition.diversityFeatures(),
                        artistIds = exhibition.artists.mapTo(mutableSetOf(), ExhibitionArtist::id),
                        artistsById = exhibition.artists.associateBy(ExhibitionArtist::id),
                        termIds = exhibition.artTerms.mapTo(mutableSetOf(), ArtTerm::id),
                        termsById = exhibition.artTerms.associateBy(ArtTerm::id),
                        detectedTermIds = detected.mapTo(mutableSetOf(), ArtTerm::id),
                        detectedTermsById = detected.associateBy(ArtTerm::id),
                        detectedTermRarity = detected.associate { it.id to termRarity.getValue(it.id) },
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
    /** The editor's reviewed terms: a shared one is a reason at full weight. */
    val termIds: Set<String>,
    val termsById: Map<String, ArtTerm>,
    /** Terms read from the exhibition's own text; a shared one counts by how rare it is in the catalogue. */
    val detectedTermIds: Set<String>,
    val detectedTermsById: Map<String, ArtTerm>,
    val detectedTermRarity: Map<String, Double>,
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
        val eligible = indexable.filter { it.exhibition.isLocallyDiscoverable(context.today) }
        if (eligible.isEmpty()) return emptyList()
        val signals =
            HistorySignals.from(
                indexable = indexable,
                bookmarkedExhibitionIds = context.bookmarkedExhibitionIds,
                visits = context.visits,
                followedGalleries = context.followedGalleries,
            )

        val ranked =
            eligible
                .asSequence()
                .filterNot {
                    it.exhibition.id in context.bookmarkedExhibitionIds || it.exhibition.id in signals.visitedIds
                }.mapNotNull { candidate ->
                    val distanceKm = context.origin?.let { origin -> candidate.exhibition.distanceFrom(origin) }
                    if (
                        context.maxDistanceKm != null &&
                        (distanceKm == null || distanceKm > context.maxDistanceKm)
                    ) {
                        return@mapNotNull null
                    }
                    val assessment = candidate.assess(signals, distanceKm, context.today, saved = false)
                    val evidence = assessment.scored.strongestDistinctEvidence()
                    if (evidence.isEmpty()) return@mapNotNull null
                    RankedCandidate(
                        recommendation =
                            ExhibitionRecommendation(
                                exhibition = candidate.exhibition,
                                scoreBasisPoints = assessment.scoreBasisPoints,
                                evidence = evidence,
                            ),
                        hasPersonalEvidence = assessment.hasPersonalEvidence,
                    )
                }.sortedWith(
                    compareByDescending<RankedCandidate> { it.hasPersonalEvidence }
                        .thenByDescending { it.recommendation.scoreBasisPoints }
                        .thenBy { it.recommendation.exhibition.id },
                ).map(RankedCandidate::recommendation)
                .toList()
        return diversify(ranked, context.limit)
    }

    /** Ranks the whole route-eligible pool around the origin; saved stays, visited goes, fillers stay. */
    override fun rankRouteCandidates(context: RouteRelevanceContext): List<RouteRelevance> {
        val indexable = key.exhibitionsById.map { featuresById.getValue(it.id) }
        val signals =
            HistorySignals.from(
                indexable = indexable,
                bookmarkedExhibitionIds = context.bookmarkedExhibitionIds,
                visits = context.visits,
                followedGalleries = context.followedGalleries,
            )
        return indexable
            .asSequence()
            .filter { it.exhibition.isLocallyDiscoverable(context.today) }
            .filterNot { it.exhibition.id in signals.visitedIds }
            .mapNotNull { candidate ->
                val distanceKm = candidate.exhibition.distanceFrom(context.origin) ?: return@mapNotNull null
                if (distanceKm > context.maxDistanceKm) return@mapNotNull null
                val saved = candidate.exhibition.id in context.bookmarkedExhibitionIds
                val assessment = candidate.assess(signals, distanceKm, context.today, saved)
                RouteRelevance(
                    exhibition = candidate.exhibition,
                    scoreBasisPoints = assessment.scoreBasisPoints,
                    evidence = assessment.scored.strongestDistinctEvidence(),
                    hasPersonalEvidence = assessment.hasPersonalEvidence,
                )
            }.sortedWith(
                compareByDescending<RouteRelevance> { it.scoreBasisPoints }
                    .thenBy { it.exhibition.id },
            ).toList()
    }

    /**
     * Scores one candidate against the visitor's history and the generic catalogue signals.
     *
     * A saved candidate receives [SAVED_STOP_WEIGHT] instead of taste matching, so it is presented as
     * saved and never with an inferred reason.
     */
    private fun PreparedExhibitionFeatures.assess(
        signals: HistorySignals,
        distanceKm: Double?,
        today: kotlinx.datetime.LocalDate,
        saved: Boolean,
    ): CandidateAssessment {
        val tasteMatches = if (saved) emptyList() else tasteMatches(signals)
        val followed =
            exhibition.galleryId?.let(signals.followedIds::contains) == true ||
                galleryKey(exhibition.venueNameKo, exhibition.venueNameEn) in signals.followedKeys
        val proximity = distanceKm?.let(::proximityScore) ?: 0.0
        val daysUntilClose = today.daysUntil(exhibition.closingDate)
        val closingSoon = daysUntilClose in 0..7
        val editorPick = exhibition.editorId != null
        val savedScore = if (saved) SAVED_STOP_WEIGHT else 0.0
        val followedScore = if (followed) FOLLOWED_GALLERY_WEIGHT else 0.0
        val featuredScore = if (exhibition.isFeatured) FEATURED_WEIGHT else 0.0
        val editorScore = if (editorPick) EDITOR_WEIGHT else 0.0
        val closingScore = if (closingSoon) CLOSING_WEIGHT else 0.0
        val score =
            savedScore +
                tasteMatches.sumOf(ScoredEvidence::contribution) +
                followedScore +
                proximity * PROXIMITY_WEIGHT +
                featuredScore +
                editorScore +
                closingScore
        val scored =
            buildList {
                if (saved) add(ScoredEvidence(RecommendationEvidence.Saved, savedScore))
                addAll(tasteMatches)
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
            }
        return CandidateAssessment(
            scoreBasisPoints = (score / MAX_SCORE * 10_000).roundToInt().coerceIn(0, 10_000),
            scored = scored,
            hasPersonalEvidence = saved || tasteMatches.isNotEmpty() || followed,
        )
    }

    private fun PreparedExhibitionFeatures.tasteMatches(signals: HistorySignals): List<ScoredEvidence> {
        val saved = RecommendationSignalSource.SAVED
        val visited = RecommendationSignalSource.VISITED
        return listOfNotNull(
            bestArtistMatch(signals.savedAnchors, saved)?.scored(SAVED_ARTIST_WEIGHT),
            bestArtistMatch(signals.visitedAnchors, visited)?.scored(VISITED_ARTIST_WEIGHT),
            bestArtTermMatch(signals.savedAnchors, saved)?.scored(SAVED_ART_TERM_WEIGHT),
            bestArtTermMatch(signals.visitedAnchors, visited)?.scored(VISITED_ART_TERM_WEIGHT),
            bestDetectedTermMatch(signals.savedAnchors, saved)?.scored(SAVED_DETECTED_TERM_WEIGHT),
            bestDetectedTermMatch(signals.visitedAnchors, visited)?.scored(VISITED_DETECTED_TERM_WEIGHT),
            bestTextMatch(vector, signals.savedAnchors, saved)?.scored(SAVED_TEXT_WEIGHT),
            bestTextMatch(vector, signals.visitedAnchors, visited)?.scored(VISITED_TEXT_WEIGHT),
        )
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

/** Visitor history resolved against the prepared index once per ranking call. */
private class HistorySignals(
    val savedAnchors: List<PreparedExhibitionFeatures>,
    val visitedAnchors: List<PreparedExhibitionFeatures>,
    val visitedIds: Set<String>,
    val followedIds: Set<String>,
    val followedKeys: Set<String>,
) {
    companion object {
        fun from(
            indexable: List<PreparedExhibitionFeatures>,
            bookmarkedExhibitionIds: Set<String>,
            visits: List<ExhibitionVisit>,
            followedGalleries: List<FollowedGallery>,
        ): HistorySignals {
            val visitedIds = visits.mapTo(mutableSetOf()) { it.exhibitionId }
            return HistorySignals(
                savedAnchors = indexable.filter { it.exhibition.id in bookmarkedExhibitionIds },
                visitedAnchors = indexable.filter { it.exhibition.id in visitedIds },
                visitedIds = visitedIds,
                followedIds = followedGalleries.mapNotNullTo(mutableSetOf()) { it.galleryId },
                followedKeys = followedGalleries.mapTo(mutableSetOf()) { it.galleryKey },
            )
        }
    }
}

private class CandidateAssessment(
    val scoreBasisPoints: Int,
    val scored: List<ScoredEvidence>,
    val hasPersonalEvidence: Boolean,
)

/**
 * A For You candidate with its personal-evidence tier. Candidates explained by the visitor's own saves,
 * visits or follows rank ahead of candidates explained only by editorial, proximity or timing signals,
 * because on the live catalogue a Featured flag alone otherwise outweighs a genuine text match.
 */
private class RankedCandidate(
    val recommendation: ExhibitionRecommendation,
    val hasPersonalEvidence: Boolean,
)

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

/**
 * Drops n-grams that describe a venue rather than a show (repeated across one venue's exhibitions) and
 * n-grams common across the catalogue, so text similarity reflects artistic content. The floor keeps
 * tiny catalogues from losing every feature.
 */
private fun Map<String, Map<Int, Int>>.withoutBoilerplate(indexable: List<Exhibition>): Map<String, Map<Int, Int>> {
    val venueBoilerplateById = mutableMapOf<String, Set<Int>>()
    indexable
        .groupBy { it.galleryIdentity() }
        .values
        .filter { it.size >= 2 }
        .forEach { venueExhibitions ->
            val presence = mutableMapOf<Int, Int>()
            venueExhibitions.forEach { exhibition ->
                getValue(exhibition.id).keys.forEach { feature -> presence[feature] = (presence[feature] ?: 0) + 1 }
            }
            val shared = presence.filterValues { it >= 2 }.keys
            venueExhibitions.forEach { venueBoilerplateById[it.id] = shared }
        }
    val ubiquityLimit = maxOf(UBIQUITY_FLOOR_DOCUMENTS.toDouble(), UBIQUITY_SHARE * size)
    val ubiquitous =
        values
            .flatMap { it.keys }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > ubiquityLimit }
            .keys
    return mapValues { (id, features) ->
        val venueBoilerplate = venueBoilerplateById[id].orEmpty()
        features.filterKeys { it !in venueBoilerplate && it !in ubiquitous }
    }
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
    var unmatchedComplement = 1.0
    for (anchor in anchors) {
        val intersectionSize = sharedIdentifierCount(artistIds, anchor.artistIds)
        if (intersectionSize == 0) continue
        val matchedId = firstSharedIdentifier(artistIds, anchor.artistIds) ?: continue
        val strength = symmetricOverlapStrength(artistIds, anchor.artistIds, intersectionSize)
        unmatchedComplement *= 1.0 - strength
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
        strength = aggregatedStrength(unmatchedComplement),
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
    var unmatchedComplement = 1.0
    for (anchor in anchors) {
        val intersectionSize = sharedIdentifierCount(termIds, anchor.termIds)
        if (intersectionSize == 0) continue
        val matchedId = firstSharedIdentifier(termIds, anchor.termIds) ?: continue
        val strength = symmetricOverlapStrength(termIds, anchor.termIds, intersectionSize)
        unmatchedComplement *= 1.0 - strength
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
        strength = aggregatedStrength(unmatchedComplement),
    )
}

/**
 * A shared term read from both texts, counted by how rare it is: the overlap of the two detected sets
 * (so a broad show earns less, as with reviewed terms) times the rarity of the rarest shared term, which
 * is also the term shown. A term on most of the catalogue never clears the reason threshold.
 */
private fun PreparedExhibitionFeatures.bestDetectedTermMatch(
    anchors: List<PreparedExhibitionFeatures>,
    source: RecommendationSignalSource,
): EvidenceMatch? {
    if (detectedTermIds.isEmpty()) return null
    var bestAnchor: PreparedExhibitionFeatures? = null
    var bestTermId: String? = null
    var bestStrength = 0.0
    var unmatchedComplement = 1.0
    for (anchor in anchors) {
        val intersectionSize = sharedIdentifierCount(detectedTermIds, anchor.detectedTermIds)
        if (intersectionSize == 0) continue
        val rarestId = rarestSharedTerm(anchor) ?: continue
        val strength =
            symmetricOverlapStrength(detectedTermIds, anchor.detectedTermIds, intersectionSize) *
                detectedTermRarity.getValue(rarestId)
        if (strength < DETECTED_TERM_REASON_THRESHOLD) continue
        unmatchedComplement *= 1.0 - strength
        if (
            strength > bestStrength ||
            (
                strength == bestStrength &&
                    isStableMatchEarlier(
                        anchorId = anchor.exhibition.id,
                        matchedId = rarestId,
                        currentAnchorId = bestAnchor?.exhibition?.id,
                        currentMatchedId = bestTermId,
                    )
            )
        ) {
            bestAnchor = anchor
            bestTermId = rarestId
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
                term = detectedTermsById.getValue(termId),
            ),
        strength = aggregatedStrength(unmatchedComplement),
    )
}

private fun PreparedExhibitionFeatures.rarestSharedTerm(anchor: PreparedExhibitionFeatures): String? {
    var rarestId: String? = null
    var rarest = -1.0
    for (id in detectedTermIds) {
        if (id !in anchor.detectedTermIds) continue
        val rarity = detectedTermRarity.getValue(id)
        if (rarity > rarest || (rarity == rarest && rarestId != null && id < rarestId)) {
            rarestId = id
            rarest = rarity
        }
    }
    return rarestId
}

/** Terms the exhibition's own text implies beyond the editor's reviewed ones. */
private fun Exhibition.detectedArtTerms(): List<ArtTerm> {
    val reviewed = artTerms.mapTo(mutableSetOf(), ArtTerm::id)
    return effectiveArtTerms().filterNot { it.id in reviewed }
}

/**
 * How much a shared term says about taste: `ln(N / df) / ln(N)`, one for a term found on a single
 * exhibition and zero for a term found on every one. Counted over reviewed and detected terms alike.
 */
private fun termRarity(
    detectedTerms: Collection<List<ArtTerm>>,
    catalogueSize: Int,
): Map<String, Double> {
    val documentFrequency = mutableMapOf<String, Int>()
    detectedTerms.forEach { terms ->
        terms.forEach { term -> documentFrequency[term.id] = (documentFrequency[term.id] ?: 0) + 1 }
    }
    if (catalogueSize <= 1) return documentFrequency.mapValues { 1.0 }
    val scale = ln(catalogueSize.toDouble())
    return documentFrequency.mapValues { (_, frequency) -> ln(catalogueSize.toDouble() / frequency) / scale }
}

private fun bestTextMatch(
    candidateVector: Map<Int, Double>,
    anchors: List<PreparedExhibitionFeatures>,
    source: RecommendationSignalSource,
): EvidenceMatch? {
    var bestAnchor: PreparedExhibitionFeatures? = null
    var bestStrength = 0.0
    var unmatchedComplement = 1.0
    for (anchor in anchors) {
        val similarity = cosine(candidateVector, anchor.vector).coerceIn(0.0, 1.0)
        if (similarity <= SIMILARITY_REASON_THRESHOLD) continue
        unmatchedComplement *= 1.0 - similarity
        if (
            similarity > bestStrength ||
            (similarity == bestStrength && (bestAnchor == null || anchor.exhibition.id < bestAnchor.exhibition.id))
        ) {
            bestAnchor = anchor
            bestStrength = similarity
        }
    }
    val anchor = bestAnchor ?: return null
    return EvidenceMatch(
        evidence = RecommendationEvidence.TextSimilarity(source, anchor.evidenceAnchor),
        strength = aggregatedStrength(unmatchedComplement),
    )
}

/**
 * Noisy-OR over every matching anchor: each extra match adds less, the result stays within [0, 1], and
 * it is independent of anchor order because anchors are iterated in the index's id order.
 */
private fun aggregatedStrength(unmatchedComplement: Double): Double = (1.0 - unmatchedComplement).coerceIn(0.0, 1.0)

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
        RecommendationEvidence.Saved -> -1
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
        RecommendationEvidence.Saved -> "saved"
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
        RecommendationEvidence.Saved -> "-1"
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
private const val FEATURE_SCHEMA_VERSION = 5
private const val MIN_NGRAM_SIZE = 2
private const val MAX_NGRAM_SIZE = 3

/** Replaces taste matching for a saved route stop; equal to the strongest single taste weight. */
private const val SAVED_STOP_WEIGHT = 0.50
private const val SAVED_ARTIST_WEIGHT = 0.50
private const val VISITED_ARTIST_WEIGHT = 0.35
private const val SAVED_ART_TERM_WEIGHT = 0.35
private const val VISITED_ART_TERM_WEIGHT = 0.25

/**
 * A term read from text is a guess, so even a rare one shared outright is worth about a solid text
 * match (cosine ~0.4), never a reviewed term: it earns a card its reason, not the top of the list.
 */
private const val SAVED_DETECTED_TERM_WEIGHT = 0.08
private const val VISITED_DETECTED_TERM_WEIGHT = 0.06

/** Overlap × rarity a detected-term match needs to be a reason; 회화 on a third of the catalogue never clears it. */
private const val DETECTED_TERM_REASON_THRESHOLD = 0.15
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
private const val SIMILARITY_REASON_THRESHOLD = 0.08

/** N-grams present in more than this share of the catalogue (or more than the floor) are catalogue noise. */
private const val UBIQUITY_SHARE = 0.20
private const val UBIQUITY_FLOOR_DOCUMENTS = 2
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
