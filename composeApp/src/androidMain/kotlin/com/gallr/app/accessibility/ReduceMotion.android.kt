package com.gallr.app.accessibility

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun isReduceMotionOrScreenReaderActive(): Boolean {
    val context = LocalContext.current
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val touchExploration = am?.isTouchExplorationEnabled == true
    val animationsOff = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
    }.getOrDefault(false)
    return touchExploration || animationsOff
}
