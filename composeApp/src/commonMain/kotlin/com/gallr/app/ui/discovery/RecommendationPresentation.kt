package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationBasis
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RecommendationEvidenceAnchor
import com.gallr.shared.recommendation.RecommendationSignalSource

internal data class RecommendationScreenCopy(
    val title: String,
    val loading: String,
    val emptyTitle: String,
    val emptyBody: String,
    val browseFeatured: String,
    val errorTitle: String,
    val errorBody: String,
    val retry: String,
    val back: String,
)

/** One For You card: its rank in the ranked list and the reason shown as the card's eyebrow. */
internal data class RecommendationCardPresentation(
    val exhibition: Exhibition,
    val rank: Int,
    val reason: String,
)

/** Cards explained by the visitor's own history come first; editorial and timing picks follow. */
internal enum class RecommendationSection {
    PERSONAL,
    EDITORIAL,
    ;

    fun localizedTitle(language: AppLanguage): String =
        when (this) {
            PERSONAL -> if (language == AppLanguage.KO) "내 취향 기반" else "BASED ON YOUR TASTE"
            EDITORIAL -> if (language == AppLanguage.KO) "이번 주 볼 만한 전시" else "WORTH SEEING THIS WEEK"
        }
}

internal data class RecommendationSectionPresentation(
    val section: RecommendationSection,
    val title: String,
    val cards: List<RecommendationCardPresentation>,
)

internal fun recommendationScreenCopy(language: AppLanguage): RecommendationScreenCopy =
    when (language) {
        AppLanguage.KO -> {
            RecommendationScreenCopy(
                title = "내 취향 추천",
                loading = "추천 전시를 계산하고 있습니다.",
                emptyTitle = "추천할 수 있는 전시가 없습니다.",
                emptyBody = "현재 보거나 곧 열리는 전시가 추가되면 다시 확인해 주세요.",
                browseFeatured = "추천 전시 보기",
                errorTitle = "! 추천을 계산하지 못했습니다.",
                errorBody = "기기 안에서 다시 계산해 보세요.",
                retry = "다시 시도",
                back = "뒤로",
            )
        }

        AppLanguage.EN -> {
            RecommendationScreenCopy(
                title = "FOR YOU",
                loading = "Computing recommendations on this device…",
                emptyTitle = "No recommendations right now.",
                emptyBody = "Check again when more current or upcoming exhibitions are available.",
                browseFeatured = "BROWSE FEATURED",
                errorTitle = "! Recommendations couldn’t be computed.",
                errorBody = "Try the on-device calculation again.",
                retry = "Retry",
                back = "Back",
            )
        }
    }

/** Route stops keep the "WHY THIS" prefix because the reason sits among other facts about the stop. */
internal fun recommendationContextLabel(
    evidence: List<RecommendationEvidence>,
    language: AppLanguage,
): String {
    val title = if (language == AppLanguage.KO) "추천 이유" else "WHY THIS"
    return "$title · ${recommendationReasonLabel(evidence, language)}"
}

/** The reasons alone, for a surface whose title already says these are recommendations. */
internal fun recommendationReasonLabel(
    evidence: List<RecommendationEvidence>,
    language: AppLanguage,
): String {
    require(evidence.isNotEmpty()) { "personalized recommendations require visible evidence" }
    return evidence
        .distinct()
        .take(MAX_RECOMMENDATION_REASONS)
        .joinToString(" · ") { localizedRecommendationEvidence(it, language) }
}

/** One line that says what the list is built from and that it never leaves the device (spec 073). */
internal fun recommendationBasisLabel(
    basis: RecommendationBasis,
    language: AppLanguage,
): String =
    when {
        basis.isEmpty && language == AppLanguage.KO -> {
            "전시를 저장하거나 방문을 기록하면 취향에 맞춰 추천해 드려요. 이 기기에서만 계산합니다."
        }

        basis.isEmpty -> {
            "Save an exhibition or log a visit and recommendations follow your taste. Computed on this device."
        }

        language == AppLanguage.KO -> {
            "저장 ${basis.savedCount} · 방문 ${basis.visitedCount} · 팔로우 ${basis.followedCount} 기반 · 이 기기에서만 계산"
        }

        else -> {
            "BASED ON ${basis.savedCount} SAVED · ${basis.visitedCount} VISITED · ${basis.followedCount} FOLLOWED · " +
                "COMPUTED ON THIS DEVICE"
        }
    }

internal fun recommendationSections(
    recommendations: List<ExhibitionRecommendation>,
    language: AppLanguage,
): List<RecommendationSectionPresentation> {
    val ranked = recommendations.take(MAX_RECOMMENDATION_CARDS)
    val cards = recommendationCardPresentations(ranked, language)
    val (personal, editorial) =
        cards.partition { card -> ranked[card.rank].evidence.any(RecommendationEvidence::isPersonal) }
    // The editorial section already says its cards are featured picks; the eyebrow keeps only what is
    // specific to the card (editor curation, closing soon, nearby) and disappears when nothing is left.
    val editorialWithoutFeatured =
        editorial.map { card ->
            val specific = ranked[card.rank].evidence.filterNot { it == RecommendationEvidence.Featured }
            card.copy(reason = if (specific.isEmpty()) "" else recommendationReasonLabel(specific, language))
        }
    val personalSection =
        personal.takeIf { it.isNotEmpty() }?.let { section(RecommendationSection.PERSONAL, it, language) }
    val editorialSection =
        editorialWithoutFeatured
            .takeIf { it.isNotEmpty() }
            ?.let { section(RecommendationSection.EDITORIAL, it, language) }
    return listOfNotNull(personalSection, editorialSection)
}

private fun section(
    section: RecommendationSection,
    cards: List<RecommendationCardPresentation>,
    language: AppLanguage,
) = RecommendationSectionPresentation(section, section.localizedTitle(language), cards)

private fun RecommendationEvidence.isPersonal(): Boolean =
    when (this) {
        is RecommendationEvidence.ArtistMatch,
        is RecommendationEvidence.ArtTermMatch,
        is RecommendationEvidence.TextSimilarity,
        RecommendationEvidence.FollowedGallery,
        RecommendationEvidence.Saved,
        -> true

        RecommendationEvidence.Nearby,
        RecommendationEvidence.Featured,
        RecommendationEvidence.EditorCurated,
        RecommendationEvidence.ClosingSoon,
        -> false
    }

internal fun localizedRecommendationEvidence(
    evidence: RecommendationEvidence,
    language: AppLanguage,
): String =
    when (evidence) {
        is RecommendationEvidence.ArtistMatch -> {
            val anchor = evidence.anchor.localizedName(language)
            val artist = evidence.artist.localizedName(language).displayEvidenceValue(language)
            when (language) {
                AppLanguage.KO -> {
                    val action = if (evidence.source == RecommendationSignalSource.SAVED) "저장한" else "방문한"
                    "$action “$anchor”와 같은 작가: $artist"
                }

                AppLanguage.EN -> {
                    "${evidence.source.englishAnchorPrefix()} “$anchor” · SAME ARTIST: $artist"
                }
            }
        }

        is RecommendationEvidence.ArtTermMatch -> {
            val anchor = evidence.anchor.localizedName(language)
            val term = evidence.term.localizedName(language).displayEvidenceValue(language)
            val category = evidence.term.category.localizedEvidenceCategory(language)
            when (language) {
                AppLanguage.KO -> {
                    val action = if (evidence.source == RecommendationSignalSource.SAVED) "저장한" else "방문한"
                    "$action “$anchor”와 공통 $category: $term"
                }

                AppLanguage.EN -> {
                    "${evidence.source.englishAnchorPrefix()} “$anchor” · SHARED $category: $term"
                }
            }
        }

        is RecommendationEvidence.TextSimilarity -> {
            val anchor = evidence.anchor.localizedName(language)
            when (language) {
                AppLanguage.KO -> {
                    val action = if (evidence.source == RecommendationSignalSource.SAVED) "저장한" else "방문한"
                    "$action “$anchor”와 비슷한 전시"
                }

                AppLanguage.EN -> {
                    "${evidence.source.englishAnchorPrefix()} “$anchor” · SIMILAR EXHIBITION"
                }
            }
        }

        RecommendationEvidence.FollowedGallery -> {
            if (language == AppLanguage.KO) "팔로우한 갤러리" else "FROM A GALLERY YOU FOLLOW"
        }

        RecommendationEvidence.Nearby -> {
            if (language == AppLanguage.KO) "가까운 전시" else "NEARBY"
        }

        RecommendationEvidence.Featured -> {
            if (language == AppLanguage.KO) "추천 전시" else "FEATURED"
        }

        RecommendationEvidence.EditorCurated -> {
            if (language == AppLanguage.KO) "에디터 큐레이션" else "EDITOR CURATED"
        }

        RecommendationEvidence.ClosingSoon -> {
            if (language == AppLanguage.KO) "곧 종료" else "CLOSING SOON"
        }

        RecommendationEvidence.Saved -> {
            if (language == AppLanguage.KO) "저장한 전시" else "SAVED"
        }
    }

internal fun recommendationCardPresentations(
    recommendations: List<ExhibitionRecommendation>,
    language: AppLanguage,
): List<RecommendationCardPresentation> =
    recommendations
        .take(MAX_RECOMMENDATION_CARDS)
        .mapIndexed { rank, recommendation ->
            RecommendationCardPresentation(
                exhibition = recommendation.exhibition,
                rank = rank,
                reason = recommendationReasonLabel(recommendation.evidence, language),
            )
        }

private fun RecommendationSignalSource.englishAnchorPrefix(): String =
    if (this == RecommendationSignalSource.SAVED) "BECAUSE YOU SAVED" else "BECAUSE YOU VISITED"

private fun RecommendationEvidenceAnchor.localizedName(language: AppLanguage): String =
    when (language) {
        AppLanguage.KO -> nameKo.ifBlank { nameEn }
        AppLanguage.EN -> nameEn.ifBlank { nameKo }
    }

private fun ArtTermCategory.localizedEvidenceCategory(language: AppLanguage): String =
    when (this) {
        ArtTermCategory.MEDIUM -> if (language == AppLanguage.KO) "매체" else "MEDIUM"
        ArtTermCategory.STYLE -> if (language == AppLanguage.KO) "스타일" else "STYLE"
        ArtTermCategory.THEME -> if (language == AppLanguage.KO) "주제" else "THEME"
        ArtTermCategory.MOOD -> if (language == AppLanguage.KO) "분위기" else "MOOD"
    }

private fun String.displayEvidenceValue(language: AppLanguage): String =
    if (language == AppLanguage.EN) uppercase() else this

private const val MAX_RECOMMENDATION_CARDS = 6
private const val MAX_RECOMMENDATION_REASONS = 2
