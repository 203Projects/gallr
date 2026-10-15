package com.gallr.app

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // No-op on iOS — no system back button; back navigation is UI-driven
}
