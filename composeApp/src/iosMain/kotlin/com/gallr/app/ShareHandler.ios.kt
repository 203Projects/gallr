package com.gallr.app

import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

private const val APP_STORE_URL = "https://apps.apple.com/app/gallr/id6760855059"

actual fun createShareHandler(): ShareHandler = object : ShareHandler {
    override fun shareApp() {
        val text = "Check out gallr \u2014 $APP_STORE_URL"
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        @Suppress("DEPRECATION")
        val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootVC?.presentViewController(controller, animated = true, completion = null)
    }
}
