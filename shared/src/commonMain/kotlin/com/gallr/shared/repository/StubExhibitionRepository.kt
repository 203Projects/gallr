package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate

/**
 * Hardcoded placeholder data for development/demo use before a real backend exists.
 */
class StubExhibitionRepository : ExhibitionRepository {

    private val exhibitions = listOf(
        Exhibition(
            id = "1",
            nameKo = "선의 거장 눈알",
            nameEn = "Zen Master Eyeball",
            venueNameKo = "국제갤러리 K1",
            venueNameEn = "Kukje Gallery K1",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 3, 19),
            closingDate = LocalDate(2026, 5, 10),
            isFeatured = true,
            latitude = 37.5796,
            longitude = 126.9784,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        ),
        Exhibition(
            id = "2",
            nameKo = "코라",
            nameEn = "Chora",
            venueNameKo = "국제갤러리 K3, 한옥",
            venueNameEn = "Kukje Gallery K3, Hanok",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 3, 19),
            closingDate = LocalDate(2026, 5, 10),
            isFeatured = true,
            latitude = 37.5796,
            longitude = 126.9784,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        ),
        Exhibition(
            id = "3",
            nameKo = "봄이 온다",
            nameEn = "Here Comes Spring",
            venueNameKo = "어피스어피스",
            venueNameEn = "A piece a peace",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "강남구",
            regionEn = "Gangnam-gu",
            openingDate = LocalDate(2026, 3, 28),
            closingDate = LocalDate(2026, 4, 19),
            isFeatured = false,
            latitude = 37.5245,
            longitude = 127.0377,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        ),
    )

    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions.filter { it.isFeatured })

    override suspend fun getExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions)
}
