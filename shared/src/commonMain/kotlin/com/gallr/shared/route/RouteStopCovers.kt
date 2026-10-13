package com.gallr.shared.route

import com.gallr.shared.data.model.Exhibition

/**
 * The cover image of each stop's exhibition, keyed by exhibition id. A stop whose exhibition has left the
 * catalogue, or has no cover, is absent: stops keep only a snapshot of names and location.
 */
fun routeStopCovers(
    stops: List<PersonalRouteStop>,
    exhibitions: List<Exhibition>,
): Map<String, String> {
    if (stops.isEmpty()) return emptyMap()
    val stopIds = stops.mapTo(mutableSetOf(), PersonalRouteStop::exhibitionId)
    return exhibitions
        .asSequence()
        .filter { it.id in stopIds }
        .mapNotNull { exhibition ->
            exhibition.coverImageUrl
                ?.takeIf(String::isNotBlank)
                ?.let { cover -> exhibition.id to cover }
        }.toMap()
}
