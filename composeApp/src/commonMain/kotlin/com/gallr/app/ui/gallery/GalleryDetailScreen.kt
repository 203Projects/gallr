package com.gallr.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.GalleryDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_arrow_back
import gallr.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    viewModel: GalleryDetailViewModel,
    lang: AppLanguage,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val current = state.currentExhibition
    var showFullProgramme by remember(state.galleryKey) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(Res.drawable.logo),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            )
                            Spacer(Modifier.size(GallrSpacing.sm))
                            Text("gallr", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_arrow_back),
                                contentDescription = if (lang == AppLanguage.KO) "뒤로" else "Back",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = GallrSpacing.screenMargin),
            ) {
                Spacer(Modifier.height(GallrSpacing.md))
                Text(
                    text = state.snapshot.localizedName(lang),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(GallrSpacing.xs))
                Text(
                    text = state.snapshot.localizedLocation(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(GallrSpacing.md))

                if (state.visitedExhibitions.isNotEmpty()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                                .padding(GallrSpacing.md),
                    ) {
                        Text(
                            text =
                                if (lang == AppLanguage.KO) {
                                    "이곳에서 ${state.visitedExhibitions.size}개 전시를 방문했어요"
                                } else {
                                    "YOU’VE VISITED ${state.visitedExhibitions.size} EXHIBITIONS HERE"
                                },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        state.visitedExhibitions.take(3).forEach { visit ->
                            Spacer(Modifier.height(GallrSpacing.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val visitImage = visit.snapshot.coverImageUrl
                                if (!visitImage.isNullOrBlank()) {
                                    AsyncImage(
                                        model = nativeSupabaseImageUrl(visitImage),
                                        contentDescription = visit.snapshot.localizedName(lang),
                                        contentScale = ContentScale.Crop,
                                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                                        modifier = Modifier.width(72.dp).height(52.dp),
                                    )
                                    Spacer(Modifier.width(GallrSpacing.sm))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = visit.snapshot.localizedName(lang).uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Spacer(Modifier.height(GallrSpacing.xs))
                                    Text(
                                        text = visit.snapshot.localizedDateRange(lang).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(GallrSpacing.md))
                }

                val imageUrl = current?.coverImageUrl
                if (imageUrl.isNullOrBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(8f / 5f)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape),
                    )
                } else {
                    AsyncImage(
                        model = nativeSupabaseImageUrl(imageUrl),
                        contentDescription = current.localizedName(lang),
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().aspectRatio(8f / 5f),
                    )
                }

                Spacer(Modifier.height(GallrSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
                ) {
                    OutlinedButton(
                        onClick = viewModel::toggleFollow,
                        enabled = !state.isSaving,
                        shape = RectangleShape,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        if (state.isFollowing) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(GallrSpacing.sm))
                        }
                        Text(
                            if (state.isFollowing) {
                                if (lang == AppLanguage.KO) "팔로잉" else "FOLLOWING"
                            } else {
                                if (lang == AppLanguage.KO) "팔로우" else "FOLLOW"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::openAlertRationale,
                        enabled = !state.isSaving,
                        shape = RectangleShape,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor =
                                    if (state.newExhibitionAlertsEnabled) {
                                        GallrAccent.activeIndicator
                                    } else {
                                        MaterialTheme.colorScheme.onBackground
                                    },
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsNone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(GallrSpacing.sm))
                        Text(
                            if (state.newExhibitionAlertsEnabled) {
                                if (lang == AppLanguage.KO) "알림 켜짐" else "ALERTS ON"
                            } else {
                                if (lang == AppLanguage.KO) "새 전시 알림" else "NEW EXHIBITION ALERT"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (state.saveFailed) {
                    GallrErrorMessage(
                        message =
                            if (lang == AppLanguage.KO) {
                                "변경 내용을 저장하지 못했습니다. 다시 시도해 주세요."
                            } else {
                                "Couldn’t save your change. Please try again."
                            },
                        modifier = Modifier.padding(top = GallrSpacing.md),
                    )
                }

                current?.let { exhibition ->
                    Spacer(Modifier.height(GallrSpacing.xl))
                    Text(
                        text = if (lang == AppLanguage.KO) "현재 전시" else "CURRENT EXHIBITION",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(GallrSpacing.sm))
                    Text(
                        text = exhibition.localizedName(lang),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))
                    Text(
                        text = exhibition.localizedDateRange(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(GallrSpacing.lg))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(GallrSpacing.lg))
                }

                if (state.exhibitions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showFullProgramme = !showFullProgramme },
                        shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            text =
                                if (showFullProgramme) {
                                    if (lang == AppLanguage.KO) "프로그램 닫기" else "HIDE FULL PROGRAMME"
                                } else {
                                    if (lang == AppLanguage.KO) "전체 프로그램 보기" else "VIEW FULL PROGRAMME"
                                },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (showFullProgramme) {
                        Spacer(Modifier.height(GallrSpacing.md))
                        state.exhibitions.forEach { exhibition ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onExhibitionTap(exhibition) }
                                        .padding(vertical = GallrSpacing.sm),
                            ) {
                                Text(
                                    text = exhibition.localizedName(lang).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(GallrSpacing.xs))
                                Text(
                                    text = exhibition.localizedDateRange(lang).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Spacer(Modifier.height(GallrSpacing.xl))
                }
            }
        }

        if (state.showAlertRationale) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.44f))
                        .clickable(onClick = viewModel::dismissAlertRationale),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {},
                            ).navigationBarsPadding()
                            .padding(GallrSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 3.dp, height = 22.dp)
                                    .border(3.dp, GallrAccent.activeIndicator, RectangleShape),
                        )
                        Spacer(Modifier.size(GallrSpacing.sm))
                        Text(
                            text = if (lang == AppLanguage.KO) "새 전시 알림" else "NEW EXHIBITION ALERT",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.height(GallrSpacing.md))
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "${state.snapshot.localizedName(lang)}의 다음 전시를\n놓치지 마세요."
                            } else {
                                "DON’T MISS THE NEXT EXHIBITION\nAT ${state.snapshot.localizedName(lang).uppercase()}."
                            },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "새 전시가 공개될 때 이 기기로 한 번 알려드려요.\n" +
                                    "팔로우와 방문 기록은 로그인 없이 그대로 사용할 수 있어요."
                            } else {
                                "Get one alert on this device when a new exhibition is published.\n" +
                                    "Following and visits continue to work without signing in."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                                .padding(horizontal = GallrSpacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsNone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(GallrSpacing.md))
                        Text(
                            text =
                                if (lang == AppLanguage.KO) {
                                    "이 기기에서만 알림"
                                } else {
                                    "ALERT ON THIS DEVICE ONLY"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.permissionDenied) {
                        Spacer(Modifier.height(GallrSpacing.md))
                        GallrErrorMessage(
                            message =
                                if (lang == AppLanguage.KO) {
                                    "기기 알림이 꺼져 있습니다. 설정에서 Gallr 알림을 허용해 주세요."
                                } else {
                                    "Device notifications are off. Allow Gallr notifications in Settings."
                                },
                        )
                    }
                    Spacer(Modifier.height(GallrSpacing.md))
                    Button(
                        onClick = viewModel::enableAlerts,
                        enabled = !state.isSaving,
                        shape = RectangleShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground,
                                contentColor = MaterialTheme.colorScheme.background,
                            ),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.background,
                            )
                        } else {
                            Text(
                                text = if (lang == AppLanguage.KO) "알림 허용하기" else "ALLOW ALERTS",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::dismissAlertRationale,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (lang == AppLanguage.KO) "지금은 안 할게요" else "NOT NOW",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text =
                            if (lang == AppLanguage.KO) {
                                "설정에서 언제든 변경할 수 있어요."
                            } else {
                                "YOU CAN CHANGE THIS ANYTIME IN SETTINGS."
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
            }
        }
    }
}
