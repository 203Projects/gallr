package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteDeclineReason
import com.gallr.shared.route.RouteListingBlocker
import com.gallr.shared.route.RouteListingState
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A route as returned by the save and publish functions (spec 089). */
@Serializable
data class PersonalRouteDto(
    val id: String,
    val revision: Instant,
    val name: String,
    @SerialName("is_published") val isPublished: Boolean,
    val stops: List<PersonalRouteStopDto>,
) {
    fun toDomain(): PersonalRoute =
        PersonalRoute(
            id = id,
            name = name,
            stops = stops.sortedBy(PersonalRouteStopDto::position).map(PersonalRouteStopDto::toDomain),
            isPublished = isPublished,
            revision = revision,
        )
}

/** A route row read directly with its embedded stops (owner-only under row-level security). */
@Serializable
data class PersonalRouteRowDto(
    val id: String,
    val name: String,
    @SerialName("is_published") val isPublished: Boolean,
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("personal_route_stops") val stops: List<PersonalRouteStopDto>,
) {
    fun toDomain(): PersonalRoute =
        PersonalRoute(
            id = id,
            name = name,
            stops = stops.sortedBy(PersonalRouteStopDto::position).map(PersonalRouteStopDto::toDomain),
            isPublished = isPublished,
            revision = updatedAt,
        )
}

@Serializable
data class PersonalRouteStopDto(
    val position: Int,
    @SerialName("exhibition_id") val exhibitionId: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("venue_name_ko") val venueNameKo: String,
    @SerialName("venue_name_en") val venueNameEn: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("region_ko") val regionKo: String,
    @SerialName("region_en") val regionEn: String,
    @SerialName("city_ko") val cityKo: String,
) {
    fun toDomain(): PersonalRouteStop =
        PersonalRouteStop(
            exhibitionId = exhibitionId,
            nameKo = nameKo,
            nameEn = nameEn,
            venueNameKo = venueNameKo,
            venueNameEn = venueNameEn,
            point = GeoPoint(latitude, longitude),
            regionKo = regionKo,
            regionEn = regionEn,
            cityKo = cityKo,
        )
}

@Serializable
data class PersonalRouteSummaryDto(
    val id: String,
    val name: String,
    @SerialName("stop_count") val stopCount: Int,
    @SerialName("is_published") val isPublished: Boolean,
    @SerialName("revoked_at") val revokedAt: Instant? = null,
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("listing_state") val listingState: String? = null,
    @SerialName("listing_decline_reason") val listingDeclineReason: String? = null,
    @SerialName("listing_decline_note") val listingDeclineNote: String? = null,
    @SerialName("listing_blocker") val listingBlocker: String? = null,
    @SerialName("author_is_editor") val authorIsEditor: Boolean = false,
) {
    fun toDomain(): PersonalRouteSummary =
        PersonalRouteSummary(
            id = id,
            name = name,
            stopCount = stopCount,
            isPublished = isPublished,
            isRevoked = revokedAt != null,
            updatedAt = updatedAt,
            listingState = RouteListingState.fromWire(listingState),
            declineReason = RouteDeclineReason.fromWire(listingDeclineReason),
            declineNote = listingDeclineNote?.trim()?.takeIf(String::isNotEmpty),
            listingBlocker = RouteListingBlocker.fromWire(listingBlocker),
            authorIsEditor = authorIsEditor,
        )
}

/** One row of `list_public_routes`, already ranked by the server (spec 089 US9). */
@Serializable
data class PublicRouteSummaryDto(
    val id: String,
    val name: String,
    @SerialName("stop_count") val stopCount: Int,
    @SerialName("first_district_ko") val firstDistrictKo: String = "",
    @SerialName("first_district_en") val firstDistrictEn: String = "",
    @SerialName("last_district_ko") val lastDistrictKo: String = "",
    @SerialName("last_district_en") val lastDistrictEn: String = "",
    @SerialName("author_display_name") val authorDisplayName: String? = null,
    @SerialName("is_editor") val isEditor: Boolean = false,
    @SerialName("copy_count_30d") val copyCount30d: Int = 0,
    @SerialName("first_shared_day") val firstSharedDay: LocalDate,
    @SerialName("listing_decided_at") val listingDecidedAt: Instant,
) {
    fun toDomain(): PublicRouteSummary =
        PublicRouteSummary(
            id = id,
            name = name,
            stopCount = stopCount,
            firstDistrictKo = firstDistrictKo,
            firstDistrictEn = firstDistrictEn,
            lastDistrictKo = lastDistrictKo,
            lastDistrictEn = lastDistrictEn,
            authorDisplayName = authorDisplayName?.trim()?.takeIf(String::isNotEmpty),
            isEditor = isEditor,
            copyCount30d = copyCount30d,
            firstSharedDay = firstSharedDay,
            approvedAt = listingDecidedAt,
        )
}

/** A shared route read by id through `get_published_route` (also returned by `save_public_route`). */
@Serializable
data class PublishedRouteDto(
    val id: String,
    val name: String,
    val owner: String,
    @SerialName("updated_at") val updatedAt: Instant,
    val stops: List<PersonalRouteStopDto>,
) {
    fun toPublicStops(): PublicRouteStops = PublicRouteStops(route = toDomain(), ownerId = owner)

    fun toDomain(): PersonalRoute =
        PersonalRoute(
            id = id,
            name = name,
            stops = stops.sortedBy(PersonalRouteStopDto::position).map(PersonalRouteStopDto::toDomain),
            isPublished = true,
            revision = updatedAt,
        )
}

/** PostgREST error body; [message] carries the database's `personal_route_*` identifier. */
@Serializable
data class PostgrestErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
)
