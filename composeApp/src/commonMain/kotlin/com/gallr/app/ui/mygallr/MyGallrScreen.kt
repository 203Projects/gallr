package com.gallr.app.ui.mygallr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.FollowedGalleryUi
import com.gallr.app.viewmodel.MyGallrSection
import com.gallr.app.viewmodel.MyGallrUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.repository.MyGallrSyncStatus
import androidx.compose.foundation.lazy.items as listItems

@Composable
fun MyGallrScreen(
    state: MyGallrUiState,
    onAddVisits: () -> Unit,
    onAddGalleries: () -> Unit,
    onOpenVisit: (ExhibitionVisit) -> Unit,
    onRemoveVisit: (String) -> Unit,
    onUnfollowGallery: (String) -> Unit,
    onOpenGallery: (FollowedGalleryUi) -> Unit,
    onSelectSection: (MyGallrSection) -> Unit,
    showAccountNudge: Boolean,
    isAuthenticated: Boolean,
    syncStatus: MyGallrSyncStatus,
    onRetrySync: () -> Unit,
    onDismissAccountNudge: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<ExhibitionVisit?>(null) }
    var pendingUnfollow by remember { mutableStateOf<FollowedGalleryUi?>(null) }
    val lang = state.language

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MY GALLR",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAccount) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "계정"
                            AppLanguage.EN -> "ACCOUNT"
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ArchiveCount(
                count = state.visits.size,
                label =
                    when (lang) {
                        AppLanguage.KO -> "방문"
                        AppLanguage.EN -> "VISITS"
                    },
            )
            ArchiveCount(
                count = state.followedGalleries.size,
                label =
                    when (lang) {
                        AppLanguage.KO -> "팔로잉"
                        AppLanguage.EN -> "FOLLOWING"
                    },
            )
        }

        Text(
            text =
                syncStatusLabel(
                    lang = lang,
                    isAuthenticated = isAuthenticated,
                    syncStatus = syncStatus,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.sm),
            textAlign = TextAlign.Center,
        )
        if (isAuthenticated && syncStatus == MyGallrSyncStatus.RETRY_NEEDED) {
            TextButton(
                onClick = onRetrySync,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = if (lang == AppLanguage.KO) "백업 다시 시도" else "RETRY BACKUP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            SectionTab(
                label = if (lang == AppLanguage.KO) "방문" else "VISITS",
                selected = state.section == MyGallrSection.VISITS,
                onClick = { onSelectSection(MyGallrSection.VISITS) },
                modifier = Modifier.weight(1f),
            )
            SectionTab(
                label = if (lang == AppLanguage.KO) "팔로잉" else "FOLLOWING",
                selected = state.section == MyGallrSection.FOLLOWING,
                onClick = { onSelectSection(MyGallrSection.FOLLOWING) },
                modifier = Modifier.weight(1f),
            )
        }

        if (showAccountNudge) {
            AccountInvitationCard(
                lang = lang,
                onAccount = onAccount,
                onDismiss = onDismissAccountNudge,
                modifier =
                    Modifier.padding(
                        start = GallrSpacing.screenMargin,
                        top = GallrSpacing.md,
                        end = GallrSpacing.screenMargin,
                    ),
            )
        }

        when (state.section) {
            MyGallrSection.VISITS -> {
                VisitsArchive(
                    state = state,
                    onAddVisits = onAddVisits,
                    onOpenVisit = onOpenVisit,
                    onRemoveVisit = { visit -> pendingRemoval = visit },
                    modifier = Modifier.weight(1f),
                )
            }

            MyGallrSection.FOLLOWING -> {
                FollowingArchive(
                    state = state,
                    onAddGalleries = onAddGalleries,
                    onOpenGallery = onOpenGallery,
                    onUnfollowGallery = { followed -> pendingUnfollow = followed },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pendingRemoval?.let { visit ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = {
                Text(
                    if (lang == AppLanguage.KO) {
                        "방문 기록을 삭제할까요?"
                    } else {
                        "REMOVE THIS VISIT?"
                    },
                )
            },
            text = { Text(visit.snapshot.localizedName(lang)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onRemoveVisit(visit.exhibitionId)
                    },
                ) {
                    Text(if (lang == AppLanguage.KO) "삭제" else "REMOVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(if (lang == AppLanguage.KO) "취소" else "CANCEL")
                }
            },
            shape = RectangleShape,
        )
    }

    pendingUnfollow?.let { followed ->
        AlertDialog(
            onDismissRequest = { pendingUnfollow = null },
            title = {
                Text(
                    if (lang == AppLanguage.KO) {
                        "이 갤러리를 목록에서 삭제할까요?"
                    } else {
                        "UNFOLLOW THIS GALLERY?"
                    },
                )
            },
            text = { Text(followed.record.snapshot.localizedName(lang)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUnfollow = null
                        onUnfollowGallery(followed.record.galleryKey)
                    },
                ) {
                    Text(if (lang == AppLanguage.KO) "삭제" else "UNFOLLOW")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnfollow = null }) {
                    Text(if (lang == AppLanguage.KO) "취소" else "CANCEL")
                }
            },
            shape = RectangleShape,
        )
    }
}

@Composable
private fun AccountInvitationCard(
    lang: AppLanguage,
    onAccount: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .padding(GallrSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text =
                    if (lang == AppLanguage.KO) {
                        "MY GALLR를 더 나답게."
                    } else {
                        "MAKE YOUR GALLR YOURS."
                    },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (lang == AppLanguage.KO) "나중에" else "NOT NOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(GallrSpacing.sm))
        Text(
            text =
                if (lang == AppLanguage.KO) {
                    "계정을 만들면 방문 기록과 팔로우한 갤러리를 백업하고 다른 기기에서도 복원할 수 있어요. " +
                        "찜한 전시 동기화, 전시 일기와 프로필도 함께 이용해 보세요."
                } else {
                    "Create an account to back up visits and followed galleries and restore them " +
                        "on another device. You’ll also get bookmark sync, your exhibition diary, and profile."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Button(
            onClick = onAccount,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = GallrAccent.ctaPrimary,
                    contentColor = GallrAccent.ctaContent,
                ),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "MY GALLR 백업" else "BACK UP MY GALLR",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun syncStatusLabel(
    lang: AppLanguage,
    isAuthenticated: Boolean,
    syncStatus: MyGallrSyncStatus,
): String {
    if (!isAuthenticated) {
        return if (lang == AppLanguage.KO) {
            "방문 기록과 팔로잉은 이 기기에 저장됩니다"
        } else {
            "VISITS AND FOLLOWING ARE SAVED ON THIS DEVICE"
        }
    }
    return when (syncStatus) {
        MyGallrSyncStatus.SYNCING -> {
            if (lang == AppLanguage.KO) "MY GALLR 백업 중…" else "BACKING UP MY GALLR…"
        }

        MyGallrSyncStatus.SYNCED -> {
            if (lang == AppLanguage.KO) "계정에 백업됨" else "BACKED UP TO YOUR ACCOUNT"
        }

        MyGallrSyncStatus.RETRY_NEEDED -> {
            if (lang == AppLanguage.KO) "이 기기에 저장됨 · 백업 재시도 필요" else "SAVED HERE · BACKUP NEEDS RETRY"
        }

        MyGallrSyncStatus.DEVICE_ONLY -> {
            if (lang == AppLanguage.KO) "계정 백업 준비 중" else "ACCOUNT BACKUP STARTING"
        }
    }
}

@Composable
private fun ArchiveCount(
    count: Int,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = GallrSpacing.sm),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        if (selected) {
                            GallrAccent.activeIndicator
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
        )
    }
}

@Composable
private fun VisitsArchive(
    state: MyGallrUiState,
    onAddVisits: () -> Unit,
    onOpenVisit: (ExhibitionVisit) -> Unit,
    onRemoveVisit: (ExhibitionVisit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = state.language
    when {
        state.isLoading -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            }
        }

        state.loadFailed -> {
            GallrErrorMessage(
                message =
                    if (lang == AppLanguage.KO) {
                        "방문 기록을 불러오지 못했습니다."
                    } else {
                        "Couldn’t load your visit archive."
                    },
                modifier = modifier.padding(GallrSpacing.screenMargin),
            )
        }

        state.visits.isEmpty() -> {
            EmptyVisits(
                lang = lang,
                onAddVisits = onAddVisits,
                modifier = modifier,
            )
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(GallrSpacing.screenMargin),
                horizontalArrangement = Arrangement.spacedBy(GallrSpacing.gutterWidth),
                verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(
                        onClick = onAddVisits,
                        shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text(
                            text = if (lang == AppLanguage.KO) "+ 지난 전시 추가" else "+ ADD PAST VISITS",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                items(state.visits, key = { it.clientRecordId }) { visit ->
                    VisitCard(
                        visit = visit,
                        lang = lang,
                        onOpen = { onOpenVisit(visit) },
                        onRemove = { onRemoveVisit(visit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVisits(
    lang: AppLanguage,
    onAddVisits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = GallrSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text =
                if (lang == AppLanguage.KO) {
                    "당신의 예술 생활을\n이곳에 보관하세요."
                } else {
                    "YOUR ART LIFE,\nKEPT HERE."
                },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Text(
            text =
                if (lang == AppLanguage.KO) {
                    "방문한 전시를 기록하고 나만의 예술 생활을 차곡차곡 모아보세요."
                } else {
                    "Archive exhibitions you visit and build a private record of your art life."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GallrSpacing.lg))
        Button(
            onClick = onAddVisits,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = GallrAccent.ctaPrimary,
                    contentColor = GallrAccent.ctaContent,
                ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "지난 전시 추가" else "ADD PAST VISITS",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun VisitCard(
    visit: ExhibitionVisit,
    lang: AppLanguage,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .clickable(onClick = onOpen),
    ) {
        val imageUrl = visit.snapshot.coverImageUrl
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            AsyncImage(
                model = nativeSupabaseImageUrl(imageUrl),
                contentDescription = visit.snapshot.localizedName(lang),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            )
        }
        Column(modifier = Modifier.padding(GallrSpacing.sm)) {
            Text(
                text = visit.snapshot.localizedName(lang),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(GallrSpacing.xs))
            Text(
                text = visit.snapshot.localizedVenueName(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = visit.snapshot.localizedDateRange(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = GallrSpacing.sm),
            )
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) {
                Text(
                    text = if (lang == AppLanguage.KO) "삭제" else "REMOVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FollowingArchive(
    state: MyGallrUiState,
    onAddGalleries: () -> Unit,
    onOpenGallery: (FollowedGalleryUi) -> Unit,
    onUnfollowGallery: (FollowedGalleryUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = state.language
    when {
        state.followingLoadFailed -> {
            GallrErrorMessage(
                message =
                    if (lang == AppLanguage.KO) {
                        "팔로우한 갤러리를 불러오지 못했습니다."
                    } else {
                        "Couldn’t load followed galleries."
                    },
                modifier = modifier.padding(GallrSpacing.screenMargin),
            )
        }

        state.followedGalleries.isEmpty() -> {
            EmptyFollowing(lang = lang, onAddGalleries = onAddGalleries, modifier = modifier)
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(GallrSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(GallrSpacing.md),
            ) {
                item {
                    OutlinedButton(
                        onClick = onAddGalleries,
                        shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text(
                            text = if (lang == AppLanguage.KO) "+ 갤러리 추가" else "+ ADD GALLERIES",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                item {
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "새 전시는 여기에서 확인하고 · 갤러리를 눌러 알림을 설정하세요"
                            } else {
                                "SEE NEW EXHIBITIONS HERE · TAP A GALLERY TO MANAGE ALERTS"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                listItems(state.followedGalleries, key = { it.record.galleryKey }) { followed ->
                    FollowedGalleryRow(
                        followed = followed,
                        lang = lang,
                        onOpen = { onOpenGallery(followed) },
                        onUnfollow = { onUnfollowGallery(followed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFollowing(
    lang: AppLanguage,
    onAddGalleries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = GallrSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (lang == AppLanguage.KO) "좋아하는 갤러리를\n계속 지켜보세요." else "KEEP UP WITH\nGALLERIES YOU LIKE.",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(GallrSpacing.md))
        Text(
            text =
                if (lang == AppLanguage.KO) {
                    "새 전시가 카탈로그에 추가되면 MY GALLR에서 표시해 드립니다."
                } else {
                    "When a new exhibition enters the catalogue, it will be marked here in My Gallr."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GallrSpacing.lg))
        Button(
            onClick = onAddGalleries,
            shape = RectangleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = GallrAccent.ctaPrimary,
                    contentColor = GallrAccent.ctaContent,
                ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "갤러리 추가" else "ADD GALLERIES",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun FollowedGalleryRow(
    followed: FollowedGalleryUi,
    lang: AppLanguage,
    onOpen: () -> Unit,
    onUnfollow: () -> Unit,
) {
    val snapshot = followed.snapshot
    val latest = followed.latestRelevantExhibition
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .clickable(enabled = latest != null, onClick = onOpen)
                .padding(GallrSpacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
        ) {
            GalleryMonogram(name = snapshot.localizedName(lang))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snapshot.localizedName(lang),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (followed.unseenExhibitions.isNotEmpty()) {
                        Text(
                            text = "NEW ${followed.unseenExhibitions.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GallrAccent.activeIndicator,
                        )
                    }
                }
                Text(
                    text = snapshot.localizedLocation(lang),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (latest != null) {
            Spacer(Modifier.height(GallrSpacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(GallrSpacing.sm))
            Text(
                text = latest.localizedName(lang),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = latest.localizedDateRange(lang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnfollow, modifier = Modifier.align(Alignment.End)) {
            Text(
                text = if (lang == AppLanguage.KO) "삭제" else "UNFOLLOW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
