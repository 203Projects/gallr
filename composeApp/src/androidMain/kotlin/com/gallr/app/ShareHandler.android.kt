package com.gallr.app

import android.content.Context
import android.content.Intent

private var shareContext: Context? = null

fun initShareHandler(context: Context) {
    shareContext = context.applicationContext
}

actual fun createShareHandler(): ShareHandler = object : ShareHandler {
    override fun shareApp() {
        val context = checkNotNull(shareContext) {
            "ShareHandler not initialized. Call initShareHandler(context) in MainActivity.onCreate()."
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out gallr \u2014 https://play.google.com/store/apps/details?id=com.gallr.app",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
