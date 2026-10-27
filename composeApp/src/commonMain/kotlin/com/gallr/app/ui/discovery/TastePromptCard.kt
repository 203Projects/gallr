package com.gallr.app.ui.discovery

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.tabs.home.TastePromptKind
import com.gallr.app.ui.tabs.home.tastePromptCopy
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/**
 * The 내 취향 slot when there is nothing personal to show (spec 101 FR-004): one card that opens the taste
 * sheet, in place of editorial cards under a taste heading. Home's card shape with a hairline outline.
 */
@Composable
internal fun TastePromptCard(
    kind: TastePromptKind,
    language: AppLanguage,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = tastePromptCopy(kind, language)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.screenMargin)
                .border(1.dp, scheme.outlineVariant, GallrShapeTokens.card)
                .padding(GallrSpacing.md),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
    ) {
        Text(text = copy.message, style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground)
        Button(
            onClick = onOpen,
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = scheme.onBackground,
                    contentColor = scheme.background,
                ),
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(text = copy.action)
        }
    }
}
