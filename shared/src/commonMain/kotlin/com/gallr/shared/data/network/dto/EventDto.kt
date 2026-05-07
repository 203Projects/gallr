package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Event
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
    val id: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("description_ko") val descriptionKo: String = "",
    @SerialName("description_en") val descriptionEn: String = "",
    @SerialName("location_label_ko") val locationLabelKo: String,
    @SerialName("location_label_en") val locationLabelEn: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("brand_color") val brandColor: String,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
) {
    fun toDomain(): Event? {
        val start = try { LocalDate.parse(startDate) } catch (_: Exception) { return null }
        val end = try { LocalDate.parse(endDate) } catch (_: Exception) { return null }
        return Event(
            id = id,
            nameKo = nameKo,
            nameEn = nameEn,
            descriptionKo = descriptionKo,
            descriptionEn = descriptionEn,
            locationLabelKo = locationLabelKo,
            locationLabelEn = locationLabelEn,
            startDate = start,
            endDate = end,
            brandColor = brandColor,
            ticketUrl = ticketUrl,
            isActive = isActive,
            coverImageUrl = coverImageUrl,
        )
    }
}
