package com.gallr.shared.data.model

data class RemotePushAddress(
    val platform: String,
    val provider: String,
    val token: String,
    val environment: String,
) {
    init {
        require(platform in setOf("android", "ios")) { "Unsupported push platform" }
        require(provider in setOf("apns", "fcm")) { "Unsupported push provider" }
        require(environment in setOf("sandbox", "production")) { "Unsupported push environment" }
        require(provider != "fcm" || environment == "production") {
            "FCM addresses use the production provider environment"
        }
        require(
            if (provider == "apns") {
                token.length == 64 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            } else {
                token.length in 20..4096 &&
                    token.all {
                        it.isLetterOrDigit() || it == ':' || it == '_' || it == '-'
                    }
            },
        ) { "Invalid push address" }
    }
}
