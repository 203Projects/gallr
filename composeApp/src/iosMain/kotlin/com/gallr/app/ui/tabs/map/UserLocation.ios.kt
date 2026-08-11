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
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberLastKnownCoordinates(
    enabled: Boolean,
    requestKey: Int,
): Coordinates? {
    // Separate from the manager in LocationPermission.ios.kt by design:
    // CLLocationManager is stateless, so a read-only manager here doesn't fight
    // with the permission-owning one over delegate / authorization callbacks.
    val manager = remember { CLLocationManager() }
    var coords by remember { mutableStateOf<Coordinates?>(null) }
    // CLLocationManager.delegate is weak. The delegate must be retained by the
    // composition or a one-shot fix requested after authorization can disappear.
    val delegate =
        remember {
            object : NSObject(), CLLocationManagerDelegateProtocol {
                override fun locationManager(
                    manager: CLLocationManager,
                    didUpdateLocations: List<*>,
                ) {
                    val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                    coords =
                        location.coordinate.useContents {
                            Coordinates(latitude, longitude)
                        }
                }

                override fun locationManager(
                    manager: CLLocationManager,
                    didFailWithError: NSError,
                ) {
                    // Leave coords null → MapScreen falls back to Seoul.
                }
            }
        }

    DisposableEffect(enabled, requestKey, manager, delegate) {
        if (!enabled) {
            coords = null
            return@DisposableEffect onDispose {
                manager.stopUpdatingLocation()
                manager.delegate = null
            }
        }
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 100.0
        manager.delegate = delegate
        manager.startUpdatingLocation()
        onDispose {
            manager.stopUpdatingLocation()
            manager.delegate = null
        }
    }
    return coords
}
