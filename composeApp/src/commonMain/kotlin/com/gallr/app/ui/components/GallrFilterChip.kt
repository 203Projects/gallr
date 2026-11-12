package com.gallr.app.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrShapeTokens

/** The monochrome filter chip: 12dp corners, `activeIndicator` fill only while selected (DESIGN.md, Selection). */
@Composable
internal fun GallrFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    /** The selected fill; the accent by default, `onBackground` where the selection must stay monochrome. */
    selectedColor: Color = GallrAccent.activeIndicator,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = GallrShapeTokens.small,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.background,
                labelColor = MaterialTheme.colorScheme.onBackground,
                selectedContainerColor = selectedColor,
                selectedLabelColor = filterChipSelectedLabelColor(selectedColor, MaterialTheme.colorScheme),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outline,
                selectedBorderColor = selectedColor,
                borderWidth = 1.dp,
                selectedBorderWidth = 1.dp,
            ),
    )
}

/**
 * The label on a selected chip. On the accent it is black `ctaContent` (6.52:1), because the screen colour reads at
 * only 3.22:1 on #FF5400; on a monochrome fill it is the screen colour, the fill's inverse.
 */
internal fun filterChipSelectedLabelColor(
    selectedFill: Color,
    scheme: ColorScheme,
): Color = if (selectedFill == GallrAccent.activeIndicator) GallrAccent.ctaContent else scheme.background
