package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import kotlin.time.Instant

/**
 * One exhibition in an author's route (spec 089). The names, venue, location and district are a snapshot: the
 * server copies them from the catalogue at save time (E-D7, RR2), so a stop still renders after its exhibition
 * leaves the catalogue. Stops added on the device carry the catalogue values they were added with.
 */
data class PersonalRouteStop(
    val exhibitionId: String,
    val nameKo: String,
    val nameEn: String,
    val venueNameKo: String,
    val venueNameEn: String,
    val point: GeoPoint,
    val regionKo: String,
    val regionEn: String,
    val cityKo: String,
)

/**
 * An authored, named, ordered route. The order is the author's and is never changed by evaluation.
 *
 * [id] is chosen on the device before the first save so a retried save never creates a second route (E-D17).
 * [revision] is the server's last-saved timestamp; null until the route has been saved.
 */
data class PersonalRoute(
    val id: String,
    val name: String,
    val stops: List<PersonalRouteStop>,
    val isPublished: Boolean = false,
    val revision: Instant? = null,
) {
    /** The name as it is stored: surrounding whitespace removed. */
    val trimmedName: String get() = name.trim()

    /** Why this route cannot be saved yet, or null when it can (FR-002). */
    fun saveProblem(): RouteSaveProblem? {
        val nameLength = trimmedName.characterCount()
        return when {
            nameLength == 0 -> RouteSaveProblem.NAME_EMPTY
            nameLength > MAX_ROUTE_NAME_LENGTH -> RouteSaveProblem.NAME_TOO_LONG
            stops.size < MIN_ROUTE_STOPS -> RouteSaveProblem.TOO_FEW_STOPS
            stops.size > MAX_ROUTE_STOPS -> RouteSaveProblem.TOO_MANY_STOPS
            stops.distinctBy(PersonalRouteStop::exhibitionId).size != stops.size -> RouteSaveProblem.DUPLICATE_STOP
            else -> null
        }
    }
}

/** Reasons a route cannot be saved, matching the server's own checks. */
enum class RouteSaveProblem { NAME_EMPTY, NAME_TOO_LONG, TOO_FEW_STOPS, TOO_MANY_STOPS, DUPLICATE_STOP }

/** One row of the author's saved routes (the 내 동선 list), with its public-list state (spec 089 US7). */
data class PersonalRouteSummary(
    val id: String,
    val name: String,
    val stopCount: Int,
    val isPublished: Boolean,
    val isRevoked: Boolean,
    val updatedAt: Instant,
    val listingState: RouteListingState = RouteListingState.Unlisted,
    val declineReason: RouteDeclineReason? = null,
    /** Staff's optional note shown under the decline reason. */
    val declineNote: String? = null,
    /** Why the route is not shown to readers, or null when nothing blocks it. */
    val listingBlocker: RouteListingBlocker? = null,
    /** True while the author holds an active editor membership (their edits stay approved). */
    val authorIsEditor: Boolean = false,
)

/**
 * The stop this exhibition becomes when added to a route, or null when it has no map location and so cannot
 * be part of a walk (RO5).
 */
fun Exhibition.toRouteStop(): PersonalRouteStop? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return null
    return PersonalRouteStop(
        exhibitionId = id,
        nameKo = nameKo,
        nameEn = nameEn,
        venueNameKo = venueNameKo,
        venueNameEn = venueNameEn,
        point = point,
        regionKo = regionKo,
        regionEn = regionEn,
        cityKo = cityKo,
    )
}

/** Counts Unicode code points, as the database's `char_length` does, rather than UTF-16 units. */
private fun String.characterCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        index += if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}

const val MIN_ROUTE_STOPS = 2
const val MAX_ROUTE_STOPS = 10
const val MAX_ROUTE_NAME_LENGTH = 60
