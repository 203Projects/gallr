package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExhibitionDto(
    val id: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String = "",
    @SerialName("venue_name_ko") val venueNameKo: String,
    @SerialName("venue_name_en") val venueNameEn: String = "",
    @SerialName("city_ko") val cityKo: String,
    @SerialName("city_en") val cityEn: String = "",
    @SerialName("region_ko") val regionKo: String,
    @SerialName("region_en") val regionEn: String = "",
    @SerialName("opening_date") val openingDate: String,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("is_featured") val isFeatured: Boolean,
    @SerialName("is_editors_pick") val isEditorsPick: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("description_ko") val descriptionKo: String = "",
    @SerialName("description_en") val descriptionEn: String = "",
    @SerialName("address_ko") val addressKo: String = "",
    @SerialName("address_en") val addressEn: String = "",
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
    val hours: String? = null,
    val contact: String? = null,
    @SerialName("reception_date") val receptionDate: String? = null,
    @SerialName("opening_time") val openingTime: String? = null,
    @SerialName("event_id") val eventId: String? = null,
) {
    fun toDomain(): Exhibition? {
        val opening = try { LocalDate.parse(openingDate) } catch (_: Exception) { return null }
        val closing = try { LocalDate.parse(closingDate) } catch (_: Exception) { return null }
        return Exhibition(
            id = id,
            nameKo = nameKo,
            nameEn = nameEn,
            venueNameKo = venueNameKo,
            venueNameEn = venueNameEn,
            cityKo = cityKo,
            cityEn = cityEn,
            regionKo = regionKo,
            regionEn = regionEn,
            openingDate = opening,
            closingDate = closing,
            isFeatured = isFeatured,
            isEditorsPick = isEditorsPick,
            latitude = latitude,
            longitude = longitude,
            descriptionKo = descriptionKo,
            descriptionEn = descriptionEn,
            addressKo = addressKo,
            addressEn = addressEn,
            coverImageUrl = coverImageUrl,
            hours = hours,
            contact = contact,
            receptionDate = receptionDate?.let { raw ->
                try {
                    // Parse as full ISO timestamp and convert to local date in system timezone
                    Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault()).date
                } catch (_: Exception) {
                    // Fallback: try parsing as date-only string (YYYY-MM-DD)
                    try { LocalDate.parse(raw.take(10)) } catch (_: Exception) { null }
                }
            },
            openingTime = openingTime,
            eventId = eventId,
        )
    }
}
