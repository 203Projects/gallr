package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.ui.theme.HomeVisualTokens

/**
 * A run's status (오픈 예정, 종료 예정) as the share card draws it: a small accent dot before monochrome text (owner
 * decision 2026-11-12). The words take [color], the colour of the text around them, so they read on white, in dark
 * mode and in white over a picture's scrim; accent text at this size reads at 3.22:1 on white and 1.47:1 over a
 * light picture. The dot is a signal beside the words, never the only carrier of the meaning.
 */
@Composable
internal fun ExhibitionStatusLabel(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(HomeVisualTokens.dotSize)
                .background(GallrAccent.activeIndicator, CircleShape),
        )
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
