package com.gallr.app.splash

import androidx.compose.ui.unit.Dp

/**
 * Size of the gallr arch-pin logo on the Compose splash overlay.
 *
 * Per-platform — sized to match what the native splash actually renders so
 * the native → Compose hand-off is pixel-perfect (no logo jump). Android
 * SplashScreen API enforces ~192dp; iOS storyboard authors at 72dp.
 */
expect val splashLogoDp: Dp
