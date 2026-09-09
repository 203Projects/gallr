package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.MAX_ART_METADATA_LABEL_LENGTH
import com.gallr.shared.data.model.MAX_EXHIBITION_ARTISTS
import com.gallr.shared.data.model.MAX_EXHIBITION_ART_TERMS
import com.gallr.shared.data.model.MAX_EXHIBITION_ART_TERMS_PER_CATEGORY
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExhibitionArtistDto(
    val id: String = "",
    @SerialName("name_ko") val nameKo: String = "",
    @SerialName("name_en") val nameEn: String = "",
) {
    fun toDomain(): ExhibitionArtist? {
        val normalizedId = id.trim()
        val normalizedNameKo = nameKo.trim()
        val normalizedNameEn = nameEn.trim()
        if (
            normalizedId.isEmpty() ||
            (normalizedNameKo.isEmpty() && normalizedNameEn.isEmpty()) ||
            normalizedNameKo.length > MAX_ART_METADATA_LABEL_LENGTH ||
            normalizedNameEn.length > MAX_ART_METADATA_LABEL_LENGTH
        ) {
            return null
        }
        return ExhibitionArtist(
            id = normalizedId,
            nameKo = normalizedNameKo,
            nameEn = normalizedNameEn,
        )
    }
}

@Serializable
data class ArtTermDto(
    val id: String = "",
    val category: String = "",
    @SerialName("name_ko") val nameKo: String = "",
    @SerialName("name_en") val nameEn: String = "",
) {
    fun toDomain(): ArtTerm? {
        val normalizedId = id.trim()
        val normalizedNameKo = nameKo.trim()
        val normalizedNameEn = nameEn.trim()
        val normalizedCategory = category.trim().lowercase().toArtTermCategory() ?: return null
        if (
            normalizedId.isEmpty() ||
            (normalizedNameKo.isEmpty() && normalizedNameEn.isEmpty()) ||
            normalizedNameKo.length > MAX_ART_METADATA_LABEL_LENGTH ||
            normalizedNameEn.length > MAX_ART_METADATA_LABEL_LENGTH
        ) {
            return null
        }
        return ArtTerm(
            id = normalizedId,
            category = normalizedCategory,
            nameKo = normalizedNameKo,
            nameEn = normalizedNameEn,
        )
    }
}

internal fun List<ExhibitionArtistDto>.toDomainArtists(): List<ExhibitionArtist> =
    asSequence()
        .mapNotNull(ExhibitionArtistDto::toDomain)
        .distinctBy(ExhibitionArtist::id)
        .take(MAX_EXHIBITION_ARTISTS)
        .toList()

internal fun List<ArtTermDto>.toDomainArtTerms(): List<ArtTerm> {
    val categoryCounts = mutableMapOf<ArtTermCategory, Int>()
    return asSequence()
        .mapNotNull(ArtTermDto::toDomain)
        .distinctBy(ArtTerm::id)
        .filter { term ->
            val nextCount = (categoryCounts[term.category] ?: 0) + 1
            if (nextCount > MAX_EXHIBITION_ART_TERMS_PER_CATEGORY) {
                false
            } else {
                categoryCounts[term.category] = nextCount
                true
            }
        }.take(MAX_EXHIBITION_ART_TERMS)
        .toList()
}

private fun String.toArtTermCategory(): ArtTermCategory? =
    when (this) {
        "medium" -> ArtTermCategory.MEDIUM
        "style" -> ArtTermCategory.STYLE
        "theme" -> ArtTermCategory.THEME
        "mood" -> ArtTermCategory.MOOD
        else -> null
    }
