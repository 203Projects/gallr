package com.gallr.app.ui.discovery

import com.gallr.app.viewmodel.RecommendationUiState
import com.gallr.shared.data.model.AppLanguage

/**
 * The Featured tab's For You entry: its label, a one-line preview of what waits inside, and the frames
 * the entry cycles through once the list is personal (DESIGN.md, For You entry).
 */
data class RecommendationsEntryPresentation(
    val title: String,
    val teaser: String?,
    val frames: List<RecommendationsEntryFrame>,
    /** The top pick's cover, washed behind the entry whenever the row is not cycling through frames. */
    val coverImageUrl: String?,
)

/** One pick as the entry shows it while cycling: why it is here, its name, and its cover behind both. */
data class RecommendationsEntryFrame(
    val reason: String,
    val name: String,
    val coverImageUrl: String?,
)

/**
 * Previews the top pick (its name and how many picks follow) once the list is personal, nudges toward
 * saving on a cold start, and stays a bare label while nothing is ready. The frames carry the first
 * three picks with their reasons; the teaser is what stands still when motion is off.
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
            RecommendationsEntryPresentation(title, teaser = null, frames = emptyList(), coverImageUrl = null)
        }

        ready.basis.isEmpty -> {
            RecommendationsEntryPresentation(
                title = title,
                teaser = coldStartTeaser(language),
                frames = emptyList(),
                coverImageUrl = top.exhibition.coverImageUrl,
            )
        }

        else -> {
            RecommendationsEntryPresentation(
                title = title,
                teaser = topPickTeaser(top.exhibition.localizedName(language), shown.size - 1, language),
                frames =
                    shown.take(MAX_ENTRY_FRAMES).map { pick ->
                        RecommendationsEntryFrame(
                            reason = recommendationReasonLabel(pick.evidence, language),
                            name = pick.exhibition.localizedName(language),
                            coverImageUrl = pick.exhibition.coverImageUrl,
                        )
                    },
                coverImageUrl = top.exhibition.coverImageUrl,
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

private const val MAX_ENTRY_FRAMES = 3
