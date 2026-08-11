package com.gallr.app.ui.tabs.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

private const val LOCATION_UPDATE_INTERVAL_MILLIS = 30_000L
private const val LOCATION_UPDATE_MIN_INTERVAL_MILLIS = 15_000L
private const val LOCATION_UPDATE_MIN_DISTANCE_METERS = 100f

@Composable
actual fun rememberLastKnownCoordinates(
    enabled: Boolean,
    requestKey: Int,
): Coordinates? {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<Coordinates?>(null) }
    val client = remember(context) { LocationServices.getFusedLocationProviderClient(context) }
    val hasLocationPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    val locationCallback =
        remember {
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        coords = Coordinates(location.latitude, location.longitude)
                    }
                }
            }
        }

    DisposableEffect(enabled, hasLocationPermission, client, locationCallback) {
        if (!enabled || !hasLocationPermission) {
            coords = null
            return@DisposableEffect onDispose {}
        }

        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_UPDATE_INTERVAL_MILLIS)
                .setMinUpdateIntervalMillis(LOCATION_UPDATE_MIN_INTERVAL_MILLIS)
                .setMinUpdateDistanceMeters(LOCATION_UPDATE_MIN_DISTANCE_METERS)
                .build()
        client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(locationCallback) }
    }

    LaunchedEffect(enabled, requestKey, hasLocationPermission, client) {
        if (!enabled || !hasLocationPermission) {
            coords = null
            return@LaunchedEffect
        }
        coords =
            runCatching {
                val location =
                    client
                        .getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            CancellationTokenSource().token,
                        ).await()
                location?.let { Coordinates(it.latitude, it.longitude) }
            }.getOrNull() ?: coords
    }
    return coords
}
