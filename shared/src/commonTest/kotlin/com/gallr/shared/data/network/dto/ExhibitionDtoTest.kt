package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.AppLanguage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExhibitionDtoTest {

    private val testJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val bilingualJson = """
        {
            "id": "a3f2b1c9d4e7f8a2",
            "name_ko": "선의 거장 눈알",
            "name_en": "Zen Master Eyeball",
            "venue_name_ko": "국제갤러리 K1",
            "venue_name_en": "Kukje Gallery K1",
            "city_ko": "서울",
            "city_en": "Seoul",
            "region_ko": "종로구",
            "region_en": "Jongno-gu",
            "opening_date": "2026-03-19",
            "closing_date": "2026-05-10",
            "is_featured": true,
            "is_editors_pick": false,
            "latitude": 37.5796,
            "longitude": 126.9784,
            "description_ko": "개인전",
            "description_en": "A solo exhibition",
            "cover_image_url": null,
            "updated_at": "2026-03-20T10:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `ExhibitionDto deserializes bilingual fields from Supabase JSON`() {
        val dto = testJson.decodeFromString<ExhibitionDto>(bilingualJson)
        assertEquals("선의 거장 눈알", dto.nameKo)
        assertEquals("Zen Master Eyeball", dto.nameEn)
        assertEquals("국제갤러리 K1", dto.venueNameKo)
        assertEquals("Kukje Gallery K1", dto.venueNameEn)
        assertEquals("서울", dto.cityKo)
        assertEquals("Seoul", dto.cityEn)
        assertEquals("종로구", dto.regionKo)
        assertEquals("Jongno-gu", dto.regionEn)
        assertEquals("개인전", dto.descriptionKo)
        assertEquals("A solo exhibition", dto.descriptionEn)
    }

    @Test
    fun `ExhibitionDto defaults English fields to empty string when missing`() {
        val koOnlyJson = """
            {
                "id": "abc123",
                "name_ko": "전시회",
                "venue_name_ko": "갤러리",
                "city_ko": "서울",
                "region_ko": "강남구",
                "opening_date": "2026-01-01",
                "closing_date": "2026-02-01",
                "is_featured": false,
                "is_editors_pick": false
            }
        """.trimIndent()
        val dto = testJson.decodeFromString<ExhibitionDto>(koOnlyJson)
        assertEquals("전시회", dto.nameKo)
        assertEquals("", dto.nameEn)
        assertEquals("", dto.venueNameEn)
        assertEquals("", dto.cityEn)
        assertEquals("", dto.regionEn)
        assertEquals("", dto.descriptionKo)
        assertEquals("", dto.descriptionEn)
    }

    @Test
    fun `ExhibitionDto ignores unknown fields`() {
        val jsonWithUnknown = bilingualJson.replace(
            "\"updated_at\"",
            "\"artist_name\": \"Kim\", \"updated_at\""
        )
        val dto = testJson.decodeFromString<ExhibitionDto>(jsonWithUnknown)
        assertEquals("a3f2b1c9d4e7f8a2", dto.id)
    }

    @Test
    fun `ExhibitionDto toDomain returns null for malformed dates`() {
        val badDateJson = bilingualJson.replace("2026-03-19", "not-a-date")
        val dto = testJson.decodeFromString<ExhibitionDto>(badDateJson)
        assertNull(dto.toDomain())
    }

    @Test
    fun `ExhibitionDto toDomain maps bilingual fields correctly`() {
        val dto = testJson.decodeFromString<ExhibitionDto>(bilingualJson)
        val exhibition = assertNotNull(dto.toDomain())
        assertEquals("선의 거장 눈알", exhibition.nameKo)
        assertEquals("Zen Master Eyeball", exhibition.nameEn)
        assertEquals("국제갤러리 K1", exhibition.venueNameKo)
        assertEquals("Kukje Gallery K1", exhibition.venueNameEn)
        assertEquals("서울", exhibition.cityKo)
        assertEquals("Seoul", exhibition.cityEn)
        assertEquals(2026, exhibition.openingDate.year)
        assertEquals(true, exhibition.isFeatured)
        assertEquals(false, exhibition.isEditorsPick)
        assertEquals(37.5796, exhibition.latitude)
        assertNull(exhibition.coverImageUrl)
    }

    @Test
    fun `Exhibition localizedName returns English with Korean fallback`() {
        val dto = testJson.decodeFromString<ExhibitionDto>(bilingualJson)
        val exhibition = assertNotNull(dto.toDomain())

        assertEquals("Zen Master Eyeball", exhibition.localizedName(AppLanguage.EN))
        assertEquals("선의 거장 눈알", exhibition.localizedName(AppLanguage.KO))
    }

    @Test
    fun `Exhibition localizedName falls back to Korean when English is empty`() {
        val koOnlyJson = """
            {
                "id": "abc123",
                "name_ko": "전시회",
                "venue_name_ko": "갤러리",
                "city_ko": "서울",
                "region_ko": "강남구",
                "opening_date": "2026-01-01",
                "closing_date": "2026-02-01",
                "is_featured": false,
                "is_editors_pick": false
            }
        """.trimIndent()
        val exhibition = assertNotNull(testJson.decodeFromString<ExhibitionDto>(koOnlyJson).toDomain())
        assertEquals("전시회", exhibition.localizedName(AppLanguage.EN))
    }

    @Test
    fun `ExhibitionDto deserializes opening_time when present`() {
        val jsonWithTime = bilingualJson.replace(
            "\"updated_at\"",
            "\"opening_time\": \"5 PM\", \"updated_at\""
        )
        val dto = testJson.decodeFromString<ExhibitionDto>(jsonWithTime)
        assertEquals("5 PM", dto.openingTime)
        val exhibition = assertNotNull(dto.toDomain())
        assertEquals("5 PM", exhibition.openingTime)
    }

    @Test
    fun `ExhibitionDto defaults openingTime to null when missing`() {
        val dto = testJson.decodeFromString<ExhibitionDto>(bilingualJson)
        assertNull(dto.openingTime)
        val exhibition = assertNotNull(dto.toDomain())
        assertNull(exhibition.openingTime)
    }
}
