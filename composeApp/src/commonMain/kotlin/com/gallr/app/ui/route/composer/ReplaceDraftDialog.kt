package com.gallr.app.ui.route.composer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import com.gallr.shared.data.model.AppLanguage

/** Asked before a new draft replaces stops that were never saved (design state model, R2-10). */
@Composable
fun ReplaceDraftDialog(
    language: AppLanguage,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text(replaceDraftTitle(language), style = MaterialTheme.typography.titleMedium) },
        confirmButton = {
            TextButton(onClick = onDiscard, shape = RectangleShape) {
                Text(replaceDraftDiscardLabel(language), color = MaterialTheme.colorScheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing, shape = RectangleShape) {
                Text(replaceDraftKeepLabel(language), color = MaterialTheme.colorScheme.onBackground)
            }
        },
    )
}
