package com.gallr.app.accessibility

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

@Composable
actual fun isReduceMotionOrScreenReaderActive(): Boolean =
    UIAccessibilityIsReduceMotionEnabled() || UIAccessibilityIsVoiceOverRunning()
