package com.gallr.app.splash

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Visible logo size on the Compose overlay. Must match what the native
// SplashScreen API actually renders so the hand-off is pixel-perfect.
// The vector drawable has 50% transparent padding inside a 200×200
// viewport, so within Android's enforced ~192dp icon canvas the visible
// arch occupies ~96dp. Verified on Samsung SM-F966N.
actual val splashLogoDp: Dp = 96.dp
