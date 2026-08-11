package com.gallr.app.platform

import androidx.compose.runtime.Composable

@Composable
expect fun rememberOpenAppSettings(): ((Boolean) -> Unit) -> Unit
