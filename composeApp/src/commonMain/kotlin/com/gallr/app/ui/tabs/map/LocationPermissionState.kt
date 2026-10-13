package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable

/** Whether the app may read the device location, and whether the system will still ask (spec 089 RO6). */
enum class LocationPermissionStatus {
    GRANTED,

    /** Not granted, and asking will show the system prompt. */
    CAN_ASK,

    /** Not granted, and the system will not show the prompt again until the user changes it in Settings. */
    DENIED_PERMANENTLY,
}

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
