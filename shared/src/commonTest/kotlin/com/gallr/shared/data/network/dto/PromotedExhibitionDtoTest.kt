package com.gallr.shared.data.network.dto

import kotlin.test.Test
import kotlin.test.assertEquals

class PromotedExhibitionDtoTest {
    @Test
    fun `maps only an explicitly disclosed paid placement`() {
        val dto = PromotedExhibitionDto(
            promotionId = "promotion-one",
            exhibitionId = "between-seasons",
            nameKo = "계절 사이",
            nameEn = "Between Seasons",
            venueNameKo = "아틀리에 한남",
            venueNameEn = "Atelier Hannam",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "용산구",
            regionEn = "Yongsan-gu",
            openingDate = "2026-08-08",
            closingDate = "2026-09-14",
            coverImageUrl = null,
            disclosure = "paid_placement",
        )
        assertEquals("between-seasons", dto.toDomain().exhibitionId)
        assertEquals("promotion-one", dto.toDomain().promotionId)
    }
}
