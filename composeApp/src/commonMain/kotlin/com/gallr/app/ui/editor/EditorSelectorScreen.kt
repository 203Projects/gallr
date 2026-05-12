package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditorSelectorState
import com.gallr.app.viewmodel.EditorSelectorViewModel
import com.gallr.shared.data.model.AppLanguage

@Composable
fun EditorSelectorScreen(
    viewModel: EditorSelectorViewModel,
    onBack: () -> Unit,
    onEditorTap: (editorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val lang by viewModel.language.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(
            label = if (lang == AppLanguage.KO) "에디터" else "Editors",
            onBack = onBack,
        )
        when (val s = state) {
            is EditorSelectorState.Loading -> Unit
            is EditorSelectorState.Error -> {
                GallrEmptyState(
                    message = if (lang == AppLanguage.KO) "에디터를 불러오지 못했습니다."
                              else "Could not load editors.",
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadEditors() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is EditorSelectorState.Success -> {
                LazyColumn {
                    item {
                        Text(
                            text = if (lang == AppLanguage.KO) "현재 큐레이션" else "Currently curating",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = GallrSpacing.screenMargin,
                                top = GallrSpacing.lg,
                                bottom = GallrSpacing.sm,
                            ),
                        )
                    }
                    items(s.active, key = { it.id }) { editor ->
                        EditorTile(
                            editor = editor,
                            lang = lang,
                            exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                            isPast = false,
                            onClick = { onEditorTap(editor.id) },
                        )
                    }
                    if (s.past.isNotEmpty()) {
                        item {
                            Text(
                                text = if (lang == AppLanguage.KO) "지난 에디터" else "Past editors",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = GallrSpacing.screenMargin,
                                    top = GallrSpacing.lg,
                                    bottom = GallrSpacing.sm,
                                ),
                            )
                        }
                        items(s.past, key = { it.id }) { editor ->
                            EditorTile(
                                editor = editor,
                                lang = lang,
                                exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                                isPast = true,
                                onClick = { onEditorTap(editor.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
