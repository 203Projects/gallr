package com.gallr.app.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.GallrErrorMessage
import com.gallr.app.ui.components.GallrFilterChip
import com.gallr.app.ui.components.SectionHeader
import com.gallr.app.ui.theme.GallrShapeTokens
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.DiaryUiState
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.network.nativeSupabaseImageUrl
import com.gallr.shared.diary.Diary
import com.gallr.shared.diary.DiaryEntry
import com.gallr.shared.diary.DiaryFilter
import com.gallr.shared.diary.PublicNoteStatus

/** What MY 일기 can ask for; the host wires each to `DiaryViewModel`, `MyGallrViewModel` and the entry sheet. */
internal data class DiaryListActions(
    val onSelectFilter: (DiaryFilter) -> Unit = {},
    /** Opens the entry sheet for the row's show. */
    val onOpenEntry: (DiaryEntry) -> Unit = {},
    val onAddPastVisits: () -> Unit = {},
    val onAddEndedSaved: () -> Unit = {},
    val onDismissEndedSaved: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * MY → 일기 (DESIGN.md "MY 일기"): the ended-saved strip, the filter, + 지난 전시 추가, then the months newest first and
 * 날짜 미정 last, each row one button that opens the entry sheet.
 */
@Composable
internal fun DiaryList(
    state: DiaryUiState,
    actions: DiaryListActions,
    modifier: Modifier = Modifier,
) {
    val language = state.language
    val diary = state.diary
    when {
        state.loadFailed -> {
            GallrErrorMessage(
                message = DiaryListCopy.loadFailed(language),
                actionLabel = DiaryListCopy.retry(language),
                onAction = actions.onRetry,
                modifier = modifier.padding(GallrSpacing.screenMargin),
            )
        }

        diary == null -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            }
        }

        else -> {
            DiaryContent(diary, state.filter, language, actions, modifier)
        }
    }
}

@Composable
private fun DiaryContent(
    diary: Diary,
    filter: DiaryFilter,
    language: AppLanguage,
    actions: DiaryListActions,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = GallrSpacing.md),
    ) {
        if (diary.showEndedSavedStrip) {
            item(key = "ended-saved") {
                EndedSavedStrip(
                    count = diary.endedSavedCount,
                    language = language,
                    onAdd = actions.onAddEndedSaved,
                    onDismiss = actions.onDismissEndedSaved,
                    modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
                )
                Spacer(Modifier.height(GallrSpacing.md))
            }
        }
        if (diary.isEmpty) {
            item(key = "empty") {
                GallrEmptyState(
                    message = DiaryListCopy.empty(language),
                    actionLabel = DiaryListCopy.emptyAction(language),
                    onAction = actions.onAddPastVisits,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@LazyColumn
        }
        item(key = "controls") {
            DiaryControls(filter, language, actions)
        }
        if (diary.months.isEmpty() && diary.undated.isEmpty()) {
            item(key = "empty-filter") {
                Text(
                    text = DiaryListCopy.emptyUnderFilter(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(GallrSpacing.screenMargin),
                )
            }
        }
        diary.months.forEach { month ->
            group(
                key = "month-${month.year}-${month.month}",
                title = DiaryListCopy.month(month.year, month.month, language),
                entries = month.entries,
                language = language,
                onOpenEntry = actions.onOpenEntry,
            )
        }
        if (diary.undated.isNotEmpty()) {
            group(
                key = "undated",
                title = DiaryListCopy.undated(language),
                entries = diary.undated,
                language = language,
                onOpenEntry = actions.onOpenEntry,
            )
        }
    }
}

@Composable
private fun DiaryControls(
    filter: DiaryFilter,
    language: AppLanguage,
    actions: DiaryListActions,
) {
    Column(modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin)) {
        Row(horizontalArrangement = Arrangement.spacedBy(GallrSpacing.sm)) {
            DiaryFilterChip(
                label = DiaryListCopy.all(language),
                selected = filter == DiaryFilter.ALL,
                onClick = { actions.onSelectFilter(DiaryFilter.ALL) },
            )
            DiaryFilterChip(
                label = DiaryListCopy.publishedNotes(language),
                selected = filter == DiaryFilter.PUBLISHED_NOTES,
                onClick = { actions.onSelectFilter(DiaryFilter.PUBLISHED_NOTES) },
            )
        }
        Spacer(Modifier.height(GallrSpacing.sm))
        OutlinedButton(
            onClick = actions.onAddPastVisits,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().height(TARGET),
        ) {
            Text(
                text = DiaryListCopy.addPastVisits(language),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The entry sheet's chips: `onBackground` when selected, never the accent; one is always selected. */
@Composable
private fun DiaryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GallrFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        selectedColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.heightIn(min = TARGET),
    )
}

private fun LazyListScope.group(
    key: String,
    title: String,
    entries: List<DiaryEntry>,
    language: AppLanguage,
    onOpenEntry: (DiaryEntry) -> Unit,
) {
    item(key = key) {
        SectionHeader(
            title = title,
            subtitle = null,
            modifier = Modifier.padding(top = GallrSpacing.lg, bottom = GallrSpacing.sm),
        )
    }
    items(entries, key = { "$key-${it.exhibitionId}" }) { entry ->
        DiaryRow(entry = entry, language = language, onClick = { onOpenEntry(entry) })
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = GallrSpacing.screenMargin),
        )
    }
}

/**
 * One entry as one button: cover, title, venue, date and feeling, the note's first line and, when the note has a
 * public status, its status word. A row with no show to open (a 감상 the app knows nothing of) is not a button.
 */
@Composable
private fun DiaryRow(
    entry: DiaryEntry,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val canOpen = entry.snapshot != null
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .semantics(mergeDescendants = true) {}
                .clickable(enabled = canOpen, role = Role.Button, onClick = onClick)
                .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GallrSpacing.md),
    ) {
        DiaryCover(entry.snapshot?.coverImageUrl)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.snapshot?.localizedName(language) ?: DiaryListCopy.unavailable(language),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.snapshot?.let { snapshot ->
                Text(
                    text = snapshot.localizedVenueName(language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            diaryRowDateLine(entry, language)?.let { line ->
                Text(text = line, style = MaterialTheme.typography.labelMedium)
            }
            diaryRowNoteLine(entry)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.noteStatus
                ?.takeIf { it != PublicNoteStatus.PRIVATE }
                ?.let { status ->
                    Text(
                        text = diaryNoteStatusLabel(status, language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun DiaryCover(url: String?) {
    val shape = GallrShapeTokens.small
    if (url.isNullOrBlank()) {
        Box(
            Modifier
                .size(COVER_SIZE)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        AsyncImage(
            model = nativeSupabaseImageUrl(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(COVER_SIZE)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/** `11월 9일 · 좋았어요` / `NOV 9 · LOVED IT`; the feeling alone without a date, nothing with neither. */
private fun diaryRowDateLine(
    entry: DiaryEntry,
    language: AppLanguage,
): String? {
    val parts =
        listOfNotNull(
            entry.visit?.visitDate?.let { diaryDayLabel(it, language) },
            entry.visit?.feeling?.let { diaryFeelingLabel(it, language) },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The note's first line: the entry's own note, or the 감상's text for a 감상 with no entry behind it. */
private fun diaryRowNoteLine(entry: DiaryEntry): String? =
    (entry.visit?.note ?: entry.publicNote?.content)
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()

private val ROW_HEIGHT = 72.dp
private val COVER_SIZE = 56.dp
private val TARGET = 44.dp
