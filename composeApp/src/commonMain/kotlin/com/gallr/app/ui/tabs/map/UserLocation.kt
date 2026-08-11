package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A geographic coordinate pair available to map-adjacent features.
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
)

internal data class MapInitialViewport(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

internal fun initialMapViewport(coordinates: Coordinates?): MapInitialViewport =
    if (coordinates == null) {
        MapInitialViewport(latitude = 37.5665, longitude = 126.9780, zoom = 11.8)
    } else {
        MapInitialViewport(
            latitude = coordinates.latitude,
            longitude = coordinates.longitude,
            zoom = 12.5,
        )
    }

/**
 * Returns one device location supplied by the OS, preferring a cached fix, or null if:
 *   - [enabled] is false (typically because permission has not been granted)
 *   - the OS has no cached fix
 *   - the platform call has not yet resolved (the returned value flips from
 *     null to non-null on a later recomposition)
 *   - the platform call failed
 *
 * This is a one-shot lookup rather than continuous tracking. Platforms may request
 * a balanced-power fix when no suitable cached location exists.
 */
@Composable
expect fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates?

/**
 * Returns `true` once it is safe to compose the map for the first time.
 *
 * Use this to defer composing a location-aware surface briefly while
 * [rememberLastKnownCoordinates] resolves, so its initial state does not jump.
 *
 * Returns `true` immediately when:
 *   - [permissionGranted] is false (no point waiting; we will use the Seoul fallback), OR
 *   - [coordsResolved] is true (cached coords are available now)
 *
 * Otherwise returns `false` until [timeoutMillis] elapse, after which it returns
 * `true` regardless of whether coords arrived. The Seoul fallback handles the
 * timeout case gracefully.
 *
 * Once it has returned `true` once, it stays `true` for the lifetime of the
 * composition. This prevents a location-aware surface from being unmounted and
 * remounted when [permissionGranted] flips mid-session.
 */
@Composable
fun rememberMapReadiness(
    permissionGranted: Boolean,
    coordsResolved: Boolean,
    timeoutMillis: Long = 300L,
): Boolean {
    var ready by remember { mutableStateOf(!permissionGranted || coordsResolved) }

    LaunchedEffect(Unit) {
        if (!ready) {
            delay(timeoutMillis)
            ready = true
        }
    }
    if (coordsResolved) ready = true

    return ready
}
