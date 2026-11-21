package com.gallr.app

import com.gallr.shared.map.ExternalMapDestination
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication

class IosExternalMapLauncher : ExternalMapLauncher {
    override fun open(destination: ExternalMapDestination): Result<Unit> =
        runCatching {
            val coordinates = "${destination.latitude},${destination.longitude}"
            val components = checkNotNull(NSURLComponents(string = "http://maps.apple.com/"))
            components.queryItems =
                listOf(
                    NSURLQueryItem(name = "ll", value = coordinates),
                    NSURLQueryItem(name = "q", value = destination.label),
                )
            val url = checkNotNull(components.URL)
            check(UIApplication.sharedApplication.canOpenURL(url)) {
                "Apple Maps is unavailable"
            }
            UIApplication.sharedApplication.openURL(
                url = url,
                options = emptyMap<Any?, Any>(),
                completionHandler = null,
            )
        }
}
