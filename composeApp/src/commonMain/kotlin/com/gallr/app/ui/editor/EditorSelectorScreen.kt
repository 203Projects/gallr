package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSelectorScreen(
    viewModel: EditorSelectorViewModel,
    onBack: () -> Unit,
    onEditorTap: (editorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val lang by viewModel.language.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            EditorTopBar(
                label = if (lang == AppLanguage.KO) "에디터" else "Editors",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is EditorSelectorState.Loading -> {
                Box(Modifier.padding(innerPadding).fillMaxSize())
            }

            is EditorSelectorState.Error -> {
                GallrEmptyState(
                    message =
                        if (lang == AppLanguage.KO) {
                            "에디터 큐레이션을 불러오지 못했습니다."
                        } else {
                            "Could not load editor curations."
                        },
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.retry() },
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                )
            }

            is EditorSelectorState.Success -> {
                if (s.active.isEmpty() && s.past.isEmpty()) {
                    GallrEmptyState(
                        message =
                            if (lang == AppLanguage.KO) {
                                "현재 볼 수 있는 에디터 큐레이션이 없습니다."
                            } else {
                                "No editor curations are currently available."
                            },
                        modifier = Modifier.padding(innerPadding).fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).fillMaxSize(),
                        contentPadding = PaddingValues(bottom = GallrSpacing.md),
                    ) {
                        if (s.active.isNotEmpty()) {
                            item {
                                Text(
                                    text = if (lang == AppLanguage.KO) "현재 큐레이션" else "Currently curating",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(
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
                        }
                        if (s.past.isNotEmpty()) {
                            item {
                                Text(
                                    text = if (lang == AppLanguage.KO) "지난 에디터" else "Past editors",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(
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
}
