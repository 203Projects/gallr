package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate

data class Event(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val descriptionKo: String,
    val descriptionEn: String,
    val locationLabelKo: String,
    val locationLabelEn: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val brandColor: String,
    val ticketUrl: String?,
    val isActive: Boolean,
    val coverImageUrl: String? = null,
    val shortLabel: String? = null,
) {
    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    /**
     * Compact identifier for the exhibition-card corner ribbon. Uses the
     * admin-authored [shortLabel] when present; otherwise truncates the
     * localized name to [maxChars] (default 12) with an ellipsis.
     */
    fun ribbonLabel(lang: AppLanguage, maxChars: Int = 12): String {
        val explicit = shortLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        val name = localizedName(lang)
        return if (name.length > maxChars) name.take(maxChars) + "…" else name
    }

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> descriptionEn.ifEmpty { descriptionKo }
        AppLanguage.KO -> descriptionKo
    }

    fun localizedLocationLabel(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> locationLabelEn.ifEmpty { locationLabelKo }
        AppLanguage.KO -> locationLabelKo
    }

    fun isActiveOn(today: LocalDate): Boolean =
        isActive && today >= startDate && today <= endDate
}
