package com.gallr.app.ui.diary

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/**
 * The top of MY 일기 when saved shows have ended without an entry (DESIGN.md "MY 일기", Ended saved shows): a card like
 * the Home update nudge with 추가하기, which opens those shows to add, and a close that hides it until more end.
 */
@Composable
internal fun EndedSavedStrip(
    count: Int,
    language: AppLanguage,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDescription = DiaryListCopy.close(language)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GallrShapeTokens.card)
                .padding(start = GallrSpacing.md, top = GallrSpacing.sm, bottom = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DiaryListCopy.endedSaved(count, language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onAdd,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = TARGET),
        ) {
            Text(
                text = DiaryListCopy.endedSavedAdd(language),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        TextButton(
            onClick = onDismiss,
            shape = MaterialTheme.shapes.small,
            modifier =
                Modifier
                    .sizeIn(minWidth = TARGET, minHeight = TARGET)
                    .semantics { contentDescription = closeDescription },
        ) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

private val TARGET = 44.dp
