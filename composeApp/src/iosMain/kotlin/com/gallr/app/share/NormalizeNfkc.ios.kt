package com.gallr.app.share

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeNfkc(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
