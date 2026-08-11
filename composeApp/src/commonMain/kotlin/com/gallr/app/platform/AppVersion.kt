package com.gallr.app.platform

import androidx.compose.runtime.Composable

/** Returns the platform bundle/package marketing version shown in Settings. */
@Composable
expect fun appVersionName(): String
