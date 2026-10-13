package com.gallr.app.ui.tabs.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.home.HomeCollection
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource

/** A themed collection opened from the home tab: its headline, what gathers it, and its exhibitions as cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    collection: HomeCollection,
    lang: AppLanguage,
    bookmarkedIds: Set<String>,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition, Int) -> Unit,
    onBookmarkToggle: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = collection.localizedTitle(lang),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = GallrSpacing.screenMargin,
                    end = GallrSpacing.screenMargin,
                    top = GallrSpacing.sm,
                    bottom = GallrSpacing.xl,
                ),
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            item(key = "collection-subtitle") {
                Text(
                    text = collectionSubtitle(collection, lang),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = GallrSpacing.md),
                )
            }
            items(collection.exhibitions.withIndex().toList(), key = { it.value.id }) { (index, exhibition) ->
                ExhibitionCard(
                    exhibition = exhibition,
                    isBookmarked = exhibition.id in bookmarkedIds,
                    onBookmarkToggle = { onBookmarkToggle(exhibition) },
                    onTap = { onExhibitionTap(exhibition, index) },
                    lang = lang,
                    modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.md),
                )
            }
        }
    }
}
