package com.gallr.app.platform

import com.gallr.shared.app.AppPlatform

/** The installed build's marketing version, as text, for the update rule (spec 098); null when it cannot be read. */
expect fun installedAppVersionName(): String?

/** The platform this build runs on, as the version policy names it. */
expect val appPlatform: AppPlatform

/** The store page that updates this app, for the required-update notice. */
expect fun appStoreListingUrl(): String
