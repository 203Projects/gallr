package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable

/** Platform location permission: its current [status] and a callback that shows the system prompt. */
data class LocationPermissionState(
    val status: LocationPermissionStatus,
    val request: () -> Unit,
) {
    val isGranted: Boolean get() = status == LocationPermissionStatus.GRANTED
}

/** Current location permission, refreshed when the app returns to the foreground. */
@Composable
expect fun rememberLocationPermissionState(): LocationPermissionState
