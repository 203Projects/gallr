package com.gallr.app.ui.profile

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gallr.shared.data.model.AppLanguage

class CropOverlayState {
    var imageBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var lang by mutableStateOf(AppLanguage.KO)
        private set
    var onConfirm: ((IntOffset, IntSize) -> Unit)? = null
        private set
    var onCancel: (() -> Unit)? = null
        private set

    val isActive: Boolean get() = imageBitmap != null

    fun show(
        bitmap: ImageBitmap,
        language: AppLanguage,
        confirm: (IntOffset, IntSize) -> Unit,
        cancel: () -> Unit,
    ) {
        imageBitmap = bitmap
        lang = language
        onConfirm = confirm
        onCancel = cancel
    }

    fun dismiss() {
        imageBitmap = null
        onConfirm = null
        onCancel = null
    }
}

val LocalCropOverlay = compositionLocalOf { CropOverlayState() }
