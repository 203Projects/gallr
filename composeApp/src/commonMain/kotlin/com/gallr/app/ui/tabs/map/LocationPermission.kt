package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable

/**
 * Platform-specific location permission state.
 * Returns a pair: (isGranted, requestPermission callback).
 */
data class LocationPermissionState(
    val isGranted: Boolean,
    val request: () -> Unit,
)

@Composable
expect fun rememberLocationPermissionState(): LocationPermissionState
