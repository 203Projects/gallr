package com.gallr.shared.data.network

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.network.dto.WalkingLegDto
import com.gallr.shared.data.network.dto.WalkingRouteRequestDto
import com.gallr.shared.data.network.dto.WalkingRouteResponseDto
import com.gallr.shared.data.network.dto.WalkingWaypointDto
import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.WalkingRouter
import com.gallr.shared.map.decodePolyline
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/** A leg as the walking-route service answered it. */
sealed interface WalkingLegResult {
    data class Routed(
        val distanceMeters: Int,
        val geometry: List<GeoPoint>,
        /** Who drew the path; null when the service named none or one this app does not know. */
        val router: WalkingRouter? = null,
    ) : WalkingLegResult

    /** No usable path: the app keeps its estimate for this leg. */
    data object Unavailable : WalkingLegResult
}

/** Source of walking legs for consecutive venues, one result per leg in order. */
interface WalkingRouteSource {
    suspend fun legs(waypoints: List<RouteWaypoint>): List<WalkingLegResult>
}

/**
 * Calls the `walking-route` Edge Function (spec 093 contracts/walking-route.md). Readers need no account, so only
 * the publishable key the shared HTTP client already carries is sent. Only venues are accepted: a place without an
 * exhibition id is refused before anything leaves the device (FR-004).
 */
class WalkingRouteApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : WalkingRouteSource {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/functions/v1/walking-route"

    override suspend fun legs(waypoints: List<RouteWaypoint>): List<WalkingLegResult> {
        val venues =
            waypoints.map { waypoint ->
                val id = requireNotNull(waypoint.exhibitionId) { "only venues are sent for walking legs" }
                WalkingWaypointDto(exhibitionId = id, lat = waypoint.point.latitude, lng = waypoint.point.longitude)
            }
        val response =
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Origin, APP_ORIGIN)
                setBody(WalkingRouteRequestDto(venues))
            }
        check(response.status == HttpStatusCode.OK) { "walking route service unavailable" }
        val legs = response.body<WalkingRouteResponseDto>().legs
        return List(waypoints.size - 1) { index -> legs.getOrNull(index)?.toResult() ?: WalkingLegResult.Unavailable }
    }
}

private fun WalkingLegDto.toResult(): WalkingLegResult {
    val distance = distanceMeters
    if (status != ROUTED || distance == null || distance < 0 || polyline == null) return WalkingLegResult.Unavailable
    val geometry = decodePolyline(polyline)
    if (geometry.isEmpty()) return WalkingLegResult.Unavailable
    return WalkingLegResult.Routed(distance, geometry, walkingRouter(provider))
}

/** An unknown router name is still a usable path, just one the map cannot credit by name. */
private fun walkingRouter(provider: String?): WalkingRouter? =
    when (provider) {
        PROVIDER_ORS -> WalkingRouter.OPENROUTESERVICE
        PROVIDER_OSRM -> WalkingRouter.OSRM
        else -> null
    }

private const val APP_ORIGIN = "app://gallr"
private const val ROUTED = "routed"
private const val PROVIDER_ORS = "ors"
private const val PROVIDER_OSRM = "osrm"
