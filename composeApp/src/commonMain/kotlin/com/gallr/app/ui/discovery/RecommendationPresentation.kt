package com.gallr.app.ui.discovery

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.recommendation.RecommendationEvidenceAnchor
import com.gallr.shared.recommendation.RecommendationSignalSource

internal data class RecommendationScreenCopy(
    val title: String,
    val deviceOnlyLabel: String,
    val explanation: String,
    val loading: String,
    val emptyTitle: String,
    val emptyBody: String,
    val browseFeatured: String,
    val errorTitle: String,
    val errorBody: String,
    val retry: String,
    val back: String,
)

internal data class RecommendationCardPresentation(
    val exhibition: Exhibition,
    val contextLabel: String,
)

internal fun recommendationScreenCopy(language: AppLanguage): RecommendationScreenCopy =
    when (language) {
        AppLanguage.KO -> {
            RecommendationScreenCopy(
                title = "내 취향 추천",
                deviceOnlyLabel = "이 기기에서 계산됨",
                explanation = "저장, 방문, 팔로우 기록을 기기 밖으로 보내지 않고 추천을 계산합니다.",
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
                deviceOnlyLabel = "COMPUTED ON THIS DEVICE",
                explanation =
                    "Recommendations use your saves, visits, and follows without sending that history off this device.",
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

internal fun recommendationContextLabel(
    evidence: List<RecommendationEvidence>,
    language: AppLanguage,
): String {
    require(evidence.isNotEmpty()) { "personalized recommendations require visible evidence" }
    val title = if (language == AppLanguage.KO) "추천 이유" else "WHY THIS"
    val labels =
        evidence
            .distinct()
            .take(MAX_RECOMMENDATION_REASONS)
            .map { localizedRecommendationEvidence(it, language) }
    return (listOf(title) + labels).joinToString(" · ")
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
    }

internal fun recommendationCardPresentations(
    recommendations: List<ExhibitionRecommendation>,
    language: AppLanguage,
): List<RecommendationCardPresentation> =
    recommendations
        .take(MAX_RECOMMENDATION_CARDS)
        .map { recommendation ->
            RecommendationCardPresentation(
                exhibition = recommendation.exhibition,
                contextLabel = recommendationContextLabel(recommendation.evidence, language),
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
