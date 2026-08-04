package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.PromotedExhibition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PromotedExhibitionDto(
    @SerialName("promotion_id") val promotionId: String,
    @SerialName("exhibition_id") val exhibitionId: String,
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
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
    val disclosure: String,
) {
    fun toDomain(): PromotedExhibition {
        require(disclosure == "paid_placement") { "Promotion disclosure is invalid." }
        return PromotedExhibition(
            promotionId = promotionId,
            exhibitionId = exhibitionId,
            nameKo = nameKo,
            nameEn = nameEn,
            venueNameKo = venueNameKo,
            venueNameEn = venueNameEn,
            cityKo = cityKo,
            cityEn = cityEn,
            regionKo = regionKo,
            regionEn = regionEn,
            openingDate = openingDate,
            closingDate = closingDate,
            coverImageUrl = coverImageUrl,
        )
    }
}

@Serializable
internal data class PromotionResponseDto(val placement: PromotedExhibitionDto)

@Serializable
internal data class PromotionRequestDto(
    @SerialName("installation_key") val installationKey: String,
    @SerialName("city_ko") val cityKo: String,
    @SerialName("region_ko") val regionKo: String,
)
