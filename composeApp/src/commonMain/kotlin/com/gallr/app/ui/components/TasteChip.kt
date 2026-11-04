package com.gallr.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrShapeTokens

/**
 * One taste term as an outline chip (DESIGN.md, Taste tags). With [onClick] it is a button into the taste sheet:
 * a full-size touch target around a chip drawn at its own size, so the label stays centred, and [actionLabel]
 * tells a screen reader that a tap edits the taste rather than filtering by the term.
 */
@Composable
internal fun TasteChip(
    text: String,
    color: Color,
    style: TextStyle,
    contentPadding: PaddingValues,
    actionLabel: String?,
    onClick: (() -> Unit)?,
) {
    val tappable =
        if (onClick == null) {
            Modifier
        } else {
            Modifier
                .minimumInteractiveComponentSize()
                .clip(GallrShapeTokens.small)
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
        }
    Text(
        text = text,
        style = style,
        color = color,
        modifier =
            tappable
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GallrShapeTokens.small)
                .padding(contentPadding),
    )
}
