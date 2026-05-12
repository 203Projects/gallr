package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.GuestEditor

/**
 * Editorial banner shown above the exhibition list when the guest-pick
 * filter is active. Left-border accent layout (spec 040): solid 3 dp
 * onSurface bar, monospace "GUEST EDITOR" label, editor name in display
 * style, bilingual title and italic bio.
 */
@Composable
fun GuestEditorBanner(
    editor: GuestEditor,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
            .background(MaterialTheme.colorScheme.surface)
            .height(IntrinsicSize.Min),
    ) {
        // Left accent bar (3 dp solid)
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
        Column(
            modifier = Modifier.padding(GallrSpacing.md),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "게스트 에디터" else "GUEST EDITOR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = editor.localizedTitle(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedBio(lang),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = GallrSpacing.sm),
            )
        }
    }
}
