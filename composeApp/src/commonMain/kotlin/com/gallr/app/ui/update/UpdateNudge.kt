package com.gallr.app.ui.update

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/** The recommended-update banner at the top of Home (spec 098 US3): one line, 업데이트 and 나중에, no accent. */
@Composable
internal fun UpdateNudge(
    language: AppLanguage,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.screenMargin)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GallrShapeTokens.card)
                .padding(horizontal = GallrSpacing.md, vertical = GallrSpacing.sm),
    ) {
        Text(
            text = updateNudgeText(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
            NudgeAction(updateActionLabel(language), onUpdate)
            NudgeAction(updateLaterLabel(language), onLater)
        }
    }
}

@Composable
private fun NudgeAction(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
