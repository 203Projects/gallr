package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.DIARY_NOTE_MAX_LENGTH
import com.gallr.shared.diary.DiaryEntryDraft
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.VisitDateOptions
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import com.gallr.shared.diary.applyEntrySave
import com.gallr.shared.diary.canRecordVisit
import com.gallr.shared.diary.normalizedDiaryNote
import com.gallr.shared.diary.visitDateOptions
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val diaryLog = AppLog.tagged("Diary")

/** Visit dates are Seoul calendar days, like the exhibitions' runs. */
private val DIARY_TIME_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

/** What the exhibition page's diary row reads (contracts/diary-ui.md, Exhibition page). */
sealed interface DiaryVisitRowState {
    data object NoEntry : DiaryVisitRowState

    data class Dated(
        val date: LocalDate,
        val feeling: VisitFeeling?,
    ) : DiaryVisitRowState

    /** A visit without a date (added in bulk), or a 감상 with no visit behind it (FR-022). */
    data object Undated : DiaryVisitRowState
}

enum class DiaryEntryFailure { SAVE, DELETE }

/**
 * The entry sheet while it is open. [initial] is what the sheet opened with, so closing can tell whether anything
 * would be lost. The publish part (US3) is not here yet.
 */
data class DiaryEntryEditorState(
    val today: LocalDate,
    val dateOptions: VisitDateOptions,
    val visitDate: LocalDate?,
    val feeling: VisitFeeling?,
    val tags: List<VisitTag>,
    val note: String,
    /** The last tag tap was refused because three are chosen; cleared by the next tag change. */
    val tagLimitReached: Boolean,
    /** A visit is saved for this show; a 감상 alone does not count. */
    val hasSavedVisit: Boolean,
    /** The diary holds something for this show (a visit or a 감상), so it can be deleted. */
    val isExistingEntry: Boolean,
    /** Deleting also deletes a 감상 that is public or in review, and the confirm says so. */
    val deleteRemovesPublicNote: Boolean,
    /** Opened from 감상 남기기: the note takes the focus. */
    val focusNote: Boolean,
    val initial: DiaryEntryDraft,
) {
    val draft: DiaryEntryDraft get() = DiaryEntryDraft(visitDate, feeling, tags, normalizedDiaryNote(note))

    val hasUnsavedChanges: Boolean get() = draft != initial

    /** A new visit needs a date (FR-003); an existing visit may stay undated. */
    val canSave: Boolean get() = visitDate != null || hasSavedVisit

    /** 날짜를 정해 주세요: an existing entry still without a date. */
    val needsDate: Boolean get() = isExistingEntry && visitDate == null

    /** A note merged from two devices can be longer than a 감상 may be. */
    val noteOverPublishLimit: Boolean get() = note.length > DIARY_NOTE_MAX_LENGTH

    val yesterday: LocalDate get() = today.minus(1, DateTimeUnit.DAY)

    /** 날짜 선택 is offered only when the run holds a date that 오늘 and 어제 do not cover. */
    val offersDatePicker: Boolean
        get() {
            val days = dateOptions.latest.toEpochDays() - dateOptions.earliest.toEpochDays() + 1
            val covered = (if (dateOptions.today) 1 else 0) + (if (dateOptions.yesterday) 1 else 0)
            return days > covered
        }
}

/** The sheet as it opens for [exhibition]; null when the show has not opened. */
internal fun diaryEntryEditor(
    exhibition: Exhibition,
    saved: ExhibitionVisit?,
    publicNote: OwnPublicNote?,
    today: LocalDate,
    focusNote: Boolean,
): DiaryEntryEditorState? {
    val options = visitDateOptions(exhibition.openingDate, exhibition.closingDate, today) ?: return null
    // A 감상 with no visit, or a visit whose note is empty, opens with the 감상's text: saving an entry without a note
    // withdraws its 감상, so the text must be there to keep it (FR-022).
    val note = saved?.note ?: publicNote?.content.orEmpty()
    val initial =
        if (saved != null) {
            DiaryEntryDraft(saved.visitDate, saved.feeling, saved.tags, normalizedDiaryNote(note))
        } else {
            // A first entry starts on today for a running show; one built from a 감상 asks for its date.
            DiaryEntryDraft(
                visitDate = if (publicNote == null) options.preselected else null,
                feeling = null,
                tags = emptyList(),
                note = normalizedDiaryNote(note),
            )
        }
    return DiaryEntryEditorState(
        today = today,
        dateOptions = options,
        visitDate = initial.visitDate,
        feeling = initial.feeling,
        tags = initial.tags,
        note = note,
        tagLimitReached = false,
        hasSavedVisit = saved != null,
        isExistingEntry = saved != null || publicNote != null,
        deleteRemovesPublicNote =
            publicNote?.moderation == PublicNoteModeration.PENDING ||
                publicNote?.moderation == PublicNoteModeration.APPROVED,
        focusNote = focusNote,
        initial = initial,
    )
}

/** Takes [date] only inside the run up to today. */
internal fun DiaryEntryEditorState.withDate(date: LocalDate): DiaryEntryEditorState =
    if (date in dateOptions.earliest..dateOptions.latest) copy(visitDate = date) else this

/** One feeling at most; tapping the chosen one clears it. */
internal fun DiaryEntryEditorState.withFeelingToggled(feeling: VisitFeeling): DiaryEntryEditorState =
    copy(feeling = if (this.feeling == feeling) null else feeling)

/** Adds or removes [tag]; a fourth is refused and says so. */
internal fun DiaryEntryEditorState.withTagToggled(tag: VisitTag): DiaryEntryEditorState =
    when {
        tag in tags -> copy(tags = tags - tag, tagLimitReached = false)
        tags.size >= VisitTag.MAX_PER_ENTRY -> copy(tagLimitReached = true)
        else -> copy(tags = tags + tag, tagLimitReached = false)
    }

/**
 * Input stops at [DIARY_NOTE_MAX_LENGTH]. A merged note already past it is kept and may only get shorter, so trimming
 * it is possible and nothing of it is cut away unasked.
 */
internal fun DiaryEntryEditorState.withNote(text: String): DiaryEntryEditorState {
    val next =
        when {
            text.length <= DIARY_NOTE_MAX_LENGTH -> text
            text.length <= note.length -> text
            note.length > DIARY_NOTE_MAX_LENGTH -> note
            else -> text.take(DIARY_NOTE_MAX_LENGTH)
        }
    return copy(note = next)
}

data class DiaryEntryUiState(
    /** Null while the entry loads and for a show that has not opened: no row then. */
    val row: DiaryVisitRowState? = null,
    /** Null while the sheet is closed. */
    val editor: DiaryEntryEditorState? = null,
    val failure: DiaryEntryFailure? = null,
)

/** What the diary row and the entry sheet can ask for; [DiaryEntryViewModel] does each. */
interface DiaryEntryActions {
    /** Opens the sheet; [focusNote] when it opens from 감상 남기기. Reopening after a failed save restores the draft. */
    fun startEditing(focusNote: Boolean = false)

    fun selectDate(date: LocalDate)

    fun toggleFeeling(feeling: VisitFeeling)

    fun toggleTag(tag: VisitTag)

    fun updateNote(text: String)

    /** Closes the sheet and saves on the device at once; an account's copy follows through its outbox. */
    fun save()

    /** Closes the sheet and deletes the entry, with its 감상. */
    fun delete()

    /** Closes the sheet without saving, dropping the draft. */
    fun discardEditing()
}

/**
 * The diary entry for one exhibition (spec 103 US1): the row on its page and the sheet that records the visit. Saves
 * go through [VisitRepository.saveEntry], which writes the device store for a guest and the account's outbox for a
 * signed-in visitor, so an entry shows at once offline and while a session refresh is pending (research P9).
 * [onVisitRecorded] reports the first save that creates the visit, as the visit prompt did.
 */
class DiaryEntryViewModel(
    private val exhibition: Exhibition,
    private val visitRepository: VisitRepository,
    private val publicNoteRepository: PublicNoteRepository,
    private val onVisitRecorded: () -> Unit,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = DIARY_TIME_ZONE,
) : ViewModel(),
    DiaryEntryActions {
    private data class SavedEntry(
        val visit: ExhibitionVisit?,
        val publicNote: OwnPublicNote?,
    )

    private data class Session(
        val editor: DiaryEntryEditorState? = null,
        val failure: DiaryEntryFailure? = null,
        /** The draft a failed save leaves, offered again when the sheet reopens. */
        val unsaved: DiaryEntryEditorState? = null,
    )

    private val savedEntry =
        combine(
            visitRepository.observeVisits().map { visits -> visits.firstOrNull { it.exhibitionId == exhibition.id } },
            publicNoteRepository.observeOwnNotes().map { notes ->
                notes.firstOrNull { it.exhibitionId == exhibition.id }
            },
            ::SavedEntry,
        )

    private val session = MutableStateFlow(Session())

    val state: StateFlow<DiaryEntryUiState> =
        combine(savedEntry, session) { saved, session ->
            DiaryEntryUiState(
                row = rowState(saved),
                editor = session.editor,
                failure = session.failure,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryEntryUiState())

    private fun today(): LocalDate = clock.todayIn(timeZone)

    private fun rowState(saved: SavedEntry): DiaryVisitRowState? {
        if (!canRecordVisit(exhibition, today())) return null
        val visit = saved.visit
        val date = visit?.visitDate
        return when {
            date != null -> DiaryVisitRowState.Dated(date, visit.feeling)
            visit != null || saved.publicNote != null -> DiaryVisitRowState.Undated
            else -> DiaryVisitRowState.NoEntry
        }
    }

    override fun startEditing(focusNote: Boolean) {
        if (session.value.editor != null) return
        viewModelScope.launch {
            val saved = savedEntry.first()
            session.update { current ->
                if (current.editor != null) return@update current
                val editor =
                    current.unsaved?.copy(focusNote = focusNote)
                        ?: diaryEntryEditor(exhibition, saved.visit, saved.publicNote, today(), focusNote)
                current.copy(editor = editor)
            }
        }
    }

    override fun selectDate(date: LocalDate) = edit { it.withDate(date) }

    override fun toggleFeeling(feeling: VisitFeeling) = edit { it.withFeelingToggled(feeling) }

    override fun toggleTag(tag: VisitTag) = edit { it.withTagToggled(tag) }

    override fun updateNote(text: String) = edit { it.withNote(text) }

    private fun edit(change: (DiaryEntryEditorState) -> DiaryEntryEditorState) {
        session.update { current -> current.copy(editor = current.editor?.let(change)) }
    }

    override fun save() {
        val editor = session.value.editor ?: return
        if (!editor.canSave) return
        session.value = Session()
        if (editor.hasSavedVisit && !editor.hasUnsavedChanges) return
        viewModelScope.launch {
            val existing = savedEntry.first().visit
            val now = clock.now()
            try {
                visitRepository.saveEntry(
                    applyEntrySave(
                        existing = existing,
                        exhibition = exhibition,
                        draft = editor.draft,
                        now = now,
                        recordId = "${exhibition.id}:${now.toEpochMilliseconds()}",
                    ),
                )
                if (existing == null) onVisitRecorded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diaryLog.warn("diary_entry_save_failed", error)
                session.update { it.copy(failure = DiaryEntryFailure.SAVE, unsaved = editor.copy(focusNote = false)) }
            }
        }
    }

    override fun delete() {
        session.value = Session()
        viewModelScope.launch {
            try {
                visitRepository.deleteEntry(exhibition.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diaryLog.warn("diary_entry_delete_failed", error)
                session.update { it.copy(failure = DiaryEntryFailure.DELETE) }
            }
        }
    }

    /** Closing without saving also lets go of a draft a failed save left, and of its error line. */
    override fun discardEditing() {
        session.value = Session()
    }

    companion object {
        fun factory(
            exhibition: Exhibition,
            visitRepository: VisitRepository,
            publicNoteRepository: PublicNoteRepository,
            onVisitRecorded: () -> Unit,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    DiaryEntryViewModel(
                        exhibition = exhibition,
                        visitRepository = visitRepository,
                        publicNoteRepository = publicNoteRepository,
                        onVisitRecorded = onVisitRecorded,
                    )
                }
            }
    }
}
