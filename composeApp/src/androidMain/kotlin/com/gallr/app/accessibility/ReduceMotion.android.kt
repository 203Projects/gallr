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
    // "Animations off" is the closest public-SDK analog to iOS Reduce Motion.
    // ANIMATOR_DURATION_SCALE covers all ValueAnimator-driven animation (broader
    // than TRANSITION_ANIMATION_SCALE, which is window enter/exit only).
    // Wrapped in runCatching because the key can be absent / blocked on some devices.
    val animationsOff =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
        }.getOrDefault(false)
    return touchExploration || animationsOff
}
