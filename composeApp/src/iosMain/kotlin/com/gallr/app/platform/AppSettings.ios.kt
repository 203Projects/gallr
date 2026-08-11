package com.gallr.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString

@Composable
actual fun rememberOpenAppSettings(): ((Boolean) -> Unit) -> Unit =
    remember {
        { onResult ->
            val url = NSURL(string = UIApplicationOpenNotificationSettingsURLString)
            UIApplication.sharedApplication.openURL(
                url = url,
                options = emptyMap<Any?, Any>(),
                completionHandler = onResult,
            )
        }
    }
