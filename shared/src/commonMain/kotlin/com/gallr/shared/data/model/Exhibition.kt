package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate

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
    val isEditorsPick: Boolean,
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
) {
    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    fun localizedVenueName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> venueNameEn.ifEmpty { venueNameKo }
        AppLanguage.KO -> venueNameKo
    }

    fun localizedCity(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> cityEn.ifEmpty { cityKo }
        AppLanguage.KO -> cityKo
    }

    fun localizedRegion(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> regionEn.ifEmpty { regionKo }
        AppLanguage.KO -> regionKo
    }

    fun localizedDescription(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> descriptionEn.ifEmpty { descriptionKo }
        AppLanguage.KO -> descriptionKo
    }

    fun localizedAddress(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> addressEn.ifEmpty { addressKo }
        AppLanguage.KO -> addressKo
    }

    fun localizedDateRange(lang: AppLanguage): String = when (lang) {
        AppLanguage.KO -> "${openingDate.formatKo()} – ${closingDate.formatKo()}"
        AppLanguage.EN -> formatEnDateRange(openingDate, closingDate)
    }
}

private val EN_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun LocalDate.formatKo(): String =
    "$year.${monthNumber.toString().padStart(2, '0')}.${dayOfMonth.toString().padStart(2, '0')}"

private fun formatEnDateRange(from: LocalDate, to: LocalDate): String {
    val fromMonth = EN_MONTHS[from.monthNumber - 1]
    val toMonth = EN_MONTHS[to.monthNumber - 1]
    return if (from.year == to.year) {
        "$fromMonth ${from.dayOfMonth} – $toMonth ${to.dayOfMonth}, ${to.year}"
    } else {
        "$fromMonth ${from.dayOfMonth}, ${from.year} – $toMonth ${to.dayOfMonth}, ${to.year}"
    }
}
