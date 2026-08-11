package com.gallr.app.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOpenExternalUri(): (String, (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { uri, onResult ->
            val parsedUri = Uri.parse(uri)
            val intent =
                Intent(
                    if (parsedUri.scheme.equals("mailto", ignoreCase = true)) {
                        Intent.ACTION_SENDTO
                    } else {
                        Intent.ACTION_VIEW
                    },
                    parsedUri,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            onResult(
                runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false),
            )
        }
    }
}
