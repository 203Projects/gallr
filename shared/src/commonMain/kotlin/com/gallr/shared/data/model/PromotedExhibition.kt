package com.gallr.shared.data.model

/** A paid exhibition placement delivered outside the organic catalogue contract. */
data class PromotedExhibition(
    val promotionId: String,
    val exhibitionId: String,
    val nameKo: String,
    val nameEn: String,
    val venueNameKo: String,
    val venueNameEn: String,
    val cityKo: String,
    val cityEn: String,
    val regionKo: String,
    val regionEn: String,
    val openingDate: String,
    val closingDate: String,
    val coverImageUrl: String?,
)
