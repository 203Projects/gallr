package com.gallr.app.platform

import androidx.compose.runtime.Composable

/**
 * Platform-specific image picker composable.
 * Returns a lambda that, when called, launches the photo picker
 * and invokes the callback with compressed JPEG bytes (or null if cancelled).
 */
@Composable
expect fun rememberImagePicker(onImagePicked: (ByteArray?) -> Unit): () -> Unit
