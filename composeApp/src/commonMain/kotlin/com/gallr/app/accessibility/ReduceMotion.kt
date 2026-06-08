package com.gallr.app.accessibility

import androidx.compose.runtime.Composable

/**
 * True when the OS signals reduced motion OR a screen reader is active.
 * Callers disable auto-advancing timers when this is true; all content still
 * renders and remains manually swipeable/tappable.
 */
@Composable
expect fun isReduceMotionOrScreenReaderActive(): Boolean
