package com.gallr.shared.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One venue sent to `walking-route` (spec 093 contract); the visitor's own position is never one of them. */
@Serializable
data class WalkingWaypointDto(
    @SerialName("exhibition_id") val exhibitionId: String,
    val lat: Double,
    val lng: Double,
)

@Serializable
data class WalkingRouteRequestDto(
    val waypoints: List<WalkingWaypointDto>,
)

/**
 * One leg as answered: `routed` with a length and an encoded path, or anything else, which is unusable. `provider`
 * names the service that drew the path (`ors`, `osrm`); absent for a leg too short to route.
 */
@Serializable
data class WalkingLegDto(
    val status: String = "",
    @SerialName("distance_m") val distanceMeters: Int? = null,
    val polyline: String? = null,
    val provider: String? = null,
)

@Serializable
data class WalkingRouteResponseDto(
    val legs: List<WalkingLegDto> = emptyList(),
)
