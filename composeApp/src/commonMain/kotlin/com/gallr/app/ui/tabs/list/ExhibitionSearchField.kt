package com.gallr.app.ui.tabs.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage

/**
 * The List tab's exhibition search bar. It owns the text being edited and reports each change.
 *
 * The screen's copy of the query comes back through TabsViewModel's combined state a frame late.
 * A text field bound to that copy is redrawn with the previous text first, which moves the caret
 * to the start: with Korean input every later syllable landed in front of the first. Seed it from
 * the view model's own query, which is current when the field enters the screen again.
 */
@Composable
internal fun ExhibitionSearchField(
    initialQuery: String,
    onQueryChange: (String) -> Unit,
    lang: AppLanguage,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initialQuery) }
    val updateText: (String) -> Unit = { value ->
        text = value
        onQueryChange(value)
    }
    TextField(
        value = text,
        onValueChange = updateText,
        placeholder = {
            Text(
                text = if (lang == AppLanguage.KO) "전시 검색..." else "Search exhibitions...",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { updateText("") }) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "⌕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = GallrSpacing.sm),
                )
            }
        },
        singleLine = true,
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = MaterialTheme.typography.labelLarge,
        shape = MaterialTheme.shapes.small,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                focusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = 48.dp)
                .padding(horizontal = GallrSpacing.screenMargin),
    )
}
