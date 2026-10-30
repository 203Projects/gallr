package com.gallr.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulse()
    val base = MaterialTheme.colorScheme.onBackground

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.sm),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .skeleton(pulse, base, GallrShapeTokens.small),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .skeleton(pulse, base, GallrShapeTokens.small),
        )
        Spacer(Modifier.height(GallrSpacing.xs))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.3f)
                    .height(12.dp)
                    .skeleton(pulse, base, GallrShapeTokens.small),
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    // Square by design: a 1dp rule is a hairline, too thin to take a corner.
                    .skeleton(pulse, base, RectangleShape),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .skeleton(pulse, base, GallrShapeTokens.small),
        )
    }
}

/**
 * The pulse every skeleton shares: a slow fade, held still when reduced motion or a screen reader is on. Read it
 * only in the draw phase (see [skeleton]), so a skeleton redraws each frame without recomposing.
 */
@Composable
internal fun rememberSkeletonPulse(): State<Float> {
    if (isReduceMotionOrScreenReaderActive()) return remember { mutableFloatStateOf(STILL_ALPHA) }
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.24f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "skeletonAlpha",
    )
}

/** Fills the element's [shape] with [base] at the current [pulse] alpha, in the draw phase. */
internal fun Modifier.skeleton(
    pulse: State<Float>,
    base: Color,
    shape: Shape,
): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(outline, base.copy(alpha = pulse.value)) }
    }

private const val STILL_ALPHA = 0.16f
