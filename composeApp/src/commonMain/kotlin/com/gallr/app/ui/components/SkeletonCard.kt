package com.gallr.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )

    val color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(20.dp)
                .background(color),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(14.dp)
                .background(color),
        )
        Spacer(Modifier.height(GallrSpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .height(12.dp)
                .background(color),
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color),
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .background(color),
        )
    }
}
