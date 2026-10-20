package com.gallr.app.platform

import com.gallr.shared.app.AppPlatform
import platform.Foundation.NSBundle

actual fun installedAppVersionName(): String? =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

actual val appPlatform: AppPlatform = AppPlatform.IOS

actual fun appStoreListingUrl(): String = "https://apps.apple.com/app/gallr/id6760855059"
