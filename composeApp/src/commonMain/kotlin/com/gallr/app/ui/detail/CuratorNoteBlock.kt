package com.gallr.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AppLanguage

/** Fixed wording of the detail screen's note block (spec 090 FR-013). */
internal data class CuratorNoteCopy(
    val label: String,
    val tipLabel: String,
)

internal fun curatorNoteCopy(language: AppLanguage): CuratorNoteCopy =
    when (language) {
        AppLanguage.KO -> CuratorNoteCopy(label = "큐레이터의 시선", tipLabel = "관람 팁")
        AppLanguage.EN -> CuratorNoteCopy(label = "CURATOR'S EYE", tipLabel = "Viewing tip")
    }

/**
 * The full note on the exhibition page: label, hook, insight paragraphs and the viewing tip. Rendered only
 * when a note exists; the caller shows nothing otherwise.
 */
@Composable
internal fun CuratorNoteBlock(
    note: CuratorNote,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    val copy = curatorNoteCopy(lang)
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
        Spacer(Modifier.height(GallrSpacing.md))
        Text(
            text = copy.label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        Text(
            text = note.hook(lang),
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onBackground,
        )
        note.insights(lang).forEach { paragraph ->
            Spacer(Modifier.height(GallrSpacing.md))
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onBackground,
            )
        }
        val tip = note.tip(lang)
        if (tip.isNotBlank()) {
            Spacer(Modifier.height(GallrSpacing.md))
            Text(
                text = "${copy.tipLabel} · $tip",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onBackground,
            )
        }
        Spacer(Modifier.height(GallrSpacing.md))
    }
}
