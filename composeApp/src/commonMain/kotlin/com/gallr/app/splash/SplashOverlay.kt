package com.gallr.app.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.zIndex
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

/**
 * Full-screen splash overlay. Renders the arch-pin logo on a theme-aware
 * background. Fades out (200ms) when controller.isVisible becomes false.
 * Sits at zIndex Float.MAX_VALUE so it covers the entire app while visible.
 */
@Composable
fun SplashOverlay(
    controller: SplashController,
    modifier: Modifier = Modifier,
) {
    val visible by controller.isVisible.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(0)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier.zIndex(Float.MAX_VALUE),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.size(splashLogoDp),
            )
        }
    }
}
