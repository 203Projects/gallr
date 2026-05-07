package com.gallr.app.notifications

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ActivityNotificationPermissionRequester(
    activity: ComponentActivity,
) : NotificationPermissionRequester {

    private var pending: Continuation<Boolean>? = null

    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.resume(granted)
            pending = null
        }

    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return suspendCoroutine { cont ->
            pending = cont
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
