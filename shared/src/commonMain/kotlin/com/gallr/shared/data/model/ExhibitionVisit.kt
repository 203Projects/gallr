package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ExhibitionVisitSnapshot(
    val nameKo: String,
    val nameEn: String,
    val venueNameKo: String,
    val venueNameEn: String,
    val openingDate: LocalDate,
    val closingDate: LocalDate,
    val coverImageUrl: String?,
    val galleryId: String? = null,
) {
    fun localizedName(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> nameEn.ifEmpty { nameKo }
            AppLanguage.KO -> nameKo
        }

    fun localizedVenueName(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> venueNameEn.ifEmpty { venueNameKo }
            AppLanguage.KO -> venueNameKo
        }

    fun localizedDateRange(lang: AppLanguage): String = localizedExhibitionDateRange(openingDate, closingDate, lang)

    companion object {
        fun from(exhibition: Exhibition): ExhibitionVisitSnapshot =
            ExhibitionVisitSnapshot(
                nameKo = exhibition.nameKo,
                nameEn = exhibition.nameEn,
                venueNameKo = exhibition.venueNameKo,
                venueNameEn = exhibition.venueNameEn,
                openingDate = exhibition.openingDate,
                closingDate = exhibition.closingDate,
                coverImageUrl = exhibition.coverImageUrl,
                galleryId = exhibition.galleryId,
            )
    }
}

@Serializable
data class ExhibitionVisit(
    val clientRecordId: String,
    val exhibitionId: String,
    val snapshot: ExhibitionVisitSnapshot,
    val createdAt: Instant,
) {
    init {
        require(clientRecordId.isNotBlank()) { "clientRecordId must not be blank" }
        require(exhibitionId.isNotBlank()) { "exhibitionId must not be blank" }
    }
}
