package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Exhibition(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val venueNameKo: String,
    val venueNameEn: String,
    val cityKo: String,
    val cityEn: String,
    val regionKo: String,
    val regionEn: String,
    val openingDate: LocalDate,
    val closingDate: LocalDate,
    val isFeatured: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val descriptionKo: String,
    val descriptionEn: String,
    val addressKo: String,
    val addressEn: String,
    val coverImageUrl: String?,
    val hours: String? = null,
    val contact: String? = null,
    val receptionDate: LocalDate? = null,
    val openingTime: String? = null,
    val eventId: String? = null,
    val editorId: String? = null,
    val creditsKo: String = "",
    val creditsEn: String = "",
    val countryCode: String = "KR",
    val ticketUrl: String? = null,
    val galleryId: String? = null,
    val artists: List<ExhibitionArtist> = emptyList(),
    val artTerms: List<ArtTerm> = emptyList(),
) {
    init {
        require(countryCode.length == 2 && countryCode.all { it in 'A'..'Z' }) {
            "countryCode must be an uppercase ISO alpha-2 code"
        }
        require(artists.size <= MAX_EXHIBITION_ARTISTS) {
            "artists must contain at most $MAX_EXHIBITION_ARTISTS entries"
        }
        require(artists.map(ExhibitionArtist::id).distinct().size == artists.size) {
            "artists must not contain duplicate IDs"
        }
        require(artTerms.size <= MAX_EXHIBITION_ART_TERMS) {
            "artTerms must contain at most $MAX_EXHIBITION_ART_TERMS entries"
        }
        require(artTerms.map(ArtTerm::id).distinct().size == artTerms.size) {
            "artTerms must not contain duplicate IDs"
        }
        require(
            artTerms
                .groupingBy(ArtTerm::category)
                .eachCount()
                .values
                .all { it <= MAX_EXHIBITION_ART_TERMS_PER_CATEGORY },
        ) {
            "artTerms must contain at most $MAX_EXHIBITION_ART_TERMS_PER_CATEGORY entries per category"
        }
    }

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

    fun localizedCity(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> cityEn.ifEmpty { cityKo }
            AppLanguage.KO -> cityKo
        }

    fun localizedRegion(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> regionEn.ifEmpty { regionKo }
            AppLanguage.KO -> regionKo
        }

    fun localizedDescription(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> descriptionEn.ifEmpty { descriptionKo }
            AppLanguage.KO -> descriptionKo
        }

    fun localizedCredits(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> creditsEn.ifEmpty { creditsKo }
            AppLanguage.KO -> creditsKo
        }

    fun localizedDescriptionAndCredits(lang: AppLanguage): String =
        listOf(localizedDescription(lang), localizedCredits(lang))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    fun localizedAddress(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> addressEn.ifEmpty { addressKo }
            AppLanguage.KO -> addressKo
        }

    fun localizedDateRange(lang: AppLanguage): String = localizedExhibitionDateRange(openingDate, closingDate, lang)
}

internal fun localizedExhibitionDateRange(
    openingDate: LocalDate,
    closingDate: LocalDate,
    lang: AppLanguage,
): String =
    when (lang) {
        AppLanguage.KO -> "${openingDate.formatKo()} – ${closingDate.formatKo()}"
        AppLanguage.EN -> formatEnDateRange(openingDate, closingDate)
    }

private val EN_MONTHS =
    arrayOf(
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec",
    )

private fun LocalDate.formatKo(): String =
    "$year.${(month.ordinal + 1).toString().padStart(2, '0')}.${day.toString().padStart(2, '0')}"

private fun formatEnDateRange(
    from: LocalDate,
    to: LocalDate,
): String {
    val fromMonth = EN_MONTHS[from.month.ordinal]
    val toMonth = EN_MONTHS[to.month.ordinal]
    return if (from.year == to.year) {
        "$fromMonth ${from.day} – $toMonth ${to.day}, ${to.year}"
    } else {
        "$fromMonth ${from.day}, ${from.year} – $toMonth ${to.day}, ${to.year}"
    }
}
