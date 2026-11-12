package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.diary.Diary
import com.gallr.shared.diary.DiaryEntry
import com.gallr.shared.diary.DiaryFilter
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.buildDiary
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.DiaryDeviceStateRepository
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val diaryListLog = AppLog.tagged("Diary")

/** Visit dates and the strip's "ended" are Seoul calendar days, like the exhibitions' runs. */
private val DIARY_LIST_TIME_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

/** MY → 일기 as it shows. [diary] is null while the entries load and after a failed read. */
data class DiaryUiState(
    val diary: Diary? = null,
    val filter: DiaryFilter = DiaryFilter.ALL,
    val language: AppLanguage = AppLanguage.KO,
    /** Every entry, whatever the filter: the 일기 count in MY's counts row. */
    val entryCount: Int = 0,
    val loadFailed: Boolean = false,
    /** A 감상 was approved and its author has not seen 감상이 공개됐어요 yet (spec 103 US3). */
    val approvalLinePending: Boolean = false,
) {
    val isLoading: Boolean get() = diary == null && !loadFailed
}

/**
 * The exhibition a diary row opens the entry sheet on: the catalogue's show, or one rebuilt from the record kept at
 * the time for a show that has left the catalogue. Null for a 감상 on a show the app knows nothing of, which has no
 * run to choose a date in.
 */
internal fun diaryEntryExhibition(
    entry: DiaryEntry,
    catalogue: List<Exhibition>,
): Exhibition? {
    catalogue.firstOrNull { it.id == entry.exhibitionId }?.let { return it }
    val snapshot = entry.snapshot ?: return null
    return Exhibition(
        id = entry.exhibitionId,
        nameKo = snapshot.nameKo,
        nameEn = snapshot.nameEn,
        venueNameKo = snapshot.venueNameKo,
        venueNameEn = snapshot.venueNameEn,
        cityKo = "",
        cityEn = "",
        regionKo = "",
        regionEn = "",
        openingDate = snapshot.openingDate,
        closingDate = snapshot.closingDate,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = snapshot.coverImageUrl,
        galleryId = snapshot.galleryId,
    )
}

/**
 * MY → 일기 (spec 103 US2): every entry from [VisitRepository] and the account's own 감상, grouped by [buildDiary].
 * Nothing from the LIST tab is an input (SC-008), and the full catalogue only names shows and counts ended saves. The
 * visits come from the device or the account outbox, so a pending session refresh never empties the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    visitRepository: VisitRepository,
    private val publicNoteRepository: PublicNoteRepository,
    private val deviceStateRepository: DiaryDeviceStateRepository,
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    savedIds: Flow<Set<String>>,
    syncStatus: Flow<MyGallrSyncStatus>,
    authState: Flow<AuthState>,
    language: Flow<AppLanguage>,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = DIARY_LIST_TIME_ZONE,
) : ViewModel() {
    private data class Entries(
        val visits: List<ExhibitionVisit>,
        val notes: List<OwnPublicNote>,
        val pending: Map<String, PendingNoteMutation>,
    )

    private data class Context(
        val catalogue: List<Exhibition>,
        val savedIds: Set<String>,
        val dismissedCount: Int,
        val syncFailed: Boolean,
        val sessionRefreshPending: Boolean,
    )

    private val filter = MutableStateFlow(DiaryFilter.ALL)
    private val readAttempt = MutableStateFlow(0)

    private val visits: Flow<Result<List<ExhibitionVisit>>> =
        readAttempt.flatMapLatest {
            visitRepository
                .observeVisits()
                .map { Result.success(it) }
                .catch { error ->
                    diaryListLog.warn("diary_list_failed", error)
                    emit(Result.failure(error))
                }
        }

    private val entries: Flow<Result<Entries>> =
        combine(
            visits,
            publicNoteRepository.observeOwnNotes().catch { error ->
                diaryListLog.warn("diary_notes_read_failed", error)
                emit(emptyList())
            },
            publicNoteRepository.observePendingNoteMutations().catch { emit(emptyMap()) },
        ) { visits, notes, pending -> visits.map { Entries(it, notes, pending) } }

    private val context: Flow<Context> =
        combine(
            exhibitionsState.map { (it as? ExhibitionListState.Success)?.exhibitions.orEmpty() },
            savedIds,
            deviceStateRepository.dismissedEndedSavedCount.catch { error ->
                diaryListLog.warn("diary_strip_read_failed", error)
                emit(0)
            },
            syncStatus.map { it == MyGallrSyncStatus.RETRY_NEEDED }.distinctUntilChanged(),
            authState.map { (it as? AuthState.Authenticated)?.isSessionRefreshPending == true }.distinctUntilChanged(),
        ) { catalogue, saved, dismissed, syncFailed, refreshPending ->
            Context(catalogue, saved, dismissed, syncFailed, refreshPending)
        }

    val state: StateFlow<DiaryUiState> =
        combine(entries, context, filter, language) { entries, context, filter, language ->
            entries.fold(
                onSuccess = { read -> uiState(read, context, filter, language) },
                onFailure = { DiaryUiState(filter = filter, language = language, loadFailed = true) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    fun selectFilter(filter: DiaryFilter) {
        this.filter.value = filter
    }

    /** Closes the strip until more saved shows have ended than now (US2 scenario 7). */
    fun dismissEndedSavedStrip() {
        val count = state.value.diary?.endedSavedCount ?: return
        viewModelScope.launch {
            try {
                deviceStateRepository.dismissEndedSavedStrip(count)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diaryListLog.warn("diary_strip_dismiss_failed", error)
            }
        }
    }

    /**
     * 감상이 공개됐어요 is on screen: each approval it announces is recorded on the account, so the line shows once. The
     * list keeps it in view for this opening.
     */
    fun acknowledgeApprovals() {
        val exhibitionIds =
            state.value.diary
                ?.let { it.months.flatMap { month -> month.entries } + it.undated }
                .orEmpty()
                .filter(DiaryEntry::showApprovalLine)
                .map(DiaryEntry::exhibitionId)
        if (exhibitionIds.isEmpty()) return
        viewModelScope.launch {
            exhibitionIds.forEach { exhibitionId ->
                try {
                    publicNoteRepository.acknowledgeApproval(exhibitionId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    diaryListLog.warn("diary_approval_ack_failed", error)
                }
            }
        }
    }

    fun retry() {
        readAttempt.value += 1
    }

    /** The show a row opens the entry sheet on; see [diaryEntryExhibition]. */
    fun exhibitionFor(entry: DiaryEntry): Exhibition? =
        diaryEntryExhibition(
            entry,
            (exhibitionsState.value as? ExhibitionListState.Success)?.exhibitions.orEmpty(),
        )

    private fun uiState(
        read: Entries,
        context: Context,
        filter: DiaryFilter,
        language: AppLanguage,
    ): DiaryUiState {
        val diary =
            buildDiary(
                visits = read.visits,
                publicNotes = read.notes,
                catalogue = context.catalogue,
                savedIds = context.savedIds,
                today = clock.todayIn(timeZone),
                filter = filter,
                dismissedEndedSavedCount = context.dismissedCount,
                pendingNoteMutations = read.pending,
                syncFailed = context.syncFailed,
                sessionRefreshPending = context.sessionRefreshPending,
                language = language,
            )
        val visitIds = read.visits.mapTo(mutableSetOf(), ExhibitionVisit::exhibitionId)
        return DiaryUiState(
            diary = diary,
            filter = filter,
            language = language,
            entryCount = visitIds.size + read.notes.count { it.exhibitionId !in visitIds },
            approvalLinePending = read.notes.any { it.moderation == PublicNoteModeration.APPROVED && !it.approvalSeen },
        )
    }

    companion object {
        fun factory(
            visitRepository: VisitRepository,
            publicNoteRepository: PublicNoteRepository,
            deviceStateRepository: DiaryDeviceStateRepository,
            exhibitionsState: StateFlow<ExhibitionListState>,
            savedIds: Flow<Set<String>>,
            syncStatus: Flow<MyGallrSyncStatus>,
            authState: Flow<AuthState>,
            language: Flow<AppLanguage>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    DiaryViewModel(
                        visitRepository = visitRepository,
                        publicNoteRepository = publicNoteRepository,
                        deviceStateRepository = deviceStateRepository,
                        exhibitionsState = exhibitionsState,
                        savedIds = savedIds,
                        syncStatus = syncStatus,
                        authState = authState,
                        language = language,
                    )
                }
            }
    }
}
