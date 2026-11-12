package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.CatalogLoadingState
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditorDetailUiState
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorDetailScreen(
    viewModel: EditorDetailViewModel,
    bookmarkedIds: Set<String>,
    onToggleBookmark: (String) -> Unit,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
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
                label = if (lang == AppLanguage.KO) "에디터" else "Editor",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            is EditorDetailUiState.Loading -> {
                CatalogLoadingState(lang = lang, modifier = Modifier.padding(innerPadding).fillMaxSize())
            }

            is EditorDetailUiState.Error -> {
                Box(
                    modifier = Modifier.padding(innerPadding).fillMaxSize().padding(GallrSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    GallrErrorMessage(
                        message =
                            if (lang == AppLanguage.KO) {
                                "에디터 큐레이션을 불러오지 못했습니다."
                            } else {
                                "Could not load this editor’s picks."
                            },
                        actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                        onAction = viewModel::retry,
                    )
                }
            }

            is EditorDetailUiState.Content -> {
                Column(Modifier.padding(innerPadding).fillMaxSize()) {
                    current.editor?.let { ed ->
                        EditorBanner(
                            editor = ed,
                            lang = lang,
                            exhibitionCount = current.exhibitions.size,
                        )
                    }
                    if (current.exhibitions.isEmpty()) {
                        GallrEmptyState(
                            message =
                                if (lang == AppLanguage.KO) {
                                    "선택된 전시가 없습니다"
                                } else {
                                    "No exhibitions in this list"
                                },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = GallrSpacing.md),
                        ) {
                            items(current.exhibitions, key = { it.id }) { exhibition ->
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { onToggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier =
                                        Modifier
                                            .padding(horizontal = GallrSpacing.screenMargin)
                                            .padding(bottom = GallrSpacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
