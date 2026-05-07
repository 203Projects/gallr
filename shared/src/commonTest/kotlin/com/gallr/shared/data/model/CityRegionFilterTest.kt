package com.gallr.shared.data.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for city sort-by-count and region grouping logic.
 * These mirror the computations in TabsViewModel.distinctCities and distinctRegions.
 */
class CityRegionFilterTest {

    private val today = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    private val yesterday = today.plus(-1, DateTimeUnit.DAY)
    private val inTenDays = today.plus(10, DateTimeUnit.DAY)
    private val tenDaysAgo = today.plus(-10, DateTimeUnit.DAY)

    private fun exhibition(
        id: String = "x",
        cityKo: String = "서울",
        cityEn: String = "Seoul",
        regionKo: String = "종로구",
        regionEn: String = "Jongno-gu",
        openingDate: kotlinx.datetime.LocalDate = yesterday,
        closingDate: kotlinx.datetime.LocalDate = inTenDays,
    ) = Exhibition(
        id = id,
        nameKo = "Test",
        nameEn = "Test",
        venueNameKo = "Venue",
        venueNameEn = "Venue",
        cityKo = cityKo,
        cityEn = cityEn,
        regionKo = regionKo,
        regionEn = regionEn,
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        isEditorsPick = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    // ── Helper: mirrors TabsViewModel.distinctCities logic ──────────────

    private fun computeDistinctCities(exhibitions: List<Exhibition>): List<CityWithCount> {
        return exhibitions
            .filter { it.closingDate >= today }
            .groupBy { it.cityKo to it.cityEn }
            .map { (city, exhs) -> CityWithCount(city.first, city.second, exhs.size) }
            .sortedByDescending { it.count }
    }

    // ── Helper: mirrors TabsViewModel.distinctRegions logic ─────────────

    private fun computeDistinctRegions(
        exhibitions: List<Exhibition>,
        selectedCity: String?,
    ): List<RegionWithCount> {
        if (selectedCity == null) return emptyList()
        return exhibitions
            .filter { it.closingDate >= today && it.cityKo == selectedCity }
            .groupBy { it.regionKo to it.regionEn }
            .map { (region, exhs) -> RegionWithCount(region.first, region.second, exhs.size) }
            .sortedByDescending { it.count }
    }

    // ── City sort-by-count tests ────────────────────────────────────────

    @Test
    fun `cities sorted by active exhibition count descending`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "부산", cityEn = "Busan"),
            exhibition(id = "2", cityKo = "서울", cityEn = "Seoul"),
            exhibition(id = "3", cityKo = "서울", cityEn = "Seoul"),
            exhibition(id = "4", cityKo = "서울", cityEn = "Seoul"),
            exhibition(id = "5", cityKo = "대구", cityEn = "Daegu"),
            exhibition(id = "6", cityKo = "대구", cityEn = "Daegu"),
        )
        val result = computeDistinctCities(exhibitions)

        assertEquals(3, result.size)
        assertEquals("서울", result[0].cityKo)
        assertEquals(3, result[0].count)
        assertEquals("대구", result[1].cityKo)
        assertEquals(2, result[1].count)
        assertEquals("부산", result[2].cityKo)
        assertEquals(1, result[2].count)
    }

    @Test
    fun `only active exhibitions counted, ended exhibitions excluded`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "서울", cityEn = "Seoul"),
            exhibition(id = "2", cityKo = "서울", cityEn = "Seoul", closingDate = tenDaysAgo), // ended
            exhibition(id = "3", cityKo = "부산", cityEn = "Busan"),
        )
        val result = computeDistinctCities(exhibitions)

        assertEquals(2, result.size)
        // Seoul has only 1 active, Busan has 1 — tied, order may vary
        val seoul = result.first { it.cityKo == "서울" }
        assertEquals(1, seoul.count) // ended one excluded
    }

    @Test
    fun `city with zero active exhibitions does not appear`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "서울", cityEn = "Seoul", closingDate = tenDaysAgo), // ended
        )
        val result = computeDistinctCities(exhibitions)

        assertTrue(result.isEmpty())
    }

    // ── Region grouping tests ───────────────────────────────────────────

    @Test
    fun `regions grouped correctly for selected city sorted by count`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu"),
            exhibition(id = "2", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu"),
            exhibition(id = "3", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu"),
            exhibition(id = "4", cityKo = "서울", regionKo = "종로구", regionEn = "Jongno-gu"),
            exhibition(id = "5", cityKo = "서울", regionKo = "마포구", regionEn = "Mapo-gu"),
            exhibition(id = "6", cityKo = "서울", regionKo = "마포구", regionEn = "Mapo-gu"),
            exhibition(id = "7", cityKo = "부산", regionKo = "해운대구", regionEn = "Haeundae-gu"),
        )
        val result = computeDistinctRegions(exhibitions, "서울")

        assertEquals(3, result.size)
        assertEquals("강남구", result[0].regionKo)
        assertEquals(3, result[0].count)
        assertEquals("마포구", result[1].regionKo)
        assertEquals(2, result[1].count)
        assertEquals("종로구", result[2].regionKo)
        assertEquals(1, result[2].count)
    }

    @Test
    fun `empty regions when no city selected`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu"),
        )
        val result = computeDistinctRegions(exhibitions, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `city with single region shows one entry`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "대구", cityEn = "Daegu", regionKo = "중구", regionEn = "Jung-gu"),
            exhibition(id = "2", cityKo = "대구", cityEn = "Daegu", regionKo = "중구", regionEn = "Jung-gu"),
        )
        val result = computeDistinctRegions(exhibitions, "대구")

        assertEquals(1, result.size)
        assertEquals("중구", result[0].regionKo)
        assertEquals(2, result[0].count)
    }

    @Test
    fun `ended exhibitions excluded from region counts`() {
        val exhibitions = listOf(
            exhibition(id = "1", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu"),
            exhibition(id = "2", cityKo = "서울", regionKo = "강남구", regionEn = "Gangnam-gu", closingDate = tenDaysAgo),
            exhibition(id = "3", cityKo = "서울", regionKo = "종로구", regionEn = "Jongno-gu", closingDate = tenDaysAgo),
        )
        val result = computeDistinctRegions(exhibitions, "서울")

        assertEquals(1, result.size) // only 강남구 has active exhibition
        assertEquals("강남구", result[0].regionKo)
        assertEquals(1, result[0].count)
    }

    // ── Region clear on city change (FilterState behavior) ──────────────

    @Test
    fun `FilterState regions cleared produces empty regions list`() {
        val filter = FilterState(regions = listOf("강남구", "종로구"))
        val cleared = filter.copy(regions = emptyList())

        assertTrue(cleared.regions.isEmpty())
        // Verify matches behavior: empty regions matches all
        val exhibition = exhibition(regionKo = "마포구")
        assertTrue(cleared.matches(exhibition))
    }
}
