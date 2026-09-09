package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTermCategory
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExhibitionDtoTest {
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    private val bilingualJson =
        """
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
            "latitude": 37.5796,
            "longitude": 126.9784,
            "description_ko": "개인전",
            "description_en": "A solo exhibition",
            "credits_ko": "자료 제공: 국제갤러리",
            "credits_en": "Courtesy of Kukje Gallery",
            "hours": "화–토 10:00–18:00\n일·월 휴관",
            "ticket_url": "https://tickets.example.test/zen-master-eyeball",
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
        assertEquals("자료 제공: 국제갤러리", dto.creditsKo)
        assertEquals("Courtesy of Kukje Gallery", dto.creditsEn)
    }

    @Test
    fun `ExhibitionDto maps stable gallery identity when the canonical catalogue provides it`() {
        val galleryId = "82100000-0000-0000-0000-000000000001"
        val dto =
            testJson.decodeFromString<ExhibitionDto>(
                bilingualJson.replace(
                    "\"updated_at\"",
                    "\"gallery_id\": \"$galleryId\", \"updated_at\"",
                ),
            )

        assertEquals(galleryId, dto.galleryId)
        assertEquals(galleryId, assertNotNull(dto.toDomain()).galleryId)
    }

    @Test
    fun `legacy catalogue rows remain valid without gallery identity`() {
        val dto = testJson.decodeFromString<ExhibitionDto>(bilingualJson)

        assertNull(dto.galleryId)
        assertNull(assertNotNull(dto.toDomain()).galleryId)
        assertEquals(emptyList(), assertNotNull(dto.toDomain()).artists)
        assertEquals(emptyList(), assertNotNull(dto.toDomain()).artTerms)
    }

    @Test
    fun `canonical catalogue maps bounded artist and art term metadata`() {
        val canonicalJson =
            bilingualJson.replace(
                "\"updated_at\"",
                """
                "artists": [
                  {"id":"artist-kimsooja","name_ko":"김수자","name_en":"Kimsooja"},
                  {"id":"artist-lee","name_ko":"이 작가","name_en":""}
                ],
                "art_terms": [
                  {"id":"style:minimalist","category":"style","name_ko":"미니멀리즘","name_en":"Minimalist"},
                  {"id":"mood:quiet","category":"mood","name_ko":"고요함","name_en":"Quiet / meditative"}
                ],
                "updated_at"
                """.trimIndent(),
            )

        val exhibition = assertNotNull(testJson.decodeFromString<ExhibitionDto>(canonicalJson).toDomain())

        assertEquals(listOf("artist-kimsooja", "artist-lee"), exhibition.artists.map { it.id })
        assertEquals("이 작가", exhibition.artists.last().localizedName(AppLanguage.EN))
        assertEquals(listOf(ArtTermCategory.STYLE, ArtTermCategory.MOOD), exhibition.artTerms.map { it.category })
        assertEquals("Quiet / meditative", exhibition.artTerms.last().localizedName(AppLanguage.EN))
    }

    @Test
    fun `malformed optional art metadata entries are dropped without losing exhibition`() {
        val canonicalJson =
            bilingualJson.replace(
                "\"updated_at\"",
                """
                "artists": [
                  {"id":"","name_ko":"이름 없음","name_en":""},
                  {"id":"artist-valid","name_ko":"유효 작가","name_en":"Valid Artist"},
                  {"id":"artist-valid","name_ko":"중복","name_en":"Duplicate"},
                  {"id":"artist-no-label","name_ko":"","name_en":""}
                ],
                "art_terms": [
                  {"id":"style:valid","category":"style","name_ko":"유효","name_en":"Valid"},
                  {"id":"style:unknown","category":"unsupported","name_ko":"알 수 없음","name_en":"Unknown"},
                  {"id":"","category":"mood","name_ko":"빈 식별자","name_en":""},
                  {"id":"style:valid","category":"style","name_ko":"중복","name_en":"Duplicate"}
                ],
                "updated_at"
                """.trimIndent(),
            )

        val exhibition = assertNotNull(testJson.decodeFromString<ExhibitionDto>(canonicalJson).toDomain())

        assertEquals(listOf("artist-valid"), exhibition.artists.map { it.id })
        assertEquals(listOf("style:valid"), exhibition.artTerms.map { it.id })
    }

    @Test
    fun `ExhibitionDto defaults English fields to empty string when missing`() {
        val koOnlyJson =
            """
            {
                "id": "abc123",
                "name_ko": "전시회",
                "venue_name_ko": "갤러리",
                "city_ko": "서울",
                "region_ko": "강남구",
                "opening_date": "2026-01-01",
                "closing_date": "2026-02-01",
                "is_featured": false
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
        assertEquals("", dto.creditsKo)
        assertEquals("", dto.creditsEn)
    }

    @Test
    fun `ExhibitionDto ignores unknown fields`() {
        val jsonWithUnknown =
            bilingualJson.replace(
                "\"updated_at\"",
                "\"artist_name\": \"Kim\", \"updated_at\"",
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
        assertEquals(37.5796, exhibition.latitude)
        assertEquals("화–토 10:00–18:00\n일·월 휴관", exhibition.hours)
        assertEquals("https://tickets.example.test/zen-master-eyeball", exhibition.ticketUrl)
        assertEquals("Courtesy of Kukje Gallery", exhibition.localizedCredits(AppLanguage.EN))
        assertEquals("자료 제공: 국제갤러리", exhibition.localizedCredits(AppLanguage.KO))
        assertEquals(
            "A solo exhibition\n\nCourtesy of Kukje Gallery",
            exhibition.localizedDescriptionAndCredits(AppLanguage.EN),
        )
        assertEquals(
            "개인전\n\n자료 제공: 국제갤러리",
            exhibition.localizedDescriptionAndCredits(AppLanguage.KO),
        )
        assertNull(exhibition.coverImageUrl)
    }

    @Test
    fun `ExhibitionDto defaults ticket URL to null when missing`() {
        val dto =
            testJson.decodeFromString<ExhibitionDto>(
                bilingualJson.replace(
                    "\"ticket_url\": \"https://tickets.example.test/zen-master-eyeball\",\n",
                    "",
                ),
            )

        assertNull(dto.ticketUrl)
        assertNull(assertNotNull(dto.toDomain()).ticketUrl)
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
        val koOnlyJson =
            """
            {
                "id": "abc123",
                "name_ko": "전시회",
                "venue_name_ko": "갤러리",
                "city_ko": "서울",
                "region_ko": "강남구",
                "opening_date": "2026-01-01",
                "closing_date": "2026-02-01",
                "is_featured": false
            }
            """.trimIndent()
        val exhibition = assertNotNull(testJson.decodeFromString<ExhibitionDto>(koOnlyJson).toDomain())
        assertEquals("전시회", exhibition.localizedName(AppLanguage.EN))
    }

    @Test
    fun `ExhibitionDto deserializes opening_time when present`() {
        val jsonWithTime =
            bilingualJson.replace(
                "\"updated_at\"",
                "\"opening_time\": \"5 PM\", \"updated_at\"",
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

    @Test
    fun `ExhibitionDto decodes the optional canonical content checksum`() {
        val checksum = "a".repeat(64)
        val canonicalJson =
            bilingualJson.replace(
                "\"updated_at\"",
                "\"content_checksum_sha256\": \"$checksum\", \"updated_at\"",
            )

        val canonical = testJson.decodeFromString<ExhibitionDto>(canonicalJson)
        val legacy = testJson.decodeFromString<ExhibitionDto>(bilingualJson)

        assertEquals(checksum, canonical.contentChecksumSha256)
        assertNull(legacy.contentChecksumSha256)
    }
}
