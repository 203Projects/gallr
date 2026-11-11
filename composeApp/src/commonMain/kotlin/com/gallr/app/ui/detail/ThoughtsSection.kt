package com.gallr.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.theme.GallrAccent
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Thought
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun ThoughtsSection(
    viewModel: ExhibitionThoughtsViewModel,
    authState: AuthState,
    lang: AppLanguage,
    isAdmin: Boolean = false,
    onSignInNeeded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentUserId = (authState as? AuthState.Authenticated)?.user?.id
    var showComposer by remember { mutableStateOf(false) }
    val retryLabel =
        when (lang) {
            AppLanguage.KO -> "다시 시도"
            AppLanguage.EN -> "Retry"
        }

    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title =
                    when (lang) {
                        AppLanguage.KO -> "감상"
                        AppLanguage.EN -> "THOUGHTS"
                    },
                subtitle = null,
                modifier = Modifier.weight(1f),
                horizontalPadding = 0.dp,
            )
            if (uiState.thoughts.isNotEmpty()) {
                Text(
                    text = "${uiState.thoughts.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(GallrSpacing.md))

        if (uiState.isLoading && !uiState.hasLoaded) {
            Text(
                text = "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            if (uiState.loadFailed) {
                GallrErrorMessage(
                    message =
                        when (lang) {
                            AppLanguage.KO -> "감상을 불러오지 못했습니다."
                            AppLanguage.EN -> "Couldn’t load thoughts."
                        },
                    actionLabel = retryLabel,
                    onAction = viewModel::refresh,
                )
                Spacer(Modifier.height(GallrSpacing.md))
            }

            if (uiState.mutationFailed) {
                GallrErrorMessage(
                    message =
                        when (lang) {
                            AppLanguage.KO -> "감상을 삭제하지 못했습니다. 다시 시도해주세요."
                            AppLanguage.EN -> "Couldn’t delete the thought. Please try again."
                        },
                )
                Spacer(Modifier.height(GallrSpacing.md))
            }

            if (
                uiState.hasLoaded &&
                uiState.thoughts.isEmpty() &&
                uiState.ownPendingThought == null &&
                !showComposer
            ) {
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "아직 감상이 없어요. 첫 번째로 나눠보세요."
                            AppLanguage.EN -> "No thoughts yet. Be the first to share."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Show user's own pending thought first with "Under review" label
            uiState.ownPendingThought?.let { ownPendingThought ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(GallrShapeTokens.card)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(GallrSpacing.sm),
                ) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "검토 중"
                                AppLanguage.EN -> "Under review"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(GallrSpacing.xs))
                    ThoughtCard(
                        thought = ownPendingThought,
                        lang = lang,
                        isOwn = true,
                        onDelete = { viewModel.deleteThought(ownPendingThought.id) },
                    )
                }
                Spacer(Modifier.height(GallrSpacing.sm))
            }

            // Approved thoughts
            uiState.thoughts.forEach { thought ->
                val canDelete = thought.userId == currentUserId || isAdmin
                ThoughtCard(
                    thought = thought,
                    lang = lang,
                    isOwn = canDelete,
                    onDelete =
                        if (canDelete) {
                            { viewModel.deleteThought(thought.id) }
                        } else {
                            null
                        },
                )
            }
        }

        Spacer(Modifier.height(GallrSpacing.md))

        // Composer or CTA (hide during loading, show review status if pending)
        if (uiState.hasLoaded && !uiState.isLoading) {
            if (showComposer) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ThoughtComposer(
                    submitThought = viewModel::submitThought,
                    lang = lang,
                    onSubmitted = { showComposer = false },
                )
            } else if (uiState.ownPendingThought != null) {
                // User has a thought being reviewed
                Text(
                    text =
                        when (lang) {
                            AppLanguage.KO -> "당신의 감상이 곧 전시됩니다"
                            AppLanguage.EN -> "Your words are finding their place on the wall"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (!uiState.hasUserThought) {
                OutlinedButton(
                    onClick = {
                        if (authState is AuthState.Authenticated) {
                            showComposer = true
                        } else {
                            onSignInNeeded()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = MaterialTheme.shapes.small,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                        ),
                ) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "✍️ 감상 남기기"
                                AppLanguage.EN -> "✍\uFE0F Share your thoughts"
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun ThoughtCard(
    thought: Thought,
    lang: AppLanguage,
    isOwn: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = GallrSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar
            val initial = thought.authorDisplayName.firstOrNull()?.uppercase() ?: "?"
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(GallrSpacing.sm))
            Column {
                Text(
                    text =
                        thought.authorDisplayName.ifEmpty {
                            when (lang) {
                                AppLanguage.KO -> "익명"
                                AppLanguage.EN -> "Anonymous"
                            }
                        },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = thoughtDateLabel(thought.createdAt, lang, TimeZone.currentSystemDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(GallrSpacing.xs))
        Text(
            text = thought.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (isOwn && onDelete != null) {
            Spacer(Modifier.height(GallrSpacing.xs))
            if (showDeleteConfirm) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text =
                            when (lang) {
                                AppLanguage.KO -> "삭제하시겠습니까?"
                                AppLanguage.EN -> "Delete?"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    ThoughtTextAction(
                        label =
                            when (lang) {
                                AppLanguage.KO -> "삭제"
                                AppLanguage.EN -> "Delete"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            onDelete()
                            showDeleteConfirm = false
                        },
                    )
                    Spacer(Modifier.width(GallrSpacing.sm))
                    ThoughtTextAction(
                        label =
                            when (lang) {
                                AppLanguage.KO -> "취소"
                                AppLanguage.EN -> "Cancel"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { showDeleteConfirm = false },
                    )
                }
            } else {
                ThoughtTextAction(
                    label =
                        when (lang) {
                            AppLanguage.KO -> "삭제"
                            AppLanguage.EN -> "Delete"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
        Spacer(Modifier.height(GallrSpacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A text action on a 44dp target with the button role (DESIGN.md touch targets). */
@Composable
private fun ThoughtTextAction(
    label: String,
    style: TextStyle,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = style, color = color)
    }
}

private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * The day a thought was written, in the reader's [timeZone]: "2026.11.11" in Korean, "Nov 11, 2026" in English, as
 * exhibition dates read. A timestamp that does not parse shows its date part as stored.
 */
internal fun thoughtDateLabel(
    createdAt: String,
    lang: AppLanguage,
    timeZone: TimeZone,
): String {
    val instant = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return createdAt.take(10)
    val date = instant.toLocalDateTime(timeZone).date
    return when (lang) {
        AppLanguage.KO -> {
            val month = (date.month.ordinal + 1).toString().padStart(2, '0')
            "${date.year}.$month.${date.day.toString().padStart(2, '0')}"
        }

        AppLanguage.EN -> {
            "${EN_MONTHS[date.month.ordinal]} ${date.day}, ${date.year}"
        }
    }
}
