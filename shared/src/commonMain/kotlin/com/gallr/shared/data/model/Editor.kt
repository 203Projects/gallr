package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate

data class Editor(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val titleKo: String,
    val titleEn: String,
    val bioKo: String,
    val bioEn: String,
    val curationDescriptionKo: String = bioKo,
    val curationDescriptionEn: String = bioEn,
    val isActive: Boolean,
    val activeFrom: LocalDate,
    val activeTo: LocalDate?,
) {
    val isHouseEditor: Boolean get() = id == HOUSE_EDITOR_ID

    fun localizedName(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> nameEn.ifEmpty { nameKo }
            AppLanguage.KO -> nameKo
        }

    fun localizedTitle(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> titleEn.ifEmpty { titleKo }
            AppLanguage.KO -> titleKo
        }

    fun localizedBio(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> bioEn.ifEmpty { bioKo }
            AppLanguage.KO -> bioKo
        }

    fun localizedCurationDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> curationDescriptionEn.ifEmpty { curationDescriptionKo }
        AppLanguage.KO -> curationDescriptionKo
    }

    /**
     * True when today falls inside [activeFrom, activeTo] (inclusive on both
     * ends, treating null activeTo as "open-ended") AND is_active is set.
     *
     * Used by the selector ViewModel to split editors into "Currently
     * curating" vs "Past editors" sections.
     */
    fun isCurrentlyActive(today: LocalDate): Boolean =
        isActive && activeFrom <= today && (activeTo == null || activeTo >= today)

    companion object {
        const val HOUSE_EDITOR_ID = "gallr-editors"
    }
}
