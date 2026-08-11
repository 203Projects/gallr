package com.gallr.app.platform

import androidx.compose.runtime.Composable

/** Opens a URI and reports whether the operating system accepted the handoff. */
@Composable
expect fun rememberOpenExternalUri(): (String, (Boolean) -> Unit) -> Unit
