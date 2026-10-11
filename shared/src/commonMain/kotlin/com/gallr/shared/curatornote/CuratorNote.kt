package com.gallr.shared.curatornote

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * An approved curator's note for one exhibition (spec 090): a hook line, two to four insight paragraphs and
 * one viewing tip in Korean, optionally in English, published under the reviewing editor's name. English
 * helpers fall back to Korean when the English text is empty (FR-014).
 */
data class CuratorNote(
    val exhibitionId: String,
    val versionNumber: Int,
    val hookKo: String,
    val hookEn: String,
    val insightsKo: List<String>,
    val insightsEn: List<String>,
    val tipKo: String,
    val tipEn: String,
    val reviewerNameKo: String,
    val reviewerNameEn: String,
    val approvedAt: Instant,
) {
    fun hook(lang: AppLanguage): String = localized(lang, hookKo, hookEn)

    fun insights(lang: AppLanguage): List<String> =
        when (lang) {
            AppLanguage.EN -> insightsEn.ifEmpty { insightsKo }
            AppLanguage.KO -> insightsKo
        }

    /** The one line a card shows under the hook. */
    fun firstInsight(lang: AppLanguage): String = insights(lang).firstOrNull().orEmpty()

    fun tip(lang: AppLanguage): String = localized(lang, tipKo, tipEn)

    fun reviewerName(lang: AppLanguage): String = localized(lang, reviewerNameKo, reviewerNameEn)

    private fun localized(
        lang: AppLanguage,
        korean: String,
        english: String,
    ): String =
        when (lang) {
            AppLanguage.EN -> english.ifBlank { korean }
            AppLanguage.KO -> korean
        }
}

/** A note paired with the catalogue exhibition it is about, as the home carousel shows it. */
data class CuratorEyeCard(
    val note: CuratorNote,
    val exhibition: Exhibition,
)

/** The home carousel shows at most this many notes; older approvals stay on the detail screen only. */
const val CURATOR_EYE_LIMIT = 10

/**
 * The 큐레이터의 시선 carousel: notes whose exhibition is in the visible [catalogue] and has not ended on
 * [today], one per exhibition, newest approval first, at most [limit] (FR-011, FR-017).
 */
fun curatorEyeCards(
    notes: List<CuratorNote>,
    catalogue: List<Exhibition>,
    today: LocalDate,
    limit: Int = CURATOR_EYE_LIMIT,
): List<CuratorEyeCard> {
    val byId = catalogue.associateBy { it.id }
    return notes
        .sortedByDescending { it.approvedAt }
        .distinctBy { it.exhibitionId }
        .mapNotNull { note ->
            val exhibition = byId[note.exhibitionId] ?: return@mapNotNull null
            if (exhibition.closingDate < today) return@mapNotNull null
            CuratorEyeCard(note, exhibition)
        }.take(limit)
}
