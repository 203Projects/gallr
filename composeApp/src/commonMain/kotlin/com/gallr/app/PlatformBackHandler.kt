package com.gallr.app

import androidx.compose.runtime.Composable

/** Handles system back while [enabled]; on iOS back is on-screen only, so this does nothing there. */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)
