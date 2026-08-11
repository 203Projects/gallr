package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.MapScopeKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapScopeRegistryTest {
    private val registry = DefaultMapScopeRegistry()

    @Test
    fun `Korea root exposes catalog cities and opens supported Seoul scope`() {
        val exhibitions = listOf(
            exhibition("seoul-a", "KR", "서울", "Seoul", "종로구", "Jongno-gu"),
            exhibition("seoul-b", "KR", "서울", "Seoul", "용산구", "Yongsan-gu"),
            exhibition("busan", "KR", "부산", "Busan", "해운대구", "Haeundae-gu"),
            exhibition("tokyo", "JP", "도쿄", "Tokyo", "시부야구", "Shibuya"),
        )

        val root = registry.rootScope
        assertEquals("country:KR", root.id.value)
        assertEquals(MapScopeKind.COUNTRY, root.kind)

        val cities = registry.childScopes(root, exhibitions)
        assertEquals(listOf("city:KR:busan", "city:KR:seoul"), cities.map { it.id.value })

        val seoul = assertNotNull(cities.firstOrNull { it.id.value == "city:KR:seoul" })
        assertEquals("seoul", seoul.geometryKey)
        assertEquals("서울", seoul.labelKo)
    }

    @Test
    fun `city children use stable district identities rather than visible translations`() {
        val exhibitions = listOf(
            exhibition("a", regionKo = "종로구", regionEn = "Jongno-gu"),
            exhibition("b", regionKo = "종로구", regionEn = "Jongno-gu"),
            exhibition("c", regionKo = "용산구", regionEn = "Yongsan-gu"),
        )
        val seoul = registry.childScopes(registry.rootScope, exhibitions)
            .single { it.id.value == "city:KR:seoul" }

        val districts = registry.childScopes(seoul, exhibitions)

        assertEquals(25, districts.size)
        assertEquals(
            2,
            registry.exhibitionsInScope(
                districts.single { it.id.value == "district:KR:seoul:jongno-gu" },
                exhibitions,
            ).size,
        )
        assertEquals(
            1,
            registry.exhibitionsInScope(
                districts.single { it.id.value == "district:KR:seoul:yongsan-gu" },
                exhibitions,
            ).size,
        )
        assertEquals(
            0,
            registry.exhibitionsInScope(
                districts.single { it.id.value == "district:KR:seoul:gangnam-gu" },
                exhibitions,
            ).size,
        )
    }

    @Test
    fun `aggregate separates saved unexplored and unavailable coordinates`() {
        val exhibitions = listOf(
            exhibition("saved"),
            exhibition("unexplored"),
            exhibition("missing", latitude = null, longitude = null),
        )

        val aggregate = ScopeAggregator.aggregate(exhibitions, bookmarkedIds = setOf("saved"))

        assertEquals(3, aggregate.activeExhibitionCount)
        assertEquals(1, aggregate.savedUnvisitedCount)
        assertEquals(2, aggregate.unexploredCount)
        assertEquals(1, aggregate.coordinateUnavailableCount)
        assertTrue(aggregate.visitedExhibitionCount == 0 && aggregate.visitCount == 0)
    }

    private fun exhibition(
        id: String,
        countryCode: String = "KR",
        cityKo: String = "서울",
        cityEn: String = "Seoul",
        regionKo: String = "종로구",
        regionEn: String = "Jongno-gu",
        latitude: Double? = 37.57,
        longitude: Double? = 126.98,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = cityKo,
        cityEn = cityEn,
        regionKo = regionKo,
        regionEn = regionEn,
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        countryCode = countryCode,
    )
}
