package com.gallr.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gallr.shared.app.AppPlatform

private var applicationContext: Context? = null

/** Called once from the Activity so the version can be read outside composition. */
fun initInstalledAppVersion(context: Context) {
    applicationContext = context.applicationContext
}

actual fun installedAppVersionName(): String? {
    val context = applicationContext ?: return null
    return runCatching {
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        packageInfo.versionName
    }.getOrNull()
}

actual val appPlatform: AppPlatform = AppPlatform.ANDROID

actual fun appStoreListingUrl(): String = "https://play.google.com/store/apps/details?id=com.gallr.app"
