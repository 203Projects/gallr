package com.gallr.shared.map

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyExhibition(
    val exhibition: Exhibition,
    val distanceKm: Double,
)

/** Returns coordinate-backed exhibitions ordered from the supplied focal point. */
fun nearestExhibitions(
    exhibitions: List<Exhibition>,
    origin: GeoPoint,
    limit: Int = 2,
): List<NearbyExhibition> {
    require(limit >= 0) { "limit must not be negative" }
    return exhibitions
        .mapNotNull { exhibition ->
            val latitude = exhibition.latitude ?: return@mapNotNull null
            val longitude = exhibition.longitude ?: return@mapNotNull null
            val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return@mapNotNull null
            NearbyExhibition(
                exhibition = exhibition,
                distanceKm = geographicDistanceKm(origin, point),
            )
        }.sortedWith(compareBy<NearbyExhibition>({ it.distanceKm }, { it.exhibition.id }))
        .distinctBy { nearby ->
            val exhibition = nearby.exhibition
            listOf(
                exhibition.venueNameEn
                    .ifBlank { exhibition.venueNameKo }
                    .trim()
                    .lowercase(),
                exhibition.latitude,
                exhibition.longitude,
            ).joinToString(":")
        }.take(limit)
}

/** Great-circle distance between two geographic points. */
fun geographicDistanceKm(
    first: GeoPoint,
    second: GeoPoint,
): Double {
    val firstLat = first.latitude.toRadians()
    val secondLat = second.latitude.toRadians()
    val latitudeDelta = (second.latitude - first.latitude).toRadians()
    val longitudeDelta = (second.longitude - first.longitude).toRadians()
    val a =
        sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLat) * cos(secondLat) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0

private const val EARTH_RADIUS_KM = 6_371.0
