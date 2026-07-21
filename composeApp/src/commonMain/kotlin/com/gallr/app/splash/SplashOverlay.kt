package com.gallr.app.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.zIndex
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

internal data class SplashPalette(
    val background: Color,
    val logo: Color,
)

internal fun splashPalette(isSystemDark: Boolean): SplashPalette =
    if (isSystemDark) {
        SplashPalette(
            background = Color(0xFF121212),
            logo = Color(0xFFE0E0E0),
        )
    } else {
        SplashPalette(
            background = Color.White,
            logo = Color.Black,
        )
    }

/**
 * Full-screen splash overlay. Renders the arch-pin logo on a system-theme-aware
 * background. Fades out (200ms) when controller.isVisible becomes false.
 * Sits at zIndex Float.MAX_VALUE so it covers the entire app while visible.
 */
@Composable
fun SplashOverlay(
    controller: SplashController,
    modifier: Modifier = Modifier,
) {
    val visible by controller.isVisible.collectAsState()
    // Native launch screens resolve from the device appearance before app
    // preferences are available. Match that same system palette here so the
    // native -> Compose handoff stays identical even when the saved app theme
    // is an explicit Light or Dark override.
    val palette = splashPalette(isSystemInDarkTheme())

    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(0)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier.zIndex(Float.MAX_VALUE),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(palette.logo),
                modifier = Modifier.size(splashLogoDp),
            )
        }
    }
}
