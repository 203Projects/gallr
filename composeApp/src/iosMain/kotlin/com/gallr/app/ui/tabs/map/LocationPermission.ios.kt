package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject

@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
    val manager = remember { CLLocationManager() }
    var status by remember { mutableStateOf(currentStatus()) }
    // CLLocationManager holds its delegate weakly. Retain it in the composition
    // so the first-run authorization callback cannot be lost before it arrives.
    val delegate =
        remember {
            object : NSObject(), CLLocationManagerDelegateProtocol {
                // Also fires when the app becomes active after the user changed the setting in Settings.
                override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                    status = currentStatus()
                }
            }
        }

    DisposableEffect(manager, delegate) {
        manager.delegate = delegate
        onDispose { manager.delegate = null }
    }

    return LocationPermissionState(
        status = status,
        request = { manager.requestWhenInUseAuthorization() },
    )
}

/** iOS shows the prompt only while the status is not determined; denied or restricted never prompts again. */
private fun currentStatus(): LocationPermissionStatus =
    when (CLLocationManager.authorizationStatus()) {
        kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> {
            LocationPermissionStatus.GRANTED
        }

        kCLAuthorizationStatusNotDetermined -> {
            LocationPermissionStatus.CAN_ASK
        }

        else -> {
            LocationPermissionStatus.DENIED_PERMANENTLY
        }
    }
