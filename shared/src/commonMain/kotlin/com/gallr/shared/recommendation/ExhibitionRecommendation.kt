package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.map.GeoPoint
import kotlinx.datetime.LocalDate

/** Local user-controlled history surface that supplied one recommendation match. */
enum class RecommendationSignalSource {
    SAVED,
    VISITED,
}

/** Minimal local exhibition reference retained only to explain a taste match. */
data class RecommendationEvidenceAnchor(
    val exhibitionId: String,
    val nameKo: String,
    val nameEn: String,
) {
    init {
        require(exhibitionId.isNotBlank()) { "evidence anchor exhibitionId must not be blank" }
        require(nameKo.isNotBlank() || nameEn.isNotBlank()) {
            "evidence anchor must have at least one localized name"
        }
    }

    companion object {
        fun from(exhibition: Exhibition): RecommendationEvidenceAnchor =
            RecommendationEvidenceAnchor(
                exhibitionId = exhibition.id,
                nameKo = exhibition.nameKo.ifBlank { exhibition.nameEn.ifBlank { exhibition.id } },
                nameEn = exhibition.nameEn,
            )
    }
}

/** Truthful local evidence retained for visible recommendation explanations. */
sealed interface RecommendationEvidence {
    data class ArtistMatch(
        val source: RecommendationSignalSource,
        val anchor: RecommendationEvidenceAnchor,
        val artist: ExhibitionArtist,
    ) : RecommendationEvidence

    data class ArtTermMatch(
        val source: RecommendationSignalSource,
        val anchor: RecommendationEvidenceAnchor,
        val term: ArtTerm,
    ) : RecommendationEvidence

    data class TextSimilarity(
        val source: RecommendationSignalSource,
        val anchor: RecommendationEvidenceAnchor,
    ) : RecommendationEvidence

    data object FollowedGallery : RecommendationEvidence

    data object Nearby : RecommendationEvidence

    data object Featured : RecommendationEvidence

    data object EditorCurated : RecommendationEvidence

    data object ClosingSoon : RecommendationEvidence

    /**
     * The visitor saved this exhibition themselves. Produced only by route candidate ranking so a
     * saved stop is presented as saved rather than with an inferred taste reason; `recommend()` never
     * emits it because saved exhibitions are excluded from the For You list.
     */
    data object Saved : RecommendationEvidence
}

/** Ranked organic exhibition with a quantized deterministic score and visible evidence. */
data class ExhibitionRecommendation(
    val exhibition: Exhibition,
    val scoreBasisPoints: Int,
    val evidence: List<RecommendationEvidence>,
) {
    init {
        require(scoreBasisPoints in 0..10_000) { "scoreBasisPoints must be between 0 and 10000" }
        require(evidence.size in 1..MAX_RECOMMENDATION_EVIDENCE) {
            "evidence must contain between 1 and $MAX_RECOMMENDATION_EVIDENCE entries"
        }
    }
}

/** User-controlled and locally persisted signals used for one recommendation run. */
data class RecommendationContext(
    val bookmarkedExhibitionIds: Set<String> = emptySet(),
    val visits: List<ExhibitionVisit> = emptyList(),
    val followedGalleries: List<FollowedGallery> = emptyList(),
    val origin: GeoPoint? = null,
    val today: LocalDate,
    val limit: Int = 6,
    val maxDistanceKm: Double? = null,
) {
    init {
        require(limit in 0..MAX_RECOMMENDATION_RESULT_LIMIT) {
            "limit must be between 0 and $MAX_RECOMMENDATION_RESULT_LIMIT"
        }
        require(maxDistanceKm == null || maxDistanceKm >= 0.0) {
            "maxDistanceKm must not be negative"
        }
    }
}

/**
 * Signals for ranking every route-eligible exhibition around one origin.
 *
 * Unlike [RecommendationContext], bookmarked exhibitions stay eligible (a saved show is a valid stop)
 * and there is no result limit: the route planner needs the whole eligible pool.
 */
data class RouteRelevanceContext(
    val bookmarkedExhibitionIds: Set<String> = emptySet(),
    val visits: List<ExhibitionVisit> = emptyList(),
    val followedGalleries: List<FollowedGallery> = emptyList(),
    val origin: GeoPoint,
    val maxDistanceKm: Double,
    val today: LocalDate,
) {
    init {
        require(maxDistanceKm > 0.0) { "maxDistanceKm must be positive" }
    }
}

/**
 * One route candidate with its deterministic score, at most two visible reasons, and whether any
 * reason is personal (saved, artist, art term, text or followed gallery) rather than generic.
 */
data class RouteRelevance(
    val exhibition: Exhibition,
    val scoreBasisPoints: Int,
    val evidence: List<RecommendationEvidence>,
    val hasPersonalEvidence: Boolean,
) {
    init {
        require(scoreBasisPoints in 0..10_000) { "scoreBasisPoints must be between 0 and 10000" }
        require(evidence.size <= MAX_RECOMMENDATION_EVIDENCE) {
            "evidence must contain at most $MAX_RECOMMENDATION_EVIDENCE entries"
        }
    }
}

/** Replaceable contract that prepares immutable catalogue-only recommendation state. */
interface ExhibitionRecommender {
    fun prepare(
        catalogue: List<Exhibition>,
        previous: ExhibitionRecommendationIndex? = null,
    ): ExhibitionRecommendationIndex
}

/** Immutable prepared catalogue index safe for repeated and concurrent local reranking. */
interface ExhibitionRecommendationIndex {
    /**
     * At most `context.limit` current or upcoming exhibitions, excluding saved and visited ones, each
     * with one or two reasons, after the diversity pass. Never emits [RecommendationEvidence.Saved].
     */
    fun recommend(context: RecommendationContext): List<ExhibitionRecommendation>

    /**
     * Every catalogue-visible exhibition with valid coordinates within `context.maxDistanceKm` of
     * `context.origin`, except visited ones, ordered by score descending then exhibition id. Saved
     * exhibitions are included with [RecommendationEvidence.Saved] first and no inferred taste
     * evidence. Candidates with no evidence are kept so routes can be filled. Deterministic for equal
     * inputs and never reads promotion state.
     */
    fun rankRouteCandidates(context: RouteRelevanceContext): List<RouteRelevance>
}

private const val MAX_RECOMMENDATION_RESULT_LIMIT = 20
private const val MAX_RECOMMENDATION_EVIDENCE = 2
