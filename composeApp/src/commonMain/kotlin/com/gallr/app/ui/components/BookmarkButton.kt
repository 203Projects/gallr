package com.gallr.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gallr.app.ui.theme.GallrAccent

/**
 * Heart bookmark button with animation.
 *
 * ♥ = bookmarked (filled, accent orange with bounce), ♡ = not bookmarked (outlined).
 * tintColor flows from ExhibitionCard to support press-inversion for the outline state.
 * detectTapGestures used for CMP iOS compatibility (avoids collectIsPressedAsState bug).
 */
@Composable
fun BookmarkButton(
    isBookmarked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val a11yLabel = if (isBookmarked) "Remove bookmark" else "Add bookmark"

    // Bounce animation on bookmark
    var bouncing by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (bouncing) 1.3f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        finishedListener = { bouncing = false },
        label = "heartScale",
    )

    // Color transition
    val heartColor by animateColorAsState(
        targetValue = if (isBookmarked) GallrAccent.activeIndicator else tintColor,
        animationSpec = tween(durationMillis = 200),
        label = "heartColor",
    )

    // Trigger bounce when bookmarked changes to true
    LaunchedEffect(isBookmarked) {
        if (isBookmarked) bouncing = true
    }

    IconButton(
        onClick = onToggle,
        modifier = modifier
            .semantics { contentDescription = a11yLabel }
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        tryAwaitRelease()
                    },
                )
            },
    ) {
        Text(
            text = if (isBookmarked) "♥" else "♡",
            color = heartColor,
            fontSize = 20.sp,
            modifier = Modifier.scale(scale),
        )
    }
}
