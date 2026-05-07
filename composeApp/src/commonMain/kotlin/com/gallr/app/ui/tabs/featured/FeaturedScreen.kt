package com.gallr.app.ui.tabs.featured

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.SkeletonCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturedScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.featuredState.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvent by viewModel.activeEvent.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        activeEvent?.let { event ->
            EventPromotionCard(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
                modifier = Modifier.padding(
                    horizontal = GallrSpacing.md,
                    vertical = GallrSpacing.sm,
                ),
            )
        }
        Text(
            text = if (lang == AppLanguage.KO) "추천" else "FEATURED",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                horizontal = GallrSpacing.screenMargin,
                vertical = GallrSpacing.sm,
            ),
        )
        Spacer(Modifier.height(GallrSpacing.sm))

        when (val s = state) {
            is ExhibitionListState.Loading -> {
                Column(modifier = Modifier.padding(horizontal = GallrSpacing.md)) {
                    repeat(3) { SkeletonCard(modifier = Modifier.padding(bottom = GallrSpacing.md)) }
                }
            }

            is ExhibitionListState.Error -> {
                GallrEmptyState(
                    message = if (s.message == "network") {
                        if (lang == AppLanguage.KO) "인터넷 연결을 확인해주세요." else "Check your internet connection."
                    } else {
                        if (lang == AppLanguage.KO) "문제가 발생했습니다. 다시 시도해주세요." else "Something went wrong. Please try again."
                    },
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadFeaturedExhibitions() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is ExhibitionListState.Success -> {
                if (s.exhibitions.isEmpty()) {
                    GallrEmptyState(
                        message = if (lang == AppLanguage.KO) "추천 전시가 없습니다." else "No featured exhibitions right now.",
                        actionLabel = if (lang == AppLanguage.KO) "새로고침" else "Refresh",
                        onAction = { viewModel.loadFeaturedExhibitions() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(GallrSpacing.md),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier
                                        .fillMaxWidth()
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
