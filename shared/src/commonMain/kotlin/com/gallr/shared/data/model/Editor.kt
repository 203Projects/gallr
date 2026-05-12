package com.gallr.shared.data.model

data class Editor(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val titleKo: String,
    val titleEn: String,
    val bioKo: String,
    val bioEn: String,
) {
    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    fun localizedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> titleEn.ifEmpty { titleKo }
        AppLanguage.KO -> titleKo
    }

    fun localizedBio(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> bioEn.ifEmpty { bioKo }
        AppLanguage.KO -> bioKo
    }
}
