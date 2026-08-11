package com.gallr.shared.data.network.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExhibitionCountryCodeTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun payload(countryField: String = ""): String = """
        {
            "id": "country-test",
            "name_ko": "전시",
            "venue_name_ko": "갤러리",
            "city_ko": "서울",
            "region_ko": "종로구",
            "opening_date": "2026-08-01",
            "closing_date": "2026-08-31",
            "is_featured": false
            $countryField
        }
    """.trimIndent()

    @Test
    fun `legacy response without country defaults to Korea during rollout`() {
        val dto = json.decodeFromString<ExhibitionDto>(payload())

        assertEquals("KR", dto.countryCode)
        assertEquals("KR", assertNotNull(dto.toDomain()).countryCode)
    }

    @Test
    fun `explicit country survives DTO to domain mapping`() {
        val dto = json.decodeFromString<ExhibitionDto>(payload(",\n\"country_code\": \"JP\""))

        assertEquals("JP", dto.countryCode)
        assertEquals("JP", assertNotNull(dto.toDomain()).countryCode)
    }

    @Test
    fun `invalid country is rejected at the DTO boundary`() {
        val dto = json.decodeFromString<ExhibitionDto>(payload(",\n\"country_code\": \"korea\""))

        assertNull(dto.toDomain())
    }
}
