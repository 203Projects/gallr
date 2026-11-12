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
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.shared.data.model.AppLanguage

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
    language: AppLanguage = AppLanguage.EN,
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val a11yLabel = bookmarkContentDescription(isBookmarked, language)

    // Bounce animation on bookmark; under reduced motion the heart only changes colour.
    val reduceMotion = isReduceMotionOrScreenReaderActive()
    var bouncing by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = bookmarkHeartScale(bouncing, reduceMotion),
        animationSpec =
            spring(
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
    LaunchedEffect(isBookmarked, reduceMotion) {
        if (isBookmarked && !reduceMotion) bouncing = true
    }

    IconButton(
        onClick = onToggle,
        modifier =
            modifier
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

/** How far the heart grows at the top of its bounce. */
internal const val BOOKMARK_BOUNCE_SCALE = 1.3f

/** The heart's target scale: it bounces when saved, and never moves when the OS asks for reduced motion. */
internal fun bookmarkHeartScale(
    bouncing: Boolean,
    reduceMotion: Boolean,
): Float = if (bouncing && !reduceMotion) BOOKMARK_BOUNCE_SCALE else 1f

internal fun bookmarkContentDescription(
    isBookmarked: Boolean,
    language: AppLanguage,
): String =
    when (language) {
        AppLanguage.KO -> if (isBookmarked) "북마크 삭제" else "북마크 추가"
        AppLanguage.EN -> if (isBookmarked) "Remove bookmark" else "Add bookmark"
    }
