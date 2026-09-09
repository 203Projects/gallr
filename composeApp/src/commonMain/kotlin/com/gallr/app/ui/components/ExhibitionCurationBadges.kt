package com.gallr.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ExhibitionCurationBadge

@Composable
fun ExhibitionCurationBadges(
    badges: List<ExhibitionCurationBadge>,
    language: AppLanguage,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
    ) {
        badges.forEach { badge ->
            Text(
                text = badge.label(language),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier =
                    Modifier
                        .border(1.dp, color, RectangleShape)
                        .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
            )
        }
    }
}
