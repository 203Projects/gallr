package com.gallr.app.platform

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOpenAppSettings(): ((Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { onResult ->
            val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            onResult(
                runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false),
            )
        }
    }
}
