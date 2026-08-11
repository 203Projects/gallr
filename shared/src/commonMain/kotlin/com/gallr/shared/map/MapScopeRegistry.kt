package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoBounds
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.model.map.MapScope
import com.gallr.shared.data.model.map.MapScopeId
import com.gallr.shared.data.model.map.MapScopeKind
import com.gallr.shared.data.model.map.ScopeAggregate

/** Korea-first scope registry with generic country/city/district contracts. */
class DefaultMapScopeRegistry {
    val rootScope =
        MapScope(
            id = MapScopeId("country:KR"),
            kind = MapScopeKind.COUNTRY,
            parentId = null,
            countryCode = "KR",
            cityKey = null,
            districtKey = null,
            labelKo = "대한민국",
            labelEn = "South Korea",
            geoBounds = KOREA_BOUNDS,
            geometryKey = "korea",
        )

    val seoulScope =
        MapScope(
            id = MapScopeId("city:KR:seoul"),
            kind = MapScopeKind.CITY,
            parentId = rootScope.id,
            countryCode = "KR",
            cityKey = "seoul",
            districtKey = null,
            labelKo = "서울",
            labelEn = "Seoul",
            geoBounds = SEOUL_BOUNDS,
            geometryKey = "seoul",
        )

    fun childScopes(
        parent: MapScope,
        exhibitions: List<Exhibition>,
    ): List<MapScope> =
        when (parent.kind) {
            MapScopeKind.COUNTRY -> {
                exhibitions
                    .asSequence()
                    .filter { it.countryCode == parent.countryCode }
                    .groupBy { locationKey(it.cityEn, it.cityKo) }
                    .filterKeys { it.isNotEmpty() }
                    .map { (cityKey, rows) -> cityScope(parent, cityKey, rows) }
                    .sortedBy { it.id.value }
            }

            MapScopeKind.CITY -> {
                val rowsByDistrict =
                    exhibitionsInScope(parent, exhibitions)
                        .groupBy { locationKey(it.regionEn, it.regionKo) }
                        .filterKeys { it.isNotEmpty() }
                if (parent.cityKey == "seoul") {
                    SEOUL_DISTRICTS.map { district ->
                        districtScope(
                            parent = parent,
                            districtKey = locationKey(district.labelEn, district.labelKo),
                            rows = rowsByDistrict[locationKey(district.labelEn, district.labelKo)].orEmpty(),
                            labelKo = district.labelKo,
                            labelEn = district.labelEn,
                        )
                    }
                } else {
                    rowsByDistrict
                        .map { (districtKey, rows) -> districtScope(parent, districtKey, rows) }
                        .sortedBy { it.id.value }
                }
            }

            MapScopeKind.DISTRICT -> {
                emptyList()
            }
        }

    fun scope(
        scopeId: MapScopeId,
        exhibitions: List<Exhibition>,
    ): MapScope? {
        if (scopeId == rootScope.id) return rootScope
        if (scopeId == seoulScope.id) return seoulScope
        val cities = childScopes(rootScope, exhibitions)
        cities.firstOrNull { it.id == scopeId }?.let { return it }
        return cities
            .asSequence()
            .flatMap { city -> childScopes(city, exhibitions).asSequence() }
            .firstOrNull { it.id == scopeId }
    }

    fun exhibitionsInScope(
        scope: MapScope,
        exhibitions: List<Exhibition>,
    ): List<Exhibition> =
        exhibitions.filter { exhibition ->
            exhibition.countryCode == scope.countryCode &&
                (scope.cityKey == null || locationKey(exhibition.cityEn, exhibition.cityKo) == scope.cityKey) &&
                (
                    scope.districtKey == null ||
                        locationKey(exhibition.regionEn, exhibition.regionKo) == scope.districtKey
                )
        }

    private fun cityScope(
        parent: MapScope,
        cityKey: String,
        rows: List<Exhibition>,
    ): MapScope {
        val bounds = if (cityKey == "seoul") SEOUL_BOUNDS else boundsFor(rows) ?: parent.geoBounds
        return MapScope(
            id = MapScopeId("city:${parent.countryCode}:$cityKey"),
            kind = MapScopeKind.CITY,
            parentId = parent.id,
            countryCode = parent.countryCode,
            cityKey = cityKey,
            districtKey = null,
            labelKo = preferredLabel(rows.map { it.cityKo }),
            labelEn = preferredLabel(rows.map { it.cityEn }).ifBlank { preferredLabel(rows.map { it.cityKo }) },
            geoBounds = bounds,
            geometryKey = if (cityKey == "seoul") "seoul" else null,
        )
    }

    private fun districtScope(
        parent: MapScope,
        districtKey: String,
        rows: List<Exhibition>,
        labelKo: String = preferredLabel(rows.map { it.regionKo }),
        labelEn: String = preferredLabel(rows.map { it.regionEn }).ifBlank { labelKo },
    ) = MapScope(
        id = MapScopeId("district:${parent.countryCode}:${parent.cityKey}:$districtKey"),
        kind = MapScopeKind.DISTRICT,
        parentId = parent.id,
        countryCode = parent.countryCode,
        cityKey = parent.cityKey,
        districtKey = districtKey,
        labelKo = labelKo,
        labelEn = labelEn,
        geoBounds = boundsFor(rows) ?: parent.geoBounds,
        geometryKey = null,
    )

    private fun boundsFor(rows: List<Exhibition>): GeoBounds? =
        GeoBounds.containing(
            rows.mapNotNull { row ->
                val latitude = row.latitude ?: return@mapNotNull null
                val longitude = row.longitude ?: return@mapNotNull null
                runCatching { GeoPoint(latitude, longitude) }.getOrNull()
            },
        )

    companion object {
        val KOREA_BOUNDS = GeoBounds(north = 38.63, east = 130.93, south = 33.06, west = 124.53)
        val SEOUL_BOUNDS = GeoBounds(north = 37.72, east = 127.28, south = 37.41, west = 126.72)

        private val SEOUL_DISTRICTS =
            listOf(
                SeoulDistrict("강남구", "Gangnam-gu"),
                SeoulDistrict("강동구", "Gangdong-gu"),
                SeoulDistrict("강북구", "Gangbuk-gu"),
                SeoulDistrict("강서구", "Gangseo-gu"),
                SeoulDistrict("관악구", "Gwanak-gu"),
                SeoulDistrict("광진구", "Gwangjin-gu"),
                SeoulDistrict("구로구", "Guro-gu"),
                SeoulDistrict("금천구", "Geumcheon-gu"),
                SeoulDistrict("노원구", "Nowon-gu"),
                SeoulDistrict("도봉구", "Dobong-gu"),
                SeoulDistrict("동대문구", "Dongdaemun-gu"),
                SeoulDistrict("동작구", "Dongjak-gu"),
                SeoulDistrict("마포구", "Mapo-gu"),
                SeoulDistrict("서대문구", "Seodaemun-gu"),
                SeoulDistrict("서초구", "Seocho-gu"),
                SeoulDistrict("성동구", "Seongdong-gu"),
                SeoulDistrict("성북구", "Seongbuk-gu"),
                SeoulDistrict("송파구", "Songpa-gu"),
                SeoulDistrict("양천구", "Yangcheon-gu"),
                SeoulDistrict("영등포구", "Yeongdeungpo-gu"),
                SeoulDistrict("용산구", "Yongsan-gu"),
                SeoulDistrict("은평구", "Eunpyeong-gu"),
                SeoulDistrict("종로구", "Jongno-gu"),
                SeoulDistrict("중구", "Jung-gu"),
                SeoulDistrict("중랑구", "Jungnang-gu"),
            )
    }
}

private data class SeoulDistrict(
    val labelKo: String,
    val labelEn: String,
)

object ScopeAggregator {
    fun aggregate(
        exhibitions: List<Exhibition>,
        bookmarkedIds: Set<String>,
        visitedExhibitionIds: Set<String> = emptySet(),
        visitCount: Int = 0,
    ): ScopeAggregate {
        val ids = exhibitions.mapTo(linkedSetOf()) { it.id }
        val visited = ids.count { it in visitedExhibitionIds }
        val saved = ids.count { it in bookmarkedIds && it !in visitedExhibitionIds }
        return ScopeAggregate(
            activeExhibitionCount = ids.size,
            visitedExhibitionCount = visited,
            visitCount = visitCount,
            savedUnvisitedCount = saved,
            unexploredCount = ids.size - visited - saved,
            coordinateUnavailableCount = exhibitions.count { it.latitude == null || it.longitude == null },
        )
    }
}

internal fun locationKey(
    preferred: String,
    fallback: String,
): String =
    preferred
        .ifBlank { fallback }
        .trim()
        .lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')

private fun preferredLabel(values: List<String>): String =
    values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        ?.key
        .orEmpty()
