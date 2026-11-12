package com.gallr.shared.data.model

import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
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

/**
 * One person's diary entry for one exhibition (spec 103): the visit record of spec 053 with the visit date, a private
 * feeling, private tags and a private note. Every diary field has a default, so JSON written by 1.15 on the device
 * (`my_gallr_exhibition_visits_v1`, `my_gallr_account_state_v1`) still decodes. The feeling and tags are kept as wire
 * strings so a value from a later version reads as none instead of failing the whole payload.
 */
@Serializable
data class ExhibitionVisit(
    val clientRecordId: String,
    val exhibitionId: String,
    val snapshot: ExhibitionVisitSnapshot,
    /** When the record was made, not the visit date. */
    val createdAt: Instant,
    /** Null is 날짜 미정: a bulk or strip add, or a record from before spec 103. */
    val visitDate: LocalDate? = null,
    @SerialName("feeling") val feelingWire: String? = null,
    @SerialName("tags") val tagWires: List<String> = emptyList(),
    /** Trimmed, never blank; up to 280 characters typed, up to 562 after a sign-in merge. */
    val note: String? = null,
    /** Set on every sheet save; the later one wins between devices. Null for records from before spec 103. */
    val entryUpdatedAt: Instant? = null,
) {
    init {
        require(clientRecordId.isNotBlank()) { "clientRecordId must not be blank" }
        require(exhibitionId.isNotBlank()) { "exhibitionId must not be blank" }
    }

    val feeling: VisitFeeling? get() = VisitFeeling.fromWire(feelingWire)

    val tags: List<VisitTag> get() = tagWires.mapNotNull(VisitTag::fromWire)
}
