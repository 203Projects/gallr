package com.gallr.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditProfileViewModel
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.MyThoughtsViewModel
import com.gallr.app.viewmodel.PendingThoughtsViewModel
import com.gallr.app.viewmodel.ProfileViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository

@Composable
fun ProfileScreen(
    user: GallrUser,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (Exhibition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profileViewModel: ProfileViewModel =
        viewModel(
            key = "profile-${user.id}",
            factory =
                ProfileViewModel.factory(
                    user = user,
                    profileRepository = profileRepository,
                    thoughtRepository = thoughtRepository,
                ),
        )
    val profileUiState by profileViewModel.uiState.collectAsState()
    val profile = profileUiState.profile
    var showMyThoughts by remember { mutableStateOf(false) }
    var showPendingThoughts by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }

    // Show Edit Profile screen
    if (showEditProfile) {
        val editProfileViewModel: EditProfileViewModel =
            viewModel(
                key = "edit-profile-${user.id}",
                factory =
                    EditProfileViewModel.factory(
                        user = user,
                        profile = profile,
                        profileRepository = profileRepository,
                    ),
            )
        EditProfileScreen(
            viewModel = editProfileViewModel,
            lang = lang,
            onBack = {
                showEditProfile = false
                profileViewModel.refresh()
            },
        )
        return
    }

    // Show My Thoughts screen
    if (showMyThoughts) {
        val myThoughtsViewModel: MyThoughtsViewModel =
            viewModel(
                key = "my-thoughts-${user.id}",
                factory =
                    MyThoughtsViewModel.factory(
                        userId = user.id,
                        thoughtRepository = thoughtRepository,
                    ),
            )
        MyThoughtsScreen(
            viewModel = myThoughtsViewModel,
            lang = lang,
            onBack = {
                showMyThoughts = false
                profileViewModel.refresh()
            },
        )
        return
    }

    // Show Pending Thoughts screen (admin only)
    if (showPendingThoughts) {
        val pendingThoughtsViewModel: PendingThoughtsViewModel =
            viewModel(
                key = "pending-thoughts",
                factory = PendingThoughtsViewModel.factory(thoughtRepository),
            )
        val allExhibitionsState by tabsViewModel.filteredExhibitions.collectAsState()
        val allExhibitions = (allExhibitionsState as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
        val closePendingThoughts = {
            showPendingThoughts = false
            profileViewModel.refresh()
        }
        PlatformBackHandler { closePendingThoughts() }
        PendingThoughtsScreen(
            viewModel = pendingThoughtsViewModel,
            exhibitions = allExhibitions,
            lang = lang,
            onBack = closePendingThoughts,
        )
        return
    }

    val displayName =
        profile?.displayName?.takeIf { it.isNotBlank() }
            ?: user.displayName.takeIf { it.isNotBlank() }

    // Get exhibitions with thoughts for the diary
    val bookmarkedIds by tabsViewModel.bookmarkedIds.collectAsState()
    val allExhibitionsState by tabsViewModel.filteredExhibitions.collectAsState()
    val diaryExhibitions =
        remember(allExhibitionsState, profileUiState.thoughtExhibitionIds) {
            val allExhibitions = (allExhibitionsState as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
            allExhibitions.filter { it.id in profileUiState.thoughtExhibitionIds }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        if (profileUiState.loadFailed) {
            GallrErrorMessage(
                message =
                    when (lang) {
                        AppLanguage.KO -> "프로필 정보를 불러오지 못했습니다."
                        AppLanguage.EN -> "Couldn’t load your profile."
                    },
                actionLabel =
                    when (lang) {
                        AppLanguage.KO -> "다시 시도"
                        AppLanguage.EN -> "Retry"
                    },
                onAction = profileViewModel::refresh,
            )
            Spacer(Modifier.height(GallrSpacing.lg))
        }

        // Avatar — show skeleton while loading to avoid flash of default content
        if (profileUiState.isLoading && !profileUiState.hasLoaded) {
            // Skeleton avatar
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(12.dp))
            // Skeleton name
            Box(
                modifier =
                    Modifier
                        .size(width = 80.dp, height = 20.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            )
        } else {
            val avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: user.avatarUrl
            val initial = (displayName ?: "?").first().uppercase()
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    displayName ?: when (lang) {
                        AppLanguage.KO -> "이름 없음"
                        AppLanguage.EN -> "No name"
                    },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showEditProfile = true }) {
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> "프로필 수정"
                        AppLanguage.EN -> "Edit Profile"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Stats row ──────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            StatItem(
                count = bookmarkedIds.size,
                label =
                    when (lang) {
                        AppLanguage.KO -> "북마크"
                        AppLanguage.EN -> "Bookmarked"
                    },
            )
            Spacer(Modifier.width(32.dp))
            StatItem(
                count = profileUiState.thoughtCount,
                label =
                    when (lang) {
                        AppLanguage.KO -> "감상"
                        AppLanguage.EN -> "Thoughts"
                    },
                // Tapping the 감상 stat opens the full My Thoughts list — a direct
                // entry point for the otherwise-unwired showMyThoughts screen.
                onClick = { showMyThoughts = true },
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Exhibition Diary section title ──────────────────────────────
        Text(
            text =
                when (lang) {
                    AppLanguage.KO -> "전시 일기"
                    AppLanguage.EN -> "EXHIBITION DIARY"
                },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        if (diaryExhibitions.isEmpty() && profileUiState.hasLoaded) {
            // Empty diary state
            Spacer(Modifier.height(16.dp))
            Text(
                text =
                    when (lang) {
                        AppLanguage.KO -> {
                            "전시 일기가 비어있어요.\n전시에 감상을 남겨보세요."
                        }

                        AppLanguage.EN -> {
                            "Your exhibition diary is empty.\n" +
                                "Share your thoughts on an exhibition to start."
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Exhibition diary grid (2 columns)
            val rows = diaryExhibitions.chunked(2)
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { exhibition ->
                        DiaryCard(
                            exhibition = exhibition,
                            lang = lang,
                            hasThought = true,
                            onClick = { onExhibitionTap(exhibition) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Fill empty space if odd number
                    if (rowItems.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Admin section (visible only to admins)
        if (profile?.isAdmin == true) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Admin",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            val pendingCountSuffix =
                profileUiState.pendingCount
                    .takeIf { it > 0 }
                    ?.let { " ($it)" }
                    .orEmpty()
            OutlinedButton(
                onClick = { showPendingThoughts = true },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RectangleShape,
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "검토 대기$pendingCountSuffix"
                            AppLanguage.EN -> "Pending Reviews$pendingCountSuffix"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Stat item ────────────────────────────────────────────────────────────────

@Composable
private fun StatItem(
    count: Int,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            },
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Diary card ───────────────────────────────────────────────────────────────

@Composable
private fun DiaryCard(
    exhibition: Exhibition,
    lang: AppLanguage,
    hasThought: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .clickable { onClick() }
                .padding(bottom = 12.dp),
    ) {
        // Cover image
        val imageUrl = exhibition.coverImageUrl
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = exhibition.localizedName(lang),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = exhibition.localizedName(lang),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = exhibition.localizedVenueName(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasThought) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "💭 ${when (lang) {
                        AppLanguage.KO -> "감상 작성됨"
                        AppLanguage.EN -> "Thought written"
                    }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
