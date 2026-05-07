package com.gallr.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.repository.ThoughtRepository
import kotlinx.coroutines.launch

private const val MAX_CHARS = 280

@Composable
fun ThoughtComposer(
    exhibitionId: String,
    thoughtRepository: ThoughtRepository,
    lang: AppLanguage,
    existingContent: String? = null,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(existingContent ?: "") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md),
    ) {
        Text(
            text = when (lang) {
                AppLanguage.KO -> "감상 남기기"
                AppLanguage.EN -> "Share your thoughts"
            },
            style = MaterialTheme.typography.titleSmall,
        )

        Spacer(Modifier.height(GallrSpacing.md))

        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= MAX_CHARS) text = it },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            placeholder = {
                Text(
                    when (lang) {
                        AppLanguage.KO -> "이 전시에 대한 감상을 나눠주세요..."
                        AppLanguage.EN -> "Share your thoughts on this exhibition..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(Modifier.height(GallrSpacing.xs))

        // Character count + submit
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${text.length}/$MAX_CHARS",
                style = MaterialTheme.typography.labelSmall,
                color = if (text.length > MAX_CHARS - 20)
                    GallrAccent.activeIndicator
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = {
                    if (text.isBlank() || isSubmitting) return@OutlinedButton
                    isSubmitting = true
                    error = null
                    scope.launch {
                        try {
                            if (existingContent != null) {
                                // Updating existing thought — get thought ID first
                                thoughtRepository.submitThought(exhibitionId, text.trim())
                            } else {
                                thoughtRepository.submitThought(exhibitionId, text.trim())
                            }
                            onSubmitted()
                        } catch (e: Exception) {
                            error = e.message?.take(60)
                            isSubmitting = false
                        }
                    }
                },
                enabled = text.isNotBlank() && !isSubmitting,
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = if (isSubmitting) "..." else when (lang) {
                        AppLanguage.KO -> "게시"
                        AppLanguage.EN -> "Post"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = error ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
