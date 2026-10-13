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

/**
 * How an exhibition can be visited, as an editor confirmed it (spec 091 FR-022). Tags arrive as `visit:` terms in the
 * reviewed vocabulary; nothing infers them from an exhibition's text, and a term this build does not know is dropped.
 */
@Serializable
enum class VisitTag(
    val termId: String,
) {
    /** No admission fee. */
    FREE_ADMISSION("visit:free-admission"),

    /** Good to visit with children and family. */
    FAMILY("visit:family"),
    ;

    companion object {
        fun fromTermId(termId: String): VisitTag? = entries.firstOrNull { it.termId == termId }
    }
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
