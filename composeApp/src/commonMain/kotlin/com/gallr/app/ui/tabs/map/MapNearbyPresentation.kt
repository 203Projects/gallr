package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.geographicDistanceKm
import kotlin.math.round

private const val NEARBY_FRAME_RADIUS_KM = 5.0
private const val NEARBY_FRAME_EXHIBITION_LIMIT = 5
private const val MINIMUM_FRAME_HALF_LATITUDE = 0.012
private const val MINIMUM_FRAME_HALF_LONGITUDE = 0.015

internal data class AdaptiveNearbyViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val exhibitionIds: List<String>,
)

internal data class OverlapExhibitionPresentation(
    val exhibition: Exhibition,
    val distanceKm: Double?,
)

/** Bounds the user with up to five exhibitions inside the nearby discovery radius. */
internal fun adaptiveNearbyViewport(
    user: Coordinates,
    exhibitions: List<Exhibition>,
    radiusKm: Double = NEARBY_FRAME_RADIUS_KM,
    limit: Int = NEARBY_FRAME_EXHIBITION_LIMIT,
): AdaptiveNearbyViewport? {
    require(radiusKm >= 0.0) { "radiusKm must not be negative" }
    require(limit >= 0) { "limit must not be negative" }
    val origin = user.toGeoPointOrNull() ?: return null
    val nearby =
        exhibitions
            .mapNotNull { exhibition ->
                val point = exhibition.geoPointOrNull() ?: return@mapNotNull null
                exhibition to geographicDistanceKm(origin, point)
            }.filter { (_, distanceKm) -> distanceKm <= radiusKm }
            .sortedWith(compareBy<Pair<Exhibition, Double>>({ it.second }, { it.first.id }))
            .distinctBy { (exhibition, _) -> exhibition.latitude to exhibition.longitude }
            .take(limit)
    if (nearby.isEmpty()) return null

    val latitudes = nearby.mapNotNull { it.first.latitude } + user.latitude
    val longitudes = nearby.mapNotNull { it.first.longitude } + user.longitude
    return AdaptiveNearbyViewport(
        south = minOf(latitudes.min(), user.latitude - MINIMUM_FRAME_HALF_LATITUDE),
        west = minOf(longitudes.min(), user.longitude - MINIMUM_FRAME_HALF_LONGITUDE),
        north = maxOf(latitudes.max(), user.latitude + MINIMUM_FRAME_HALF_LATITUDE),
        east = maxOf(longitudes.max(), user.longitude + MINIMUM_FRAME_HALF_LONGITUDE),
        exhibitionIds = nearby.map { it.first.id },
    )
}

internal fun sortOverlapExhibitionsByDistance(
    exhibitions: List<Exhibition>,
    user: Coordinates?,
): List<OverlapExhibitionPresentation> {
    val origin = user?.toGeoPointOrNull()
    return exhibitions
        .map { exhibition ->
            val distance =
                if (origin == null) {
                    null
                } else {
                    exhibition.geoPointOrNull()?.let { geographicDistanceKm(origin, it) }
                }
            OverlapExhibitionPresentation(exhibition, distance)
        }.sortedWith(
            compareBy<OverlapExhibitionPresentation> { it.distanceKm ?: Double.POSITIVE_INFINITY }
                .thenBy { it.exhibition.id },
        )
}

internal fun overlapSheetTitle(
    count: Int,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> "이 주변 전시 ${count}개"
        AppLanguage.EN -> "$count EXHIBITIONS NEARBY"
    }

internal fun overlapMetadata(
    exhibition: Exhibition,
    distanceKm: Double?,
    language: AppLanguage,
): String {
    val closing =
        when (language) {
            AppLanguage.KO -> "${exhibition.closingDate.month.ordinal + 1}월 ${exhibition.closingDate.day}일까지"

            AppLanguage.EN -> "UNTIL ${englishMonth(
                exhibition.closingDate.month.ordinal + 1,
            )} ${exhibition.closingDate.day}"
        }
    val distance = distanceKm?.let { "${round(it * 10) / 10} KM" }
    return listOfNotNull(distance, closing).joinToString(" · ")
}

private fun Exhibition.geoPointOrNull(): GeoPoint? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
}

private fun Coordinates.toGeoPointOrNull(): GeoPoint? = runCatching { GeoPoint(latitude, longitude) }.getOrNull()

private fun englishMonth(month: Int): String =
    listOf(
        "JAN",
        "FEB",
        "MAR",
        "APR",
        "MAY",
        "JUN",
        "JUL",
        "AUG",
        "SEP",
        "OCT",
        "NOV",
        "DEC",
    )[month - 1]
