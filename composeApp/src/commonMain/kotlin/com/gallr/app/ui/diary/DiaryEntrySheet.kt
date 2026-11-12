package com.gallr.app.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.components.GallrFilterChip
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.DiaryEntryActions
import com.gallr.app.viewmodel.DiaryEntryEditorState
import com.gallr.app.viewmodel.DiaryEntryUiState
import com.gallr.app.viewmodel.DiaryVisibility
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.diary.VisitDateOptions
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The diary row and entry sheet on an exhibition page (spec 103 US1), hoisted from `DiaryEntryViewModel`. */
data class DiaryEntryControl(
    val state: DiaryEntryUiState,
    val actions: DiaryEntryActions,
)

/**
 * The diary entry sheet (DESIGN.md "Diary entry sheet"): full height, with the save pinned under the parts. Closing it
 * with unsaved changes, by back, a drag or a tap outside, asks first. [onSignIn] opens sign-in over the screen; the
 * draft waits in the ViewModel and the sheet comes back with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryEntrySheet(
    title: String,
    venue: String,
    editor: DiaryEntryEditorState,
    language: AppLanguage,
    actions: DiaryEntryActions,
    onSignIn: () -> Unit,
) {
    val hasUnsavedChanges by rememberUpdatedState(editor.hasUnsavedChanges)
    var confirmDiscard by remember { mutableStateOf(false) }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { value ->
                if (value == SheetValue.Hidden && hasUnsavedChanges) {
                    confirmDiscard = true
                    false
                } else {
                    true
                }
            },
        )
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = {
            if (hasUnsavedChanges) confirmDiscard = true else actions.discardEditing()
        },
        sheetState = sheetState,
        shape = GallrShapeTokens.sheetTop,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        DiaryEntrySheetContent(
            title = title,
            venue = venue,
            editor = editor,
            language = language,
            actions = actions,
            onSignIn = onSignIn,
        )
    }
    if (confirmDiscard) {
        DiaryConfirmDialog(
            text = DiarySheetCopy.discardQuestion(language),
            confirm = DiarySheetCopy.close(language),
            dismiss = DiarySheetCopy.keepEditing(language),
            onConfirm = {
                confirmDiscard = false
                actions.discardEditing()
            },
            onDismiss = {
                confirmDiscard = false
                // Back may already have hidden the sheet on its way to asking; bring it back.
                if (!sheetState.isVisible) scope.launch { sheetState.show() }
            },
        )
    }
}

/** The sheet's parts and footer, apart from the sheet itself, so they can be tested on their own. */
@Composable
internal fun DiaryEntrySheetContent(
    title: String,
    venue: String,
    editor: DiaryEntryEditorState,
    language: AppLanguage,
    actions: DiaryEntryActions,
    onSignIn: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val partsScroll = rememberScrollState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmNoNote by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(partsScroll)
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .padding(top = GallrSpacing.sm, bottom = GallrSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GallrSpacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GallrSpacing.xs)) {
                if (editor.showApprovalLine) {
                    Text(
                        text = DiaryRowCopy.approvalLine(language),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onBackground,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = venue, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            DatePart(editor, language, actions::selectDate)
            DiaryPart(DiarySheetCopy.feelingHeading(language)) {
                ChipFlow {
                    VisitFeeling.entries.forEach { feeling ->
                        DiaryChip(
                            label = diaryFeelingLabel(feeling, language),
                            selected = editor.feeling == feeling,
                            onClick = { actions.toggleFeeling(feeling) },
                        )
                    }
                }
            }
            DiaryPart(DiarySheetCopy.tagHeading(language)) {
                ChipFlow {
                    VisitTag.entries.forEach { tag ->
                        DiaryChip(
                            label = diaryTagLabel(tag, language),
                            selected = tag in editor.tags,
                            onClick = { actions.toggleTag(tag) },
                        )
                    }
                }
                if (editor.tagLimitReached) {
                    Text(
                        text = DiarySheetCopy.tagLimit(language),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            NotePart(editor, language, actions::updateNote)
            PublishPart(editor, language, actions, onSignIn)
        }
        // A hairline marks where the parts continue under the pinned footer.
        if (partsScroll.canScrollForward || partsScroll.canScrollBackward) {
            HorizontalDivider(color = scheme.outlineVariant)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallrSpacing.screenMargin)
                    .padding(top = GallrSpacing.sm, bottom = GallrSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editor.isExistingEntry) {
                TextButton(
                    onClick = { confirmDelete = true },
                    shape = GallrShapeTokens.small,
                    modifier = Modifier.heightIn(min = TARGET),
                ) {
                    Text(
                        text = DiarySheetCopy.deleteEntry(language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onBackground,
                    )
                }
            }
            Button(
                // An entry saved without a note loses its 감상 on the server; say so before it happens.
                onClick = { if (editor.savingRemovesPublicNote) confirmNoNote = true else actions.save() },
                enabled = editor.canSave,
                shape = GallrShapeTokens.small,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = scheme.onBackground,
                        contentColor = scheme.background,
                        disabledContainerColor = scheme.surfaceVariant,
                        disabledContentColor = scheme.onSurfaceVariant,
                    ),
                modifier = Modifier.weight(1f).heightIn(min = SAVE_HEIGHT),
            ) {
                Text(text = DiarySheetCopy.save(language), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (confirmDelete) {
        DiaryConfirmDialog(
            text = DiarySheetCopy.deleteConfirm(editor.deleteRemovesPublicNote, language),
            confirm = DiarySheetCopy.delete(language),
            dismiss = DiarySheetCopy.cancel(language),
            onConfirm = {
                confirmDelete = false
                actions.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmNoNote) {
        DiaryConfirmDialog(
            text = DiaryPublishCopy.noNoteWarning(language),
            confirm = DiarySheetCopy.save(language),
            dismiss = DiarySheetCopy.cancel(language),
            onConfirm = {
                confirmNoNote = false
                actions.save()
            },
            onDismiss = { confirmNoNote = false },
        )
    }
}

@Composable
private fun DatePart(
    editor: DiaryEntryEditorState,
    language: AppLanguage,
    onSelectDate: (LocalDate) -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }
    val options = editor.dateOptions
    val date = editor.visitDate
    val onToday = options.today && date == editor.today
    val onYesterday = options.yesterday && date == editor.yesterday
    DiaryPart(DiarySheetCopy.whenHeading(language)) {
        if (editor.needsDate) {
            Text(
                text = DiarySheetCopy.chooseDate(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChipFlow {
            if (options.today) {
                DiaryChip(DiarySheetCopy.today(language), onToday, onClick = { onSelectDate(editor.today) })
            }
            if (options.yesterday) {
                DiaryChip(DiarySheetCopy.yesterday(language), onYesterday, onClick = { onSelectDate(editor.yesterday) })
            }
            if (editor.offersDatePicker) {
                val picked = date != null && !onToday && !onYesterday
                DiaryChip(
                    label = if (picked) diaryDayLabel(date, language) else DiarySheetCopy.pickDate(language),
                    selected = picked,
                    onClick = { pickingDate = true },
                )
            }
        }
    }
    if (pickingDate) {
        DiaryDatePickerDialog(
            options = options,
            current = date,
            language = language,
            onPick = onSelectDate,
            onDismiss = { pickingDate = false },
        )
    }
}

@Composable
private fun NotePart(
    editor: DiaryEntryEditorState,
    language: AppLanguage,
    onNoteChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    // Opened from 감상 남기기, or asked to edit and ask again: the note is what the visitor came to write.
    LaunchedEffect(editor.focusNote, editor.askAgain) {
        if (editor.focusNote) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
            focusRequester.requestFocus()
        }
    }
    DiaryPart(DiarySheetCopy.noteHeading(language), modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        OutlinedTextField(
            value = editor.note,
            onValueChange = onNoteChange,
            placeholder = {
                Text(
                    text = DiarySheetCopy.notePlaceholder(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground),
            shape = GallrShapeTokens.small,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = scheme.outline,
                    unfocusedBorderColor = scheme.outlineVariant,
                    cursorColor = scheme.onBackground,
                    focusedContainerColor = scheme.background,
                    unfocusedContainerColor = scheme.background,
                ),
            minLines = NOTE_MIN_LINES,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (editor.noteOverPublishLimit) {
                Text(
                    text = DiarySheetCopy.noteOverLimit(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Neutral at every length: the counter is not one of the accent's three roles.
            Text(
                text = DiarySheetCopy.noteCounter(editor.note.length),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GallrSpacing.sm),
            )
        }
    }
}

/**
 * 누가 볼 수 있나요? (owner decision 10): two chips for a signed-in visitor, a sign-in button for a guest. The line
 * under the chips follows the choice; then the note's status, the reason staff gave, and the way to ask again.
 */
@Composable
private fun PublishPart(
    editor: DiaryEntryEditorState,
    language: AppLanguage,
    actions: DiaryEntryActions,
    onSignIn: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    DiaryPart(DiaryPublishCopy.heading(language)) {
        if (!editor.isSignedIn) {
            TextButton(
                onClick = onSignIn,
                shape = GallrShapeTokens.small,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(
                    text = DiaryPublishCopy.signIn(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackground,
                )
            }
            return@DiaryPart
        }
        val shown = editor.shownVisibility
        ChipFlow {
            DiaryChip(
                label = DiaryPublishCopy.onlyMe(language),
                selected = shown == DiaryVisibility.ONLY_ME,
                onClick = { actions.selectVisibility(DiaryVisibility.ONLY_ME) },
            )
            DiaryChip(
                label = DiaryPublishCopy.exhibitionPage(language),
                selected = shown == DiaryVisibility.EXHIBITION_PAGE,
                enabled = editor.publicChoiceEnabled,
                onClick = { actions.selectVisibility(DiaryVisibility.EXHIBITION_PAGE) },
            )
        }
        Text(
            text =
                when {
                    !editor.noteFitsPublish && shown == DiaryVisibility.ONLY_ME && editor.note.isBlank() -> {
                        DiaryPublishCopy.emptyNoteLine(language)
                    }

                    shown == DiaryVisibility.EXHIBITION_PAGE -> {
                        DiaryPublishCopy.publicLine(language)
                    }

                    else -> {
                        DiaryPublishCopy.privateLine(language)
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        editor.noteStatus
            ?.takeIf { it != PublicNoteStatus.PRIVATE }
            ?.let { status ->
                Text(
                    text = diaryNoteStatusLabel(status, language),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onBackground,
                )
            }
        editor.publicNote
            ?.takeIf { it.moderation == PublicNoteModeration.REJECTED }
            ?.rejectionReason
            ?.let { reason ->
                Text(
                    text = diaryRejectionLine(reason, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        val askAgainLabel =
            when {
                editor.offersAskAgainWithEdit -> DiaryPublishCopy.askAgainWithEdit(language)
                editor.offersEditAndAskAgain -> DiaryPublishCopy.editAndAskAgain(language)
                else -> null
            }
        askAgainLabel?.let { label ->
            OutlinedButton(
                onClick = actions::askAgain,
                shape = GallrShapeTokens.small,
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = scheme.onBackground)
            }
        }
        if (editor.askAgainPending) {
            Text(
                text = DiaryPublishCopy.askAgainPending(language),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** A part of the sheet under its small label, read as a heading. */
@Composable
private fun DiaryPart(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(GallrSpacing.sm),
    ) {
        content()
    }
}

/** The taste sheet's chip: `onBackground` fill when chosen, a toggle with its selected state, 44dp tall. */
@Composable
private fun DiaryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    GallrFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        selectedColor = MaterialTheme.colorScheme.onBackground,
        enabled = enabled,
        modifier = Modifier.heightIn(min = TARGET),
    )
}

@Composable
private fun DiaryConfirmDialog(
    text: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GallrShapeTokens.card,
        containerColor = scheme.background,
        text = { Text(text = text, style = MaterialTheme.typography.bodyMedium, color = scheme.onBackground) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shape = GallrShapeTokens.small,
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(text = confirm, color = scheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = GallrShapeTokens.small,
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(text = dismiss, color = scheme.onBackground)
            }
        },
    )
}

/**
 * The calendar behind 날짜 선택 (DESIGN.md "Calendar picker"): the run up to today only, 16dp corners, monochrome
 * throughout, and in the app's language: its month names and weekdays from [diaryCalendarLocale], its header from
 * [diaryCalendarHeadline].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryDatePickerDialog(
    options: VisitDateOptions,
    current: LocalDate?,
    language: AppLanguage,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState =
        remember(options, language) {
            DatePickerState(
                locale = diaryCalendarLocale(language),
                initialSelectedDateMillis = current?.let(::utcMillis),
                initialDisplayedMonthMillis = utcMillis(current ?: options.latest),
                yearRange = options.earliest.year..options.latest.year,
                selectableDates = diarySelectableDates(options.earliest, options.latest),
            )
        }
    val colors = diaryDatePickerColors()
    val scheme = MaterialTheme.colorScheme
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPick(localDateOf(it)) }
                    onDismiss()
                },
                enabled = pickerState.selectedDateMillis != null,
                shape = GallrShapeTokens.small,
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(text = DiarySheetCopy.ok(language), color = scheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = GallrShapeTokens.small,
                modifier = Modifier.heightIn(min = TARGET),
            ) {
                Text(text = DiarySheetCopy.cancel(language), color = scheme.onBackground)
            }
        },
        shape = GallrShapeTokens.card,
        tonalElevation = 0.dp,
        colors = colors,
    ) {
        DatePicker(
            state = pickerState,
            colors = colors,
            showModeToggle = false,
            title = {
                Text(
                    text = DiarySheetCopy.pickDate(language),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(PICKER_TITLE_PADDING),
                )
            },
            headline = {
                Text(
                    text =
                        pickerState.selectedDateMillis
                            ?.let { diaryCalendarHeadline(localDateOf(it), language) }
                            ?: DiarySheetCopy.chooseDate(language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(PICKER_HEADLINE_PADDING),
                )
            },
        )
    }
}

/** The calendar's colours: the theme's monochrome pair wherever Material would reach for its primary colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun diaryDatePickerColors(): DatePickerColors {
    val scheme = MaterialTheme.colorScheme
    return DatePickerDefaults.colors(
        containerColor = scheme.background,
        titleContentColor = scheme.onSurfaceVariant,
        headlineContentColor = scheme.onBackground,
        weekdayContentColor = scheme.onSurfaceVariant,
        subheadContentColor = scheme.onSurfaceVariant,
        navigationContentColor = scheme.onBackground,
        yearContentColor = scheme.onBackground,
        disabledYearContentColor = scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
        currentYearContentColor = scheme.onBackground,
        selectedYearContentColor = scheme.background,
        selectedYearContainerColor = scheme.onBackground,
        dayContentColor = scheme.onBackground,
        disabledDayContentColor = scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
        selectedDayContentColor = scheme.background,
        selectedDayContainerColor = scheme.onBackground,
        todayContentColor = scheme.onBackground,
        todayDateBorderColor = scheme.onBackground,
        dividerColor = scheme.outlineVariant,
    )
}

/** Days from [earliest] to [latest], both included; Material passes each day as UTC midnight. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun diarySelectableDates(
    earliest: LocalDate,
    latest: LocalDate,
): SelectableDates =
    object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = localDateOf(utcTimeMillis) in earliest..latest

        override fun isSelectableYear(year: Int): Boolean = year in earliest.year..latest.year
    }

private fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

private fun localDateOf(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date

private val TARGET = 44.dp
private val SAVE_HEIGHT = 52.dp
private const val NOTE_MIN_LINES = 3
private const val DISABLED_ALPHA = 0.38f
private val PICKER_TITLE_PADDING = PaddingValues(start = GallrSpacing.lg, end = GallrSpacing.md, top = GallrSpacing.md)
private val PICKER_HEADLINE_PADDING =
    PaddingValues(start = GallrSpacing.lg, end = GallrSpacing.md, bottom = GallrSpacing.md)
