package com.gallr.app.platform

import platform.Foundation.NSBundle

actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "—"
