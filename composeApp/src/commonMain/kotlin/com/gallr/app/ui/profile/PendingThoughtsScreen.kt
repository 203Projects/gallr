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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.Thought
import com.gallr.shared.repository.ThoughtRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingThoughtsScreen(
    thoughtRepository: ThoughtRepository,
    exhibitions: List<Exhibition>,
    lang: AppLanguage,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Thought>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val exhibitionMap = remember(exhibitions) {
        exhibitions.associateBy { it.id }
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        try {
            pending = thoughtRepository.getPendingThoughts()
        } catch (_: Exception) {
            pending = emptyList()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (lang) {
                            AppLanguage.KO -> "검토 대기 (${pending.size})"
                            AppLanguage.EN -> "Pending Review (${pending.size})"
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = GallrSpacing.screenMargin)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else if (pending.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "검토할 감상이 없습니다."
                        AppLanguage.EN -> "No pending thoughts to review."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                pending.forEach { thought ->
                    val exhibition = exhibitionMap[thought.exhibitionId]
                    PendingThoughtCard(
                        thought = thought,
                        exhibitionName = exhibition?.localizedName(lang) ?: thought.exhibitionId,
                        venueName = exhibition?.localizedVenueName(lang),
                        lang = lang,
                        onApprove = {
                            scope.launch {
                                try {
                                    thoughtRepository.approveThought(thought.id)
                                    refreshTrigger++
                                } catch (_: Exception) {}
                            }
                        },
                        onReject = {
                            scope.launch {
                                try {
                                    thoughtRepository.rejectThought(thought.id)
                                    refreshTrigger++
                                } catch (_: Exception) {}
                            }
                        },
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
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier
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
                modifier = Modifier
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
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RectangleShape,
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "거절"
                        AppLanguage.EN -> "Reject"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onApprove,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "승인"
                        AppLanguage.EN -> "Approve"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
