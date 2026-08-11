package com.gallr.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
actual fun rememberOpenExternalUri(): (String, (Boolean) -> Unit) -> Unit = remember {
    { uri, onResult ->
        val url = NSURL(string = uri)
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = onResult,
        )
    }
}
