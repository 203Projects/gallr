package com.gallr.app.platform

import androidx.compose.runtime.Composable
import platform.Foundation.NSBundle

@Composable
actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "—"
