package com.gallr.shared.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

/** Validated, provider-neutral coordinates and label for a platform map handoff. */
data class ExternalMapDestination(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

/** Builds a map destination only when both coordinates are finite and legal. */
fun Exhibition.toExternalMapDestination(language: AppLanguage): ExternalMapDestination? {
    val destinationLatitude = latitude ?: return null
    val destinationLongitude = longitude ?: return null
    if (
        !destinationLatitude.isFinite() ||
        !destinationLongitude.isFinite() ||
        destinationLatitude !in -90.0..90.0 ||
        destinationLongitude !in -180.0..180.0
    ) {
        return null
    }
    return ExternalMapDestination(
        latitude = destinationLatitude,
        longitude = destinationLongitude,
        label = "${localizedName(language)} — ${localizedVenueName(language)}",
    )
}
