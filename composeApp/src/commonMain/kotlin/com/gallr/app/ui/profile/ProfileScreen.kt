package com.gallr.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditProfileViewModel
import com.gallr.app.viewmodel.ProfileViewModel
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.PublicNoteRepository

/**
 * The signed-in profile: avatar, name and the counts. The diary lives in MY → 일기 (spec 103 US2), so the 감상 count
 * opens it with 공개한 감상 selected through [onOpenPublishedNotes]; staff moderate in Admin only (US5 scenario 8).
 */
@Composable
fun ProfileScreen(
    user: GallrUser,
    profileRepository: ProfileRepository,
    publicNoteRepository: PublicNoteRepository,
    tabsViewModel: TabsViewModel,
    lang: AppLanguage,
    onOpenPublishedNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileViewModel: ProfileViewModel =
        viewModel(
            key = "profile-${user.id}",
            factory =
                ProfileViewModel.factory(
                    user = user,
                    profileRepository = profileRepository,
                    publicNoteRepository = publicNoteRepository,
                ),
        )
    val profileUiState by profileViewModel.uiState.collectAsState()
    val profile = profileUiState.profile
    var showEditProfile by remember { mutableStateOf(false) }

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

    val displayName =
        profile?.displayName?.takeIf { it.isNotBlank() }
            ?: user.displayName.takeIf { it.isNotBlank() }
    val bookmarkedIds by tabsViewModel.bookmarkedIds.collectAsState()

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
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .size(width = 80.dp, height = 20.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, GallrShapeTokens.small),
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
        TextButton(onClick = { showEditProfile = true }, shape = MaterialTheme.shapes.small) {
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
                count = profileUiState.publicNoteCount,
                label =
                    when (lang) {
                        AppLanguage.KO -> "감상"
                        AppLanguage.EN -> "Notes"
                    },
                onClick = onOpenPublishedNotes,
            )
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
                Modifier
                    .clip(GallrShapeTokens.small)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs)
            } else {
                Modifier.padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs)
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
