package com.gallr.app.ui.tabs.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

@Composable
actual fun rememberLastKnownCoordinates(
    enabled: Boolean,
    requestKey: Int,
): Coordinates? {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    LaunchedEffect(enabled, requestKey) {
        if (!enabled) {
            coords = null
            return@LaunchedEffect
        }
        coords =
            runCatching {
                val hasFineLocation =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLocation =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!hasFineLocation && !hasCoarseLocation) return@runCatching null

                val client = LocationServices.getFusedLocationProviderClient(context)
                val location =
                    client.lastLocation.await()
                        ?: client
                            .getCurrentLocation(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                CancellationTokenSource().token,
                            ).await()
                location?.let { Coordinates(it.latitude, it.longitude) }
            }.getOrNull()
    }
    return coords
}
