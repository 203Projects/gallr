package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage

/** The Featured tab's For You entry: its label and a one-line preview of what waits inside. */
data class RecommendationsEntryPresentation(
    val title: String,
    val teaser: String?,
)

/**
 * Previews the top pick (its name and how many picks follow) once the list is personal, nudges toward
 * saving on a cold start, and stays a bare label while nothing is ready.
 */
fun recommendationsEntryPresentation(
    state: RecommendationUiState,
    language: AppLanguage,
): RecommendationsEntryPresentation {
    val title = recommendationScreenCopy(language).title
    val ready = state as? RecommendationUiState.Ready
    val shown = ready?.items.orEmpty().take(MAX_RECOMMENDATION_CARDS)
    val top = shown.firstOrNull()
    return when {
        ready == null || top == null -> {
            RecommendationsEntryPresentation(title, teaser = null)
        }

        ready.basis.isEmpty -> {
            RecommendationsEntryPresentation(title, coldStartTeaser(language))
        }

        else -> {
            RecommendationsEntryPresentation(
                title = title,
                teaser = topPickTeaser(top.exhibition.localizedName(language), shown.size - 1, language),
            )
        }
    }
}

private fun topPickTeaser(
    name: String,
    remaining: Int,
    language: AppLanguage,
): String =
    when {
        remaining <= 0 -> name
        language == AppLanguage.KO -> "$name 외 ${remaining}개"
        else -> "$name and $remaining more"
    }

private fun coldStartTeaser(language: AppLanguage): String =
    when (language) {
        AppLanguage.KO -> "전시를 저장하면 취향에 맞춰 추천해요"
        AppLanguage.EN -> "Save exhibitions to tune these picks"
    }
