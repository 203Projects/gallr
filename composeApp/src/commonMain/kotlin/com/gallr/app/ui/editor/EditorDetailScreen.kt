package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

@Composable
fun EditorDetailScreen(
    viewModel: EditorDetailViewModel,
    bookmarkedIds: Set<String>,
    onToggleBookmark: (String) -> Unit,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor by viewModel.editor.collectAsState()
    val exhibitions by viewModel.exhibitions.collectAsState()
    val lang by viewModel.language.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(
            label = if (lang == AppLanguage.KO) "에디터" else "Editor",
            onBack = onBack,
        )
        editor?.let { ed ->
            EditorBanner(
                editor = ed,
                lang = lang,
                exhibitionCount = exhibitions.size,
            )
        }
        if (exhibitions.isEmpty()) {
            GallrEmptyState(
                message = if (lang == AppLanguage.KO) "선택된 전시가 없습니다"
                          else "No exhibitions in this list",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn {
                items(exhibitions, key = { it.id }) { exhibition ->
                    ExhibitionCard(
                        exhibition = exhibition,
                        isBookmarked = exhibition.id in bookmarkedIds,
                        onBookmarkToggle = { onToggleBookmark(exhibition.id) },
                        onTap = { onExhibitionTap(exhibition) },
                        lang = lang,
                    )
                }
            }
        }
    }
}
