package com.gallr.app.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gallr.app.accessibility.isScreenReaderActive
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.diary.DiaryPublishCopy
import com.gallr.app.ui.diary.DiaryRowCopy
import com.gallr.app.ui.diary.diaryNoteStatusLabel
import com.gallr.app.ui.diary.diaryRejectionLine
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionThoughtsUiState
import com.gallr.app.viewmodel.ExhibitionThoughtsViewModel
import com.gallr.app.viewmodel.ThoughtsMessage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.thoughts.PublicThought
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.ic_more_horiz
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Instant

/**
 * The 감상 section of an exhibition page. The read side (spec 103 US4) lists 감상 with a ⋯ menu per card: 신고 and
 * 이 사용자의 감상 숨기기 on another person's, 수정 and 공개 중단 on the reader's own. A 감상 is written only in the diary
 * entry sheet (spec 103 US3): 감상 남기기 opens it, and the reader's own 감상 that is not public yet shows above the list
 * with its status, in place of its old public card: the author sees exactly one card for their own 감상.
 */
@Composable
fun ThoughtsSection(
    viewModel: ExhibitionThoughtsViewModel,
    authState: AuthState,
    lang: AppLanguage,
    snackbarHostState: SnackbarHostState,
    onSignInNeeded: () -> Unit,
    /** 수정 on the reader's own card or note: opens the diary entry sheet. */
    onEditOwnNote: () -> Unit,
    modifier: Modifier = Modifier,
    /** The reader's own 감상 and its status, from the diary. */
    ownNote: OwnPublicNote? = null,
    ownNoteStatus: PublicNoteStatus? = null,
    /** 감상 남기기; null on a show that has not opened, which offers no way to write. */
    onWriteNote: (() -> Unit)? = null,
    /**
     * [ownNote] and [ownNoteStatus] come from the reader's diary: the list then shows the reader's own card only while
     * it is published, and reads again once the reader's own change reaches the server.
     */
    followsOwnNote: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(authState) { viewModel.onAuthStateChanged(authState) }
    // The ViewModel outlives the page, so the list is read again each time the page is shown and when the app comes
    // back to it (spec 103 FR-023: a 감상 taken down, kept or sent back to review elsewhere must not linger).
    LaunchedEffect(viewModel) { viewModel.onPageShown() }
    RefreshOnReturnToForeground(onReturn = viewModel::refresh)
    if (followsOwnNote) {
        LaunchedEffect(ownNote, ownNoteStatus) { viewModel.onOwnNoteChanged(ownNote, ownNoteStatus) }
    }
    LaunchedEffect(uiState.signInRequested) {
        if (uiState.signInRequested) {
            viewModel.onSignInRequestHandled()
            onSignInNeeded()
        }
    }
    ThoughtsMessageEffect(
        message = uiState.message,
        hostState = snackbarHostState,
        language = lang,
        screenReaderActive = isScreenReaderActive(),
        onUndoHide = viewModel::undoHideAuthor,
        onShown = viewModel::messageShown,
    )
    val pendingOwnNote = ownNote?.takeIf { ownNoteStatus != null && ownNoteStatus != PublicNoteStatus.PUBLISHED }

    ThoughtsSectionContent(
        state = uiState,
        lang = lang,
        onRetry = viewModel::refresh,
        onReport = viewModel::startReport,
        onHideAuthor = viewModel::hideAuthor,
        onEditOwn = { onEditOwnNote() },
        onUnpublishOwn = viewModel::unpublishOwn,
        modifier = modifier,
        canWrite = onWriteNote != null,
        hideEmptyMessage = pendingOwnNote != null,
        ownPending = {
            if (pendingOwnNote != null && ownNoteStatus != null) {
                OwnNoteInReview(note = pendingOwnNote, status = ownNoteStatus, lang = lang, onEdit = onEditOwnNote)
            }
        },
        footer = { onWriteNote?.let { WriteNoteButton(lang = lang, onClick = it) } },
    )

    uiState.reportTarget?.let {
        ThoughtReportSheet(
            language = lang,
            busy = uiState.reportBusy,
            onReport = viewModel::report,
            onDismiss = viewModel::closeReport,
        )
    }
}

/**
 * Calls [onReturn] each time the app comes back to the foreground on this screen, never on the first showing (the
 * screen reads on entry itself). Android sends ON_RESUME after its own dialogs too, which only means one more read.
 */
@Composable
private fun RefreshOnReturnToForeground(onReturn: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReturn by rememberUpdatedState(onReturn)
    DisposableEffect(lifecycleOwner) {
        var wentAway = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        wentAway = true
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        if (wentAway) currentOnReturn()
                        wentAway = false
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * The section as state: heading, list and per-card menus. [ownPending] holds the reader's own 감상 that is not public
 * yet, and [footer] 감상 남기기.
 */
@Composable
internal fun ThoughtsSectionContent(
    state: ExhibitionThoughtsUiState,
    lang: AppLanguage,
    onRetry: () -> Unit,
    onReport: (PublicThought) -> Unit,
    onHideAuthor: (PublicThought) -> Unit,
    onEditOwn: (PublicThought) -> Unit,
    onUnpublishOwn: (PublicThought) -> Unit,
    modifier: Modifier = Modifier,
    /** A 감상 can be written here (the show has opened), so an empty list invites the first. */
    canWrite: Boolean = true,
    hideEmptyMessage: Boolean = false,
    ownPending: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    var unpublishing by remember { mutableStateOf<PublicThought?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = if (lang == AppLanguage.KO) "감상" else "NOTES",
                subtitle = null,
                modifier = Modifier.weight(1f),
                horizontalPadding = 0.dp,
            )
            if (state.thoughts.isNotEmpty()) {
                Text(
                    text = "${state.thoughts.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(GallrSpacing.md))

        if (state.isLoading && !state.hasLoaded) {
            Text(
                text = "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            if (state.loadFailed) {
                GallrErrorMessage(
                    message =
                        when (lang) {
                            AppLanguage.KO -> "감상을 불러오지 못했습니다."
                            AppLanguage.EN -> "Couldn’t load notes."
                        },
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = onRetry,
                )
                Spacer(Modifier.height(GallrSpacing.md))
            }

            if (state.hasLoaded && state.thoughts.isEmpty() && !hideEmptyMessage) {
                Text(
                    text = thoughtsEmptyLine(lang, canWrite = canWrite, onlyHidden = state.onlyHiddenNotes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ownPending()

            state.thoughts.forEach { thought ->
                PublicThoughtCard(
                    thought = thought,
                    isOwn = thought.authorId == state.currentUserId,
                    lang = lang,
                    onReport = { onReport(thought) },
                    onHideAuthor = { onHideAuthor(thought) },
                    onEdit = { onEditOwn(thought) },
                    onUnpublish = { unpublishing = thought },
                )
            }
        }

        Spacer(Modifier.height(GallrSpacing.md))

        footer()
    }

    unpublishing?.let { thought ->
        UnpublishThoughtDialog(
            lang = lang,
            onConfirm = {
                unpublishing = null
                onUnpublishOwn(thought)
            },
            onDismiss = { unpublishing = null },
        )
    }
}

/** A published 감상 with its ⋯ menu (contracts/diary-ui.md "감상 card menu"). */
@Composable
private fun PublicThoughtCard(
    thought: PublicThought,
    isOwn: Boolean,
    lang: AppLanguage,
    onReport: () -> Unit,
    onHideAuthor: () -> Unit,
    onEdit: () -> Unit,
    onUnpublish: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = GallrSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThoughtAuthorLine(
                displayName = thought.authorDisplayName,
                dateLabel = thoughtDateLabel(thought.createdAt, lang, TimeZone.currentSystemDefault()),
                lang = lang,
                modifier = Modifier.weight(1f),
            )
            ThoughtCardMenu(
                isOwn = isOwn,
                reportedByMe = thought.reportedByMe,
                lang = lang,
                onReport = onReport,
                onHideAuthor = onHideAuthor,
                onEdit = onEdit,
                onUnpublish = onUnpublish,
            )
        }
        Spacer(Modifier.height(GallrSpacing.xs))
        Text(
            text = thought.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(GallrSpacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ThoughtCardMenu(
    isOwn: Boolean,
    reportedByMe: Boolean,
    lang: AppLanguage,
    onReport: () -> Unit,
    onHideAuthor: () -> Unit,
    onEdit: () -> Unit,
    onUnpublish: () -> Unit,
) {
    val ko = lang == AppLanguage.KO
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(MENU_TARGET).semantics { contentDescription = if (ko) "더 보기" else "MORE" },
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_horiz),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.small,
            containerColor = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            if (isOwn) {
                ThoughtMenuItem(if (ko) "수정" else "EDIT", enabled = true) {
                    open = false
                    onEdit()
                }
                ThoughtMenuItem(if (ko) "공개 중단" else "UNPUBLISH", enabled = true) {
                    open = false
                    onUnpublish()
                }
            } else {
                ThoughtMenuItem(
                    label =
                        when {
                            reportedByMe -> if (ko) "신고함" else "REPORTED"
                            else -> if (ko) "신고" else "REPORT"
                        },
                    enabled = !reportedByMe,
                ) {
                    open = false
                    onReport()
                }
                ThoughtMenuItem(if (ko) "이 사용자의 감상 숨기기" else "HIDE THIS PERSON'S NOTES", enabled = true) {
                    open = false
                    onHideAuthor()
                }
            }
        }
    }
}

@Composable
private fun ThoughtMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.heightIn(min = MENU_TARGET),
    )
}

@Composable
private fun UnpublishThoughtDialog(
    lang: AppLanguage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ko = lang == AppLanguage.KO
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.background,
        title = {
            Text(
                text = if (ko) "감상 공개를 중단할까요? 메모는 일기에 남아요." else "Stop publishing this note? It stays in your diary.",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = MENU_TARGET),
            ) {
                Text(if (ko) "공개 중단" else "UNPUBLISH", color = MaterialTheme.colorScheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = MENU_TARGET),
            ) {
                Text(if (ko) "취소" else "CANCEL", color = MaterialTheme.colorScheme.onBackground)
            }
        },
    )
}

/**
 * Snackbars after a report, a hide or an unpublish. The hide snackbar carries 되돌리기 and stays a long while, and
 * with a screen reader until it is acted on, as after 덜 보기.
 */
@Composable
internal fun ThoughtsMessageEffect(
    message: ThoughtsMessage?,
    hostState: SnackbarHostState,
    language: AppLanguage,
    screenReaderActive: Boolean,
    onUndoHide: (String) -> Unit,
    onShown: () -> Unit,
) {
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        hostState.currentSnackbarData?.dismiss()
        if (shown is ThoughtsMessage.AuthorHidden) {
            val result =
                hostState.showSnackbar(
                    message = authorHiddenMessage(shown.displayName, language),
                    actionLabel = if (language == AppLanguage.KO) "되돌리기" else "UNDO",
                    withDismissAction = screenReaderActive,
                    duration = if (screenReaderActive) SnackbarDuration.Indefinite else SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) onUndoHide(shown.userId)
        } else {
            hostState.showSnackbar(message = thoughtsMessageText(shown, language), duration = SnackbarDuration.Short)
        }
        onShown()
    }
}

private fun authorHiddenMessage(
    displayName: String,
    language: AppLanguage,
): String =
    when {
        displayName.isBlank() -> if (language == AppLanguage.KO) "이 사용자의 감상을 숨겼어요" else "This person's notes are hidden"
        language == AppLanguage.KO -> "${displayName}님의 감상을 숨겼어요"
        else -> "Notes from $displayName are hidden"
    }

internal fun thoughtsMessageText(
    message: ThoughtsMessage,
    language: AppLanguage,
): String {
    val (ko, en) =
        when (message) {
            ThoughtsMessage.Reported -> "신고했어요" to "Reported"
            ThoughtsMessage.AlreadyReported -> "이미 신고했어요" to "Already reported"
            ThoughtsMessage.ReportFailed -> "! 신고하지 못했어요. 다시 시도해 주세요." to REPORT_FAILED_EN
            ThoughtsMessage.CheckConnection -> "연결을 확인한 뒤 다시 시도해 주세요" to "Check your connection and try again"
            ThoughtsMessage.HideFailed -> "! 감상을 숨기지 못했어요. 다시 시도해 주세요." to HIDE_FAILED_EN
            ThoughtsMessage.UnpublishFailed -> "! 공개를 중단하지 못했어요. 다시 시도해 주세요." to UNPUBLISH_FAILED_EN
            is ThoughtsMessage.AuthorHidden -> return authorHiddenMessage(message.displayName, language)
        }
    return if (language == AppLanguage.KO) ko else en
}

/**
 * The line under the heading when the page shows no 감상: it invites a first note only where one can be written
 * ([canWrite]: a show that has opened), and says so when every 감상 is by someone the reader hid.
 */
internal fun thoughtsEmptyLine(
    lang: AppLanguage,
    canWrite: Boolean,
    onlyHidden: Boolean,
): String {
    val (ko, en) =
        when {
            onlyHidden -> "숨긴 감상만 있어요." to "Only notes you've hidden."
            canWrite -> "아직 감상이 없어요. 첫 번째로 나눠보세요." to "No notes yet. Be the first to share."
            else -> "아직 감상이 없어요." to "No notes yet."
        }
    return if (lang == AppLanguage.KO) ko else en
}

private const val REPORT_FAILED_EN = "! Couldn't send the report. Please try again."
private const val HIDE_FAILED_EN = "! Couldn't hide these notes. Please try again."
private const val UNPUBLISH_FAILED_EN = "! Couldn't unpublish the note. Please try again."

/** Avatar initial, name (익명 when blank) and the day it was written. */
@Composable
private fun ThoughtAuthorLine(
    displayName: String,
    dateLabel: String,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(GallrSpacing.sm))
        Column {
            Text(
                text = displayName.ifEmpty { if (lang == AppLanguage.KO) "익명" else "Anonymous" },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The reader's own 감상 that is not on the page yet (보내는 중, 검토 중, 공개 안 됨): its status, the reason staff gave and
 * 수정, which opens the entry sheet. Only its author sees it.
 */
@Composable
private fun OwnNoteInReview(
    note: OwnPublicNote,
    status: PublicNoteStatus,
    lang: AppLanguage,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(GallrShapeTokens.card)
                .background(scheme.surfaceVariant)
                .padding(GallrSpacing.md),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.xs),
    ) {
        Text(
            text = diaryNoteStatusLabel(status, lang),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Text(text = note.content, style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground)
        note.rejectionReason
            ?.takeIf { note.moderation == PublicNoteModeration.REJECTED }
            ?.let { reason ->
                Text(
                    text = diaryRejectionLine(reason, lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        ThoughtTextAction(
            label = DiaryRowCopy.edit(lang),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onBackground,
            onClick = onEdit,
        )
    }
    Spacer(Modifier.height(GallrSpacing.sm))
}

/** 감상 남기기: the one way to write a 감상, through the diary entry sheet (no emoji, 44dp). */
@Composable
private fun WriteNoteButton(
    lang: AppLanguage,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        shape = GallrShapeTokens.small,
        // Its words line up with the section's margin, like the heading above.
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.heightIn(min = MENU_TARGET),
    ) {
        Text(
            text = DiaryPublishCopy.writeNote(lang),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
                .heightIn(min = MENU_TARGET)
                .widthIn(min = MENU_TARGET)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = style, color = color)
    }
}

private val MENU_TARGET = 44.dp

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
    return thoughtDateLabel(instant, lang, timeZone)
}

internal fun thoughtDateLabel(
    createdAt: Instant,
    lang: AppLanguage,
    timeZone: TimeZone,
): String {
    val date = createdAt.toLocalDateTime(timeZone).date
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
