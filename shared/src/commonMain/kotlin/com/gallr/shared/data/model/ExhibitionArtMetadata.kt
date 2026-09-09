package com.gallr.shared.data.model

import kotlinx.serialization.Serializable

/** Stable public artist identity attached to one published exhibition version. */
@Serializable
data class ExhibitionArtist(
    val id: String,
    val nameKo: String,
    val nameEn: String,
) {
    init {
        require(id.isNotBlank()) { "artist id must not be blank" }
        require(nameKo.isNotBlank() || nameEn.isNotBlank()) {
            "artist must have at least one localized name"
        }
    }

    fun localizedName(language: AppLanguage): String =
        when (language) {
            AppLanguage.KO -> nameKo.ifBlank { nameEn }
            AppLanguage.EN -> nameEn.ifBlank { nameKo }
        }
}

/** Reviewed classification of one public art term. */
@Serializable
enum class ArtTermCategory {
    MEDIUM,
    STYLE,
    THEME,
    MOOD,
}

/** Stable controlled-vocabulary term attached to one published exhibition version. */
@Serializable
data class ArtTerm(
    val id: String,
    val category: ArtTermCategory,
    val nameKo: String,
    val nameEn: String,
) {
    init {
        require(id.isNotBlank()) { "art term id must not be blank" }
        require(nameKo.isNotBlank() || nameEn.isNotBlank()) {
            "art term must have at least one localized name"
        }
    }

    fun localizedName(language: AppLanguage): String =
        when (language) {
            AppLanguage.KO -> nameKo.ifBlank { nameEn }
            AppLanguage.EN -> nameEn.ifBlank { nameKo }
        }
}

internal const val MAX_EXHIBITION_ARTISTS = 32
internal const val MAX_EXHIBITION_ART_TERMS = 16
internal const val MAX_EXHIBITION_ART_TERMS_PER_CATEGORY = 6
internal const val MAX_ART_METADATA_LABEL_LENGTH = 200
