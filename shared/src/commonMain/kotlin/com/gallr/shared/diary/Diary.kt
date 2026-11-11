package com.gallr.shared.diary

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** One row of MY → 일기: a diary entry, or a 감상 with no entry behind it (undated, [visit] null). */
data class DiaryEntry(
    val exhibitionId: String,
    /** Null only for a 감상 on a show that is no longer in the catalogue. */
    val snapshot: ExhibitionVisitSnapshot?,
    val visit: ExhibitionVisit?,
    val publicNote: OwnPublicNote?,
    /** Null when the entry has neither a note nor a public note. */
    val noteStatus: PublicNoteStatus?,
    /** 감상이 공개됐어요, shown once for an approval the author has not seen. */
    val showApprovalLine: Boolean,
)

data class DiaryMonth(
    val year: Int,
    val month: Int,
    val entries: List<DiaryEntry>,
)

enum class DiaryFilter { ALL, PUBLISHED_NOTES }

data class Diary(
    /** By visit date, newest month first. */
    val months: List<DiaryMonth>,
    /** 날짜 미정, shown after every month. */
    val undated: List<DiaryEntry>,
    /** Saved shows that have closed and have no entry. */
    val endedSavedCount: Int,
    val showEndedSavedStrip: Boolean,
    /** True when there is no entry at all, whatever the filter; a filter that hides every entry leaves this false. */
    val isEmpty: Boolean,
)

/**
 * MY → 일기 (spec US2). Every visit is listed whatever the LIST tab shows (SC-008: nothing from it is an input). Months
 * run newest first, entries in a month by visit date newest first, then by name in [language]; entries with no visit
 * date and 감상 with no entry come last in 날짜 미정, by record time newest first (a 감상's request time). The strip
 * counts saved shows that closed before [today] and have no entry, and shows while that count is above the count the
 * visitor last closed it at. [DiaryFilter.PUBLISHED_NOTES] keeps entries whose note has any status but private.
 */
fun buildDiary(
    visits: List<ExhibitionVisit>,
    publicNotes: List<OwnPublicNote>,
    catalogue: List<Exhibition>,
    savedIds: Set<String>,
    today: LocalDate,
    filter: DiaryFilter,
    dismissedEndedSavedCount: Int,
    pendingNoteMutations: Map<String, PendingNoteMutation>,
    syncFailed: Boolean,
    sessionRefreshPending: Boolean,
    language: AppLanguage,
): Diary {
    val notesById = publicNotes.associateBy(OwnPublicNote::exhibitionId)
    val catalogueById = catalogue.associateBy(Exhibition::id)
    val visitIds = visits.mapTo(mutableSetOf(), ExhibitionVisit::exhibitionId)

    fun entry(
        exhibitionId: String,
        snapshot: ExhibitionVisitSnapshot?,
        visit: ExhibitionVisit?,
    ): DiaryEntry {
        val note = notesById[exhibitionId]
        return DiaryEntry(
            exhibitionId = exhibitionId,
            snapshot = snapshot,
            visit = visit,
            publicNote = note,
            noteStatus =
                diaryNoteStatus(
                    entryNote = visit?.note,
                    publicNote = note,
                    pendingNoteMutation = pendingNoteMutations[exhibitionId],
                    syncFailed = syncFailed,
                    sessionRefreshPending = sessionRefreshPending,
                ),
            showApprovalLine = note?.moderation == PublicNoteModeration.APPROVED && !note.approvalSeen,
        )
    }

    val entries =
        visits.map { entry(it.exhibitionId, it.snapshot, it) } +
            publicNotes
                .filterNot { it.exhibitionId in visitIds }
                .map { note ->
                    entry(note.exhibitionId, catalogueById[note.exhibitionId]?.let(ExhibitionVisitSnapshot::from), null)
                }
    val shown = if (filter == DiaryFilter.PUBLISHED_NOTES) entries.filter(DiaryEntry::hasPublicStatus) else entries
    val (dated, undated) = shown.partition { it.visitDate() != null }
    val entryIds = entries.mapTo(mutableSetOf(), DiaryEntry::exhibitionId)
    val endedSavedCount =
        savedIds.count { id ->
            id !in entryIds && catalogueById[id]?.let { it.closingDate < today } == true
        }

    return Diary(
        months = monthsOf(dated, language),
        undated = undated.sortedWith(compareByDescending<DiaryEntry> { it.recordTime() }.thenBy { it.exhibitionId }),
        endedSavedCount = endedSavedCount,
        showEndedSavedStrip = endedSavedCount > 0 && endedSavedCount > dismissedEndedSavedCount,
        isEmpty = entries.isEmpty(),
    )
}

private fun monthsOf(
    dated: List<DiaryEntry>,
    language: AppLanguage,
): List<DiaryMonth> {
    val order =
        compareByDescending<DiaryEntry> { it.visitDate() }
            .thenBy { it.snapshot?.localizedName(language).orEmpty() }
            .thenByDescending { it.visit?.createdAt }
            .thenBy { it.exhibitionId }
    return dated
        .sortedWith(order)
        .groupBy { entry -> entry.visitDate()?.let { it.year to it.month.ordinal + 1 } }
        .mapNotNull { (yearMonth, entries) -> yearMonth?.let { DiaryMonth(it.first, it.second, entries) } }
}

private fun DiaryEntry.visitDate(): LocalDate? = visit?.visitDate

private fun DiaryEntry.hasPublicStatus(): Boolean = noteStatus != null && noteStatus != PublicNoteStatus.PRIVATE

private fun DiaryEntry.recordTime(): Instant? = visit?.createdAt ?: publicNote?.requestedAt
