package com.gallr.app.ui.profile

import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.PendingThoughtsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.Thought

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingThoughtsScreen(
    viewModel: PendingThoughtsViewModel,
    exhibitions: List<Exhibition>,
    lang: AppLanguage,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val exhibitionMap =
        remember(exhibitions) {
            exhibitions.associateBy { it.id }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "검토 대기 (${uiState.thoughts.size})"
                                AppLanguage.EN -> "Pending Review (${uiState.thoughts.size})"
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
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
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.isLoading && !uiState.hasLoaded) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                Spacer(Modifier.height(32.dp))
                if (uiState.loadFailed) {
                    GallrErrorMessage(
                        message =
                            when (lang) {
                                AppLanguage.KO -> "검토 대기 감상을 불러오지 못했습니다."
                                AppLanguage.EN -> "Couldn’t load pending thoughts."
                            },
                        actionLabel =
                            when (lang) {
                                AppLanguage.KO -> "다시 시도"
                                AppLanguage.EN -> "Retry"
                            },
                        onAction = viewModel::refresh,
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
                if (uiState.mutationFailed) {
                    GallrErrorMessage(
                        message =
                            when (lang) {
                                AppLanguage.KO -> "검토 상태를 변경하지 못했습니다. 다시 시도해주세요."
                                AppLanguage.EN -> "Couldn’t update the review. Please try again."
                            },
                    )
                    Spacer(Modifier.height(GallrSpacing.md))
                }
                if (uiState.hasLoaded && uiState.thoughts.isEmpty()) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "검토할 감상이 없습니다."
                                AppLanguage.EN -> "No pending thoughts to review."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                uiState.thoughts.forEach { thought ->
                    val exhibition = exhibitionMap[thought.exhibitionId]
                    PendingThoughtCard(
                        thought = thought,
                        exhibitionName = exhibition?.localizedName(lang) ?: thought.exhibitionId,
                        venueName = exhibition?.localizedVenueName(lang),
                        lang = lang,
                        actionsEnabled = uiState.mutatingThoughtId == null,
                        onApprove = { viewModel.approveThought(thought.id) },
                        onReject = { viewModel.rejectThought(thought.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(GallrSpacing.lg))
        }
    }
}

@Composable
private fun PendingThoughtCard(
    thought: Thought,
    exhibitionName: String,
    venueName: String?,
    lang: AppLanguage,
    actionsEnabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = GallrSpacing.md),
    ) {
        // Exhibition context
        Text(
            text = exhibitionName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (venueName != null) {
            Text(
                text = venueName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(GallrSpacing.sm))

        // Author + date
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initial = thought.authorDisplayName.firstOrNull()?.uppercase() ?: "?"
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(GallrSpacing.xs))
            Text(
                text = thought.authorDisplayName.ifEmpty { "?" },
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(GallrSpacing.sm))
            Text(
                text = thought.createdAt.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(GallrSpacing.sm))

        // Thought content
        Text(
            text = thought.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(GallrSpacing.md))

        // Approve / Reject buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onReject,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RectangleShape,
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "거절"
                            AppLanguage.EN -> "Reject"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onApprove,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RectangleShape,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "승인"
                            AppLanguage.EN -> "Approve"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
