package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.DIARY_NOTE_MAX_LENGTH
import com.gallr.shared.diary.DiaryEntryDraft
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.diary.VisitDateOptions
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import com.gallr.shared.diary.applyEntrySave
import com.gallr.shared.diary.canRecordVisit
import com.gallr.shared.diary.diaryNoteStatus
import com.gallr.shared.diary.normalizedDiaryNote
import com.gallr.shared.diary.visitDateOptions
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    data class Undated(
        val feeling: VisitFeeling?,
    ) : DiaryVisitRowState
}

/**
 * What went wrong after the sheet closed. [PUBLISH] covers both choices of who can see the note. [REMOVED_ELSEWHERE]:
 * the account no longer holds the entry (deleted on another device), so the sheet closed instead of re-creating it.
 */
enum class DiaryEntryFailure { SAVE, DELETE, PUBLISH, REMOVED_ELSEWHERE }

/** 누가 볼 수 있나요? (owner decision 10): the note stays private, or it is asked for on the exhibition page. */
enum class DiaryVisibility { ONLY_ME, EXHIBITION_PAGE }

/** What a save does to the 감상 besides the entry. */
enum class DiaryPublishAction { NONE, PUBLISH, UNPUBLISH }

/**
 * The entry sheet while it is open. [initial] and [initialVisibility] are what the sheet opened with, so closing can
 * tell whether anything would be lost. [visibility] is the visitor's choice; [shownVisibility] is what the sheet can
 * show for it (nothing goes on the page without a note). [isSignedIn], [publicNote] and [noteStatus] follow the account
 * while the sheet is open.
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
    /** Opened from 감상 남기기, or asked to edit and ask again: the note takes the focus. */
    val focusNote: Boolean,
    val initial: DiaryEntryDraft,
    val isSignedIn: Boolean = false,
    val visibility: DiaryVisibility = DiaryVisibility.ONLY_ME,
    val initialVisibility: DiaryVisibility = DiaryVisibility.ONLY_ME,
    /** The author asked to send the note for review again (수정한 내용으로 다시 요청, 수정해서 다시 요청). */
    val askAgain: Boolean = false,
    val publicNote: OwnPublicNote? = null,
    val noteStatus: PublicNoteStatus? = null,
    /** 감상이 공개됐어요, for an approval the author had not seen when the sheet opened. */
    val showApprovalLine: Boolean = false,
) {
    val draft: DiaryEntryDraft get() = DiaryEntryDraft(visitDate, feeling, tags, normalizedDiaryNote(note))

    val hasUnsavedChanges: Boolean get() = draft != initial || visibility != initialVisibility || askAgain

    /**
     * A new visit needs a date (FR-003). An existing entry may stay undated, including a 감상 from app 1.15 with no
     * visit behind it: changing only who can see it, or its note, must not make the visitor invent a date.
     */
    val canSave: Boolean get() = visitDate != null || hasSavedVisit || isExistingEntry

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

    private val savedNote: String? get() = normalizedDiaryNote(note)

    /** The note can become a 감상: 1–280 characters. */
    val noteFitsPublish: Boolean get() = savedNote?.length?.let { it <= DIARY_NOTE_MAX_LENGTH } == true

    /** Without a note nothing can be on the page, so 나만 보기 shows whatever was chosen. */
    val shownVisibility: DiaryVisibility get() = if (savedNote == null) DiaryVisibility.ONLY_ME else visibility

    /** 전시 페이지에 공개 can be chosen: signed in, with a note that fits or one already asked for. */
    val publicChoiceEnabled: Boolean
        get() = isSignedIn && (noteFitsPublish || (visibility == DiaryVisibility.EXHIBITION_PAGE && savedNote != null))

    /** A 감상 that is public or in review; deleting the entry, or saving it without a note, removes it. */
    val deleteRemovesPublicNote: Boolean
        get() =
            publicNote?.moderation == PublicNoteModeration.PENDING ||
                publicNote?.moderation == PublicNoteModeration.APPROVED

    /** Saving now would leave no note and so delete the public 감상 (the server withdraws it): ask first. */
    val savingRemovesPublicNote: Boolean get() = deleteRemovesPublicNote && savedNote == null

    private val asksForThePage: Boolean
        get() = isSignedIn && !askAgain && visibility == DiaryVisibility.EXHIBITION_PAGE

    /**
     * 수정한 내용으로 다시 요청: the page shows, or review would approve, an older text than the note. Staff review the
     * text that was sent, so an edit after sending needs asking again too.
     */
    val offersAskAgainWithEdit: Boolean
        get() =
            asksForThePage &&
                deleteRemovesPublicNote &&
                noteFitsPublish &&
                savedNote != publicNote?.content

    /**
     * The visitor asked to send the note again and the next save will: 저장하면 다시 검토를 요청해요 stands where the
     * button was. Choosing 나만 보기 takes the request back.
     */
    val askAgainPending: Boolean
        get() = isSignedIn && askAgain && shownVisibility == DiaryVisibility.EXHIBITION_PAGE && noteFitsPublish

    /** 수정해서 다시 요청: staff did not publish the note. */
    val offersEditAndAskAgain: Boolean
        get() = asksForThePage && publicNote?.moderation == PublicNoteModeration.REJECTED

    /**
     * Editing the note never changes the public text by itself (FR-020): a save publishes a note that is not on the
     * page yet or that the author asked to send again, and 나만 보기 takes it off. A save that leaves no note needs no
     * action, because the entry's own save withdraws the 감상.
     */
    val publishAction: DiaryPublishAction
        get() =
            when {
                !isSignedIn -> {
                    DiaryPublishAction.NONE
                }

                shownVisibility == DiaryVisibility.EXHIBITION_PAGE -> {
                    if (noteFitsPublish && (publicNote == null || askAgain)) {
                        DiaryPublishAction.PUBLISH
                    } else {
                        DiaryPublishAction.NONE
                    }
                }

                visibility == DiaryVisibility.ONLY_ME && publicNote != null && savedNote != null -> {
                    DiaryPublishAction.UNPUBLISH
                }

                else -> {
                    DiaryPublishAction.NONE
                }
            }
}

/**
 * The sheet as it opens for [exhibition]; null when the show has not opened. [focusNote] is the opening from 감상
 * 남기기: it preselects 전시 페이지에 공개 only when the entry has no note yet, since the visitor came to write one to
 * show. A note already kept private stays 나만 보기 (owner decision 10): a choice changes only when the visitor taps it.
 */
internal fun diaryEntryEditor(
    exhibition: Exhibition,
    saved: ExhibitionVisit?,
    publicNote: OwnPublicNote?,
    today: LocalDate,
    focusNote: Boolean,
    isSignedIn: Boolean = false,
    noteStatus: PublicNoteStatus? = diaryNoteStatus(saved?.note, publicNote, null, false, false),
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
    val writingFirstNote = focusNote && normalizedDiaryNote(saved?.note) == null
    val visibility =
        if (publicNote != null || writingFirstNote) DiaryVisibility.EXHIBITION_PAGE else DiaryVisibility.ONLY_ME
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
        focusNote = focusNote,
        initial = initial,
        isSignedIn = isSignedIn,
        visibility = visibility,
        initialVisibility = visibility,
        publicNote = publicNote,
        noteStatus = noteStatus,
        showApprovalLine = publicNote?.moderation == PublicNoteModeration.APPROVED && !publicNote.approvalSeen,
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

/** 전시 페이지에 공개 is taken only when it can be chosen; 나만 보기 always. */
internal fun DiaryEntryEditorState.withVisibility(choice: DiaryVisibility): DiaryEntryEditorState =
    when (choice) {
        DiaryVisibility.EXHIBITION_PAGE -> if (publicChoiceEnabled) copy(visibility = choice) else this
        DiaryVisibility.ONLY_ME -> copy(visibility = choice, askAgain = false)
    }

/** The next save sends the note for review again; the note takes the focus to be edited first. */
internal fun DiaryEntryEditorState.withAskAgain(): DiaryEntryEditorState = copy(askAgain = true, focusNote = true)

data class DiaryEntryUiState(
    /** Null while the entry loads and for a show that has not opened: no row then. */
    val row: DiaryVisitRowState? = null,
    /** Null while the sheet is closed. */
    val editor: DiaryEntryEditorState? = null,
    val failure: DiaryEntryFailure? = null,
    /** The signed-in author's own 감상 on this show, and its status as the diary words it. */
    val publicNote: OwnPublicNote? = null,
    val noteStatus: PublicNoteStatus? = null,
    /** A save, publish or delete the sheet started is still going; its failure may yet show. */
    val isWorking: Boolean = false,
)

/** What the diary row and the entry sheet can ask for; [DiaryEntryViewModel] does each. */
interface DiaryEntryActions {
    /**
     * Opens the sheet; [focusNote] when it opens from 감상 남기기 (the note first; 전시 페이지에 공개 preselected only
     * for an entry without a note). Reopening after a failed save restores the draft. A signed-in visitor's account is
     * read again as the sheet opens, so an entry deleted on another device is not edited back into being.
     */
    fun startEditing(focusNote: Boolean = false)

    /**
     * 수정 on the reader's own 감상: opens the sheet on the note, except for a 감상 staff did not publish, which opens on
     * 수정해서 다시 요청 so the keyboard does not hide it; without it a save leaves the note 공개 안 됨 (FR-020).
     */
    fun editOwnNote()

    fun selectDate(date: LocalDate)

    fun toggleFeeling(feeling: VisitFeeling)

    fun toggleTag(tag: VisitTag)

    fun updateNote(text: String)

    fun selectVisibility(visibility: DiaryVisibility)

    /** 수정한 내용으로 다시 요청 or 수정해서 다시 요청: the next save asks for review again. */
    fun askAgain()

    /** Closes the sheet and saves on the device at once; an account's copy and its 감상 follow through the outbox. */
    fun save()

    /** Closes the sheet and deletes the entry, with its 감상. */
    fun delete()

    /** Closes the sheet without saving, dropping the draft. */
    fun discardEditing()
}

/**
 * The diary entry for one exhibition (spec 103 US1, US3): the row on its page and the sheet that records the visit and
 * says who can see its note. Saves go through [VisitRepository.saveEntry], and publishing through
 * [PublicNoteRepository], both queued in the account's outbox behind each other, so an entry and its 감상 show at once
 * offline and while a session refresh is pending (research P9): the status then reads 보내는 중, never a failure.
 * [onVisitRecorded] reports the first save that creates the visit, as the visit prompt did.
 */
class DiaryEntryViewModel(
    private val exhibition: Exhibition,
    private val visitRepository: VisitRepository,
    private val publicNoteRepository: PublicNoteRepository,
    authState: Flow<AuthState>,
    syncStatus: Flow<MyGallrSyncStatus>,
    private val onVisitRecorded: () -> Unit,
    private val refreshAccount: suspend () -> Unit = {},
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = DIARY_TIME_ZONE,
) : ViewModel(),
    DiaryEntryActions {
    private data class SavedEntry(
        val visit: ExhibitionVisit?,
        val publicNote: OwnPublicNote?,
        val pending: PendingNoteMutation?,
    )

    private data class Reader(
        val signedIn: Boolean,
        val refreshPending: Boolean,
        val syncFailed: Boolean,
    )

    private data class Session(
        val editor: DiaryEntryEditorState? = null,
        val failure: DiaryEntryFailure? = null,
        /** The draft a failed save leaves, offered again when the sheet reopens. */
        val unsaved: DiaryEntryEditorState? = null,
        val working: Boolean = false,
    )

    private val savedEntry: Flow<SavedEntry> =
        combine(
            visitRepository.observeVisits().map { visits -> visits.firstOrNull { it.exhibitionId == exhibition.id } },
            publicNoteRepository.observeOwnNotes().map { notes ->
                notes.firstOrNull { it.exhibitionId == exhibition.id }
            },
            publicNoteRepository.observePendingNoteMutations().map { it[exhibition.id] },
            ::SavedEntry,
        )

    private val reader: Flow<Reader> =
        combine(authState, syncStatus) { auth, sync ->
            Reader(
                signedIn = auth is AuthState.Authenticated,
                refreshPending = (auth as? AuthState.Authenticated)?.isSessionRefreshPending == true,
                syncFailed = sync == MyGallrSyncStatus.RETRY_NEEDED,
            )
        }.distinctUntilChanged()

    private val session = MutableStateFlow(Session())

    /** Keeps the open sheet's sign-in, 감상 and status current; runs only while the sheet is open. */
    private var followJob: Job? = null

    /** The account read started as the sheet opened; a save waits for it before it writes. */
    private var openingRead: Job? = null

    /**
     * The sheet opened, signed in, on an entry the account holds: if the account stops holding it, it was deleted on
     * another device. `update_visit` creates a missing entry on the server, so a save must not go out then.
     */
    private var editsAccountEntry = false

    private val accountReads = AccountReadThrottle(clock)

    val state: StateFlow<DiaryEntryUiState> =
        combine(savedEntry, reader, session) { saved, reader, session ->
            val status = noteStatus(saved, reader)
            DiaryEntryUiState(
                row = rowState(saved),
                editor = session.editor,
                failure = session.failure,
                publicNote = saved.publicNote,
                noteStatus = status,
                isWorking = session.working,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryEntryUiState())

    private fun today(): LocalDate = clock.todayIn(timeZone)

    private fun noteStatus(
        saved: SavedEntry,
        reader: Reader,
    ): PublicNoteStatus? =
        diaryNoteStatus(
            entryNote = saved.visit?.note,
            publicNote = saved.publicNote,
            pendingNoteMutation = saved.pending,
            syncFailed = reader.syncFailed,
            sessionRefreshPending = reader.refreshPending,
        )

    private fun rowState(saved: SavedEntry): DiaryVisitRowState? {
        if (!canRecordVisit(exhibition, today())) return null
        val visit = saved.visit
        val date = visit?.visitDate
        return when {
            date != null -> DiaryVisitRowState.Dated(date, visit.feeling)
            visit != null || saved.publicNote != null -> DiaryVisitRowState.Undated(visit?.feeling)
            else -> DiaryVisitRowState.NoEntry
        }
    }

    /**
     * The exhibition page is shown (entered or returned to): a signed-in visitor's account is read again, at most once
     * per [ACCOUNT_READ_INTERVAL], so a review decided meanwhile shows on the author's own card as in the diary.
     */
    fun onPageShown() {
        viewModelScope.launch {
            if (reader.first().signedIn && accountReads.tryStart()) readAccount()
        }
    }

    override fun startEditing(focusNote: Boolean) = open(focusNote, ownNote = false)

    override fun editOwnNote() = open(focusNote = true, ownNote = true)

    private fun open(
        focusNote: Boolean,
        ownNote: Boolean,
    ) {
        if (session.value.editor != null) return
        viewModelScope.launch {
            val saved = savedEntry.first()
            val reader = reader.first()
            var opened: DiaryEntryEditorState? = null
            session.update { current ->
                if (current.editor != null) return@update current
                val built =
                    current.unsaved?.copy(focusNote = focusNote, showApprovalLine = false)
                        ?: diaryEntryEditor(
                            exhibition = exhibition,
                            saved = saved.visit,
                            publicNote = saved.publicNote,
                            today = today(),
                            focusNote = focusNote,
                            isSignedIn = reader.signedIn,
                            noteStatus = noteStatus(saved, reader),
                        )
                // The note's keyboard would hide 수정해서 다시 요청; choosing it focuses the note then.
                val opensOnAskAgain = ownNote && built?.offersEditAndAskAgain == true
                val editor = if (opensOnAskAgain) built?.copy(focusNote = false) else built
                opened = editor
                current.copy(editor = editor)
            }
            val editor = opened ?: return@launch
            editsAccountEntry = reader.signedIn && editor.isExistingEntry
            if (reader.signedIn) openingRead = viewModelScope.launch { readAccount() }
            followAccount()
            if (editor.showApprovalLine) acknowledgeApproval()
        }
    }

    /** Reads the account again (the coordinator's own sync); its failure leaves the device's copy as it was. */
    private suspend fun readAccount() {
        try {
            refreshAccount()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diaryLog.warn("diary_account_read_failed", error)
        }
    }

    /** The account held the entry when the sheet opened and holds neither its visit nor its 감상 now. */
    private fun removedElsewhere(
        saved: SavedEntry,
        reader: Reader,
    ): Boolean = editsAccountEntry && reader.signedIn && saved.visit == null && saved.publicNote == null

    private fun followAccount() {
        followJob?.cancel()
        followJob =
            viewModelScope.launch {
                combine(savedEntry, reader, ::Pair).collect { (saved, reader) ->
                    if (removedElsewhere(saved, reader)) {
                        // Deleted on another device: say so rather than keep a sheet whose save would re-create it.
                        session.value = Session(failure = DiaryEntryFailure.REMOVED_ELSEWHERE)
                        stopFollowing()
                        return@collect
                    }
                    session.update { current ->
                        current.copy(
                            editor =
                                current.editor?.copy(
                                    isSignedIn = reader.signedIn,
                                    publicNote = saved.publicNote,
                                    noteStatus = noteStatus(saved, reader),
                                ),
                        )
                    }
                }
            }
    }

    private fun stopFollowing() {
        val job = followJob
        followJob = null
        job?.cancel()
    }

    /** The one-time line is on screen: the account records that the author saw the approval. */
    private suspend fun acknowledgeApproval() {
        try {
            publicNoteRepository.acknowledgeApproval(exhibition.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diaryLog.warn("diary_approval_ack_failed", error)
        }
    }

    override fun selectDate(date: LocalDate) = edit { it.withDate(date) }

    override fun toggleFeeling(feeling: VisitFeeling) = edit { it.withFeelingToggled(feeling) }

    override fun toggleTag(tag: VisitTag) = edit { it.withTagToggled(tag) }

    override fun updateNote(text: String) = edit { it.withNote(text) }

    override fun selectVisibility(visibility: DiaryVisibility) = edit { it.withVisibility(visibility) }

    override fun askAgain() = edit { it.withAskAgain() }

    private fun edit(change: (DiaryEntryEditorState) -> DiaryEntryEditorState) {
        session.update { current -> current.copy(editor = current.editor?.let(change)) }
    }

    override fun save() {
        val editor = session.value.editor ?: return
        if (!editor.canSave) return
        val action = editor.publishAction
        val writeEntry = !editor.hasSavedVisit || editor.draft != editor.initial
        stopFollowing()
        if (!writeEntry && action == DiaryPublishAction.NONE) {
            session.update { Session(working = it.working) }
            return
        }
        session.value = Session(working = true)
        val read = openingRead
        viewModelScope.launch {
            // The account read the opening started may still bring news of a deletion elsewhere.
            read?.join()
            // A sheet left open may have missed a deletion made after it opened: read once more before an existing
            // entry goes out, because `update_visit` would create it again. A read that fails (offline) leaves the
            // device's copy, so the save still goes out (TODOS: offline save of an entry deleted elsewhere).
            if (editsAccountEntry) readAccount()
            if (removedElsewhere(savedEntry.first(), reader.first())) {
                session.value = Session(failure = DiaryEntryFailure.REMOVED_ELSEWHERE)
                return@launch
            }
            if (writeEntry && !writeEntry(editor)) return@launch
            when (action) {
                DiaryPublishAction.PUBLISH -> {
                    changePublication("diary_note_publish_failed") { publish(exhibition.id) }
                }

                DiaryPublishAction.UNPUBLISH -> {
                    changePublication(
                        "diary_note_unpublish_failed",
                    ) { unpublish(exhibition.id) }
                }

                DiaryPublishAction.NONE -> {
                    Unit
                }
            }
            session.update { it.copy(working = false) }
        }
    }

    /** Saves the entry; false after a failure, which keeps the draft for the next opening. */
    private suspend fun writeEntry(editor: DiaryEntryEditorState): Boolean {
        val existing = savedEntry.first().visit
        val now = clock.now()
        return try {
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
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diaryLog.warn("diary_entry_save_failed", error)
            session.value =
                Session(
                    failure = DiaryEntryFailure.SAVE,
                    unsaved = editor.copy(focusNote = false, showApprovalLine = false),
                )
            false
        }
    }

    private suspend fun changePublication(
        operation: String,
        change: suspend PublicNoteRepository.() -> Unit,
    ) {
        try {
            publicNoteRepository.change()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diaryLog.warn(operation, error)
            session.update { it.copy(failure = DiaryEntryFailure.PUBLISH) }
        }
    }

    override fun delete() {
        stopFollowing()
        session.value = Session(working = true)
        viewModelScope.launch {
            try {
                visitRepository.deleteEntry(exhibition.id)
                session.update { it.copy(working = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diaryLog.warn("diary_entry_delete_failed", error)
                session.value = Session(failure = DiaryEntryFailure.DELETE)
            }
        }
    }

    /** Closing without saving also lets go of a draft a failed save left, and of its error line. */
    override fun discardEditing() {
        stopFollowing()
        session.update { Session(working = it.working) }
    }

    companion object {
        fun factory(
            exhibition: Exhibition,
            visitRepository: VisitRepository,
            publicNoteRepository: PublicNoteRepository,
            authState: Flow<AuthState>,
            syncStatus: Flow<MyGallrSyncStatus>,
            onVisitRecorded: () -> Unit,
            refreshAccount: suspend () -> Unit,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    DiaryEntryViewModel(
                        exhibition = exhibition,
                        visitRepository = visitRepository,
                        publicNoteRepository = publicNoteRepository,
                        authState = authState,
                        syncStatus = syncStatus,
                        onVisitRecorded = onVisitRecorded,
                        refreshAccount = refreshAccount,
                    )
                }
            }
    }
}
