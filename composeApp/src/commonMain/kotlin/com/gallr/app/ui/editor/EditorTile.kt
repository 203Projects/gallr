package com.gallr.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor

/**
 * Single tile in the EditorSelectorScreen.
 *
 * Layout: optional left-border accent (house editor only) + Column with
 * uppercase small label (HOUSE EDITOR / GUEST · NOW / month-year for past),
 * editor name, title, and exhibition count.
 */
@Composable
fun EditorTile(
    editor: Editor,
    lang: AppLanguage,
    exhibitionCount: Int,
    isPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelText = when {
        editor.isHouseEditor -> if (lang == AppLanguage.KO) "하우스 에디터" else "HOUSE EDITOR"
        isPast -> formatPastLabel(editor.activeTo ?: editor.activeFrom, lang)
        else -> if (lang == AppLanguage.KO) "게스트 · 현재" else "GUEST · NOW"
    }
    val nameColor = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md)
            .height(IntrinsicSize.Min),
    ) {
        if (editor.isHouseEditor) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
        Column(
            modifier = Modifier
                .padding(start = if (editor.isHouseEditor) GallrSpacing.md else 0.dp)
                .weight(1f),
        ) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = nameColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = editor.localizedTitle(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (exhibitionCount > 0) {
                Text(
                    text = if (lang == AppLanguage.KO) "전시 ${exhibitionCount}개" else "$exhibitionCount exhibitions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GallrSpacing.sm),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

private fun formatPastLabel(date: kotlinx.datetime.LocalDate, lang: AppLanguage): String {
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return if (lang == AppLanguage.KO) {
        "${date.year}.${date.monthNumber.toString().padStart(2, '0')}"
    } else {
        "${months[date.monthNumber - 1]} ${date.year}"
    }
}
