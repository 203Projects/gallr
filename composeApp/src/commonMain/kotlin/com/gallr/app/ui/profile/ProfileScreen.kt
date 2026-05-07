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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import com.gallr.app.PlatformBackHandler
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import com.gallr.shared.data.network.dto.ThoughtDto
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    user: GallrUser,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    supabaseClient: SupabaseClient,
    viewModel: TabsViewModel,
    lang: AppLanguage,
    onExhibitionTap: (Exhibition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMyThoughts by remember { mutableStateOf(false) }
    var showPendingThoughts by remember { mutableStateOf(false) }
    var isProfileLoading by remember { mutableStateOf(true) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var thoughtExhibitionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var thoughtCount by remember { mutableStateOf(0) }
    var pendingCount by remember { mutableStateOf(0) }

    var showEditProfile by remember { mutableStateOf(false) }

    // Show Edit Profile screen
    if (showEditProfile) {
        EditProfileScreen(
            user = user,
            profile = profile,
            profileRepository = profileRepository,
            lang = lang,
            onBack = {
                showEditProfile = false
                // Refresh profile after edit
                val userId = user.id.takeIf { it.isNotBlank() }
                if (userId != null) {
                    scope.launch {
                        try { profile = profileRepository.getProfile(userId) } catch (_: Exception) {}
                    }
                }
            },
        )
        return
    }

    // Show My Thoughts screen
    if (showMyThoughts) {
        MyThoughtsScreen(
            thoughtRepository = thoughtRepository,
            supabaseClient = supabaseClient,
            lang = lang,
            onBack = { showMyThoughts = false },
        )
        return
    }

    // Show Pending Thoughts screen (admin only)
    if (showPendingThoughts) {
        val allExhibitionsState by viewModel.filteredExhibitions.collectAsState()
        val allExhibitions = (allExhibitionsState as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
        PlatformBackHandler {
            showPendingThoughts = false
            scope.launch {
                try { pendingCount = thoughtRepository.getPendingThoughts().size } catch (_: Exception) {}
            }
        }
        PendingThoughtsScreen(
            thoughtRepository = thoughtRepository,
            exhibitions = allExhibitions,
            lang = lang,
            onBack = {
                showPendingThoughts = false
                scope.launch {
                    try { pendingCount = thoughtRepository.getPendingThoughts().size } catch (_: Exception) {}
                }
            },
        )
        return
    }

    // Fetch profile + user's thought exhibition IDs
    LaunchedEffect(user.id) {
        isProfileLoading = true
        val userId = user.id.takeIf { it.isNotBlank() }
            ?: try { supabaseClient.auth.retrieveUserForCurrentSession()?.id } catch (_: Exception) { null }
        if (userId != null) {
            try { profile = profileRepository.getProfile(userId) } catch (_: Exception) {}
            try {
                val userThoughts = supabaseClient.postgrest
                    .from("thoughts")
                    .select { filter { eq("user_id", userId) } }
                    .decodeList<ThoughtDto>()
                thoughtExhibitionIds = userThoughts.map { it.exhibitionId }.toSet()
                thoughtCount = userThoughts.size
            } catch (_: Exception) {}
            // Fetch pending count for admin
            val p = profile
            if (p?.isAdmin == true) {
                try { pendingCount = thoughtRepository.getPendingThoughts().size } catch (_: Exception) {}
            }
        }
        isProfileLoading = false
    }

    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: user.displayName.takeIf { it.isNotBlank() }

    // Get exhibitions with thoughts for the diary
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val allExhibitionsState by viewModel.filteredExhibitions.collectAsState()
    val diaryExhibitions = remember(allExhibitionsState, thoughtExhibitionIds) {
        val allExhibitions = (allExhibitionsState as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
        allExhibitions.filter { it.id in thoughtExhibitionIds }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GallrSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        // Avatar — show skeleton while loading to avoid flash of default content
        if (isProfileLoading) {
            // Skeleton avatar
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(12.dp))
            // Skeleton name
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 20.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            )
        } else {
            val avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: user.avatarUrl
            val initial = (displayName ?: "?").first().uppercase()
            Box(
                modifier = Modifier
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
                text = displayName ?: when (lang) {
                    AppLanguage.KO -> "이름 없음"
                    AppLanguage.EN -> "No name"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showEditProfile = true }) {
            Text(
                text = when (lang) {
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
                label = when (lang) {
                    AppLanguage.KO -> "북마크"
                    AppLanguage.EN -> "Bookmarked"
                },
            )
            Spacer(Modifier.width(32.dp))
            StatItem(
                count = thoughtCount,
                label = when (lang) {
                    AppLanguage.KO -> "감상"
                    AppLanguage.EN -> "Thoughts"
                },
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Exhibition Diary section title ──────────────────────────────
        Text(
            text = when (lang) {
                AppLanguage.KO -> "전시 일기"
                AppLanguage.EN -> "EXHIBITION DIARY"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        if (diaryExhibitions.isEmpty()) {
            // Empty diary state
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "전시 일기가 비어있어요.\n전시에 감상을 남겨보세요."
                    AppLanguage.EN -> "Your exhibition diary is empty.\nShare your thoughts on an exhibition to start."
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

            OutlinedButton(
                onClick = { showPendingThoughts = true },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RectangleShape,
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "검토 대기 ${if (pendingCount > 0) "($pendingCount)" else ""}"
                        AppLanguage.EN -> "Pending Reviews ${if (pendingCount > 0) "($pendingCount)" else ""}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        // Settings section
        Text(
            text = when (lang) {
                AppLanguage.KO -> "설정"
                AppLanguage.EN -> "SETTINGS"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Logout
        OutlinedButton(
            onClick = { scope.launch { authRepository.signOut() } },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RectangleShape,
        ) {
            Text(
                text = when (lang) {
                    AppLanguage.KO -> "로그아웃"
                    AppLanguage.EN -> "Sign Out"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Delete Account
        if (showDeleteConfirm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "계정을 삭제하시겠습니까? 모든 북마크와 감상이 영구적으로 삭제됩니다."
                        AppLanguage.EN -> "Delete your account? All bookmarks and thoughts will be permanently deleted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = false },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RectangleShape,
                    ) {
                        Text(
                            when (lang) {
                                AppLanguage.KO -> "취소"
                                AppLanguage.EN -> "Cancel"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                authRepository.deleteAccount()
                                showDeleteConfirm = false
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(
                            when (lang) {
                                AppLanguage.KO -> "삭제"
                                AppLanguage.EN -> "Delete"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "계정 삭제"
                        AppLanguage.EN -> "Delete Account"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Stat item ────────────────────────────────────────────────────────────────

@Composable
private fun StatItem(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        modifier = modifier
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
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )
        } else {
            Box(
                modifier = Modifier
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
