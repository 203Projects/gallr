package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates? {
    // Separate from the manager in LocationPermission.ios.kt by design:
    // CLLocationManager is stateless, so a read-only manager here doesn't fight
    // with the permission-owning one over delegate / authorization callbacks.
    val manager = remember { CLLocationManager() }
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    DisposableEffect(enabled) {
        if (!enabled) {
            coords = null
            return@DisposableEffect onDispose { manager.delegate = null }
        }
        // A freshly-created CLLocationManager.location is nil until the OS
        // delivers a fix via the delegate. requestLocation() asks for a single
        // fix, returns the cached value immediately if one exists, and only
        // triggers a GPS query if not — light enough for our cached-only intent.
        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>,
            ) {
                val location = didUpdateLocations.firstOrNull() as? CLLocation ?: return
                coords = location.coordinate.useContents {
                    Coordinates(latitude, longitude)
                }
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                // Leave coords null → MapScreen falls back to Seoul.
            }
        }
        manager.delegate = delegate
        manager.requestLocation()
        onDispose { manager.delegate = null }
    }
    return coords
}
