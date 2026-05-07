package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gallr.shared.data.model.ExhibitionMapPin
import kotlin.math.round

/**
 * A location on the map that may contain one or more exhibitions.
 */
data class MapLocation(
    val latitude: Double,
    val longitude: Double,
    val pins: List<ExhibitionMapPin>,
) {
    val count: Int get() = pins.size
    val label: String get() = if (count == 1) pins.first().name else "$count"
}

/**
 * Groups pins that share the same coordinates (within a small tolerance).
 */
fun groupPinsByLocation(pins: List<ExhibitionMapPin>): List<MapLocation> =
    pins.groupBy { "${it.latitude.roundTo4()},${it.longitude.roundTo4()}" }
        .map { (_, group) ->
            MapLocation(
                latitude = group.first().latitude,
                longitude = group.first().longitude,
                pins = group,
            )
        }

private fun Double.roundTo4(): Long = round(this * 10000).toLong()

/**
 * Platform-specific map composable.
 *
 * @param locations Grouped exhibition locations to render as markers.
 * @param onLocationTap Called when a marker is tapped; receives all pins at that location.
 * @param enableUserLocation Whether to show and track user's current location.
 */
@Composable
expect fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier = Modifier,
    enableUserLocation: Boolean = false,
    initialCenter: Coordinates? = null,
)
