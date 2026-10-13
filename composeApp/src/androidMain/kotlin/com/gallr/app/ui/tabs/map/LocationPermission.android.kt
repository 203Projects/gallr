package com.gallr.app.ui.tabs.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val PERMISSION_PREFERENCES = "gallr_location_permission"
private const val BLOCKED_KEY = "blocked_after_request"

/**
 * Android cannot tell "never asked" from "will not ask again" by looking: both report no rationale. So the
 * outcome of the app's own request is recorded: a denial with no rationale to show means the system will not
 * prompt again. Any later grant clears it, so revoking in Settings makes the prompt available again (RO6).
 */
@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf(currentStatus(context, preferences)) }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            val blocked = !isGranted(context) && !showsRationale(context)
            preferences.edit().putBoolean(BLOCKED_KEY, blocked).apply()
            status = currentStatus(context, preferences)
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) status = currentStatus(context, preferences)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return LocationPermissionState(
        status = status,
        request = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
    )
}

private fun currentStatus(
    context: Context,
    preferences: SharedPreferences,
): LocationPermissionStatus {
    if (isGranted(context)) {
        preferences.edit().remove(BLOCKED_KEY).apply()
        return LocationPermissionStatus.GRANTED
    }
    return if (preferences.getBoolean(BLOCKED_KEY, false)) {
        LocationPermissionStatus.DENIED_PERMANENTLY
    } else {
        LocationPermissionStatus.CAN_ASK
    }
}

private fun isGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun showsRationale(context: Context): Boolean {
    val activity = context.findActivity() ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
