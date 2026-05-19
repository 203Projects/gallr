package com.gallr.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor

/**
 * Editor banner shown above the exhibition list on the editor detail page.
 * Left-border accent layout (spec 040/041): solid 3 dp onSurface bar,
 * monospace small-caps label ("GUEST EDITOR" or "HOUSE EDITOR"), editor
 * name in titleLarge, bilingual title, italic bio. Optional meta line
 * below the bio shows the active window + exhibition count.
 */
@Composable
fun EditorBanner(
    editor: Editor,
    lang: AppLanguage,
    exhibitionCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val label = when {
        editor.isHouseEditor -> if (lang == AppLanguage.KO) "하우스 에디터" else "HOUSE EDITOR"
        else -> if (lang == AppLanguage.KO) "게스트 에디터" else "GUEST EDITOR"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
            .background(MaterialTheme.colorScheme.surface)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
        Column(modifier = Modifier.padding(GallrSpacing.md)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Trim before comparing: a seeded title with trailing whitespace
            // would otherwise slip past the dedup and double the label line.
            val localizedTitle = editor.localizedTitle(lang).trim()
            if (localizedTitle.isNotEmpty() && localizedTitle != label.trim()) {
                Text(
                    text = localizedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = editor.localizedBio(lang),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = GallrSpacing.sm),
            )
            if (exhibitionCount > 0) {
                Text(
                    text = formatEditorMeta(editor, exhibitionCount, lang),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GallrSpacing.sm),
                )
            }
        }
    }
}

private val EN_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "Apr 25 — May 24 · 5 exhibitions" / "2026.04.25 – 2026.05.24 · 전시 5개". */
private fun formatEditorMeta(
    editor: Editor,
    exhibitionCount: Int,
    lang: AppLanguage,
): String {
    val from = editor.activeFrom
    val to = editor.activeTo
    val countText = if (lang == AppLanguage.KO) "전시 ${exhibitionCount}개" else "$exhibitionCount exhibitions"
    val rangeText = if (lang == AppLanguage.KO) {
        val fromStr = "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')}"
        val toStr = if (to == null) "진행중"
            else "${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        "$fromStr – $toStr"
    } else {
        val fromStr = "${EN_MONTHS[from.monthNumber - 1]} ${from.dayOfMonth}"
        val toStr = if (to == null) "ongoing"
            else "${EN_MONTHS[to.monthNumber - 1]} ${to.dayOfMonth}"
        "$fromStr — $toStr"
    }
    val separator = if (lang == AppLanguage.KO) " · " else " · "
    return "$rangeText$separator$countText"
}
