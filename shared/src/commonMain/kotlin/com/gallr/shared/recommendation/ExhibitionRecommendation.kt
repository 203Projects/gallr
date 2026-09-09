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

/** Replaceable contract that prepares immutable catalogue-only recommendation state. */
interface ExhibitionRecommender {
    fun prepare(
        catalogue: List<Exhibition>,
        previous: ExhibitionRecommendationIndex? = null,
    ): ExhibitionRecommendationIndex
}

/** Immutable prepared catalogue index safe for repeated and concurrent local reranking. */
fun interface ExhibitionRecommendationIndex {
    fun recommend(context: RecommendationContext): List<ExhibitionRecommendation>
}

private const val MAX_RECOMMENDATION_RESULT_LIMIT = 20
private const val MAX_RECOMMENDATION_EVIDENCE = 2
