package com.gallr.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent

/**
 * Marks a selected option row with the accent bar on its leading edge (DESIGN.md, Selection).
 *
 * The bar is drawn behind the row's own content, so apply it before the row's padding and leave at
 * least `md` horizontal padding so text clears the bar.
 */
fun Modifier.leadingSelectionBar(selected: Boolean): Modifier =
    if (!selected) {
        this
    } else {
        drawBehind {
            drawRect(
                color = GallrAccent.activeIndicator,
                size = Size(SELECTION_BAR_WIDTH.toPx(), size.height),
            )
        }
    }

private val SELECTION_BAR_WIDTH = 3.dp
