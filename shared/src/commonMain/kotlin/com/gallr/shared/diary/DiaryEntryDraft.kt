package com.gallr.shared.diary

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** The longest note the sheet accepts as typed input, and the longest text that can be published. */
const val DIARY_NOTE_MAX_LENGTH = 280

/** Two full notes joined by a sign-in merge (research P5); the server refuses anything longer. */
const val DIARY_MERGED_NOTE_MAX_LENGTH = 2 * DIARY_NOTE_MAX_LENGTH + 2

/** What the entry sheet saves. The sheet asks for a date before it saves; this type does not. */
data class DiaryEntryDraft(
    val visitDate: LocalDate?,
    val feeling: VisitFeeling?,
    val tags: List<VisitTag>,
    val note: String?,
)

/**
 * The entry after a sheet save. The first save creates it with the exhibition's snapshot, [recordId] and [now] as the
 * record time; a later save keeps those and replaces only the four diary fields. Either way [now] becomes the entry's
 * update time, which decides between devices. Refuses more than [VisitTag.MAX_PER_ENTRY] or repeated tags, and a
 * note longer than [DIARY_MERGED_NOTE_MAX_LENGTH], which the server would refuse and so block the account's outbox.
 */
fun applyEntrySave(
    existing: ExhibitionVisit?,
    exhibition: Exhibition,
    draft: DiaryEntryDraft,
    now: Instant,
    recordId: String,
): ExhibitionVisit {
    require(existing == null || existing.exhibitionId == exhibition.id) { "entry belongs to another exhibition" }
    require(draft.tags.size <= VisitTag.MAX_PER_ENTRY) { "too many tags" }
    require(draft.tags.distinct().size == draft.tags.size) { "repeated tag" }
    val note = normalizedDiaryNote(draft.note)
    require(note == null || note.length <= DIARY_MERGED_NOTE_MAX_LENGTH) { "note too long" }
    val entry =
        existing ?: ExhibitionVisit(
            clientRecordId = recordId,
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = now,
        )
    return entry.copy(
        visitDate = draft.visitDate,
        feelingWire = draft.feeling?.wire,
        tagWires = draft.tags.map(VisitTag::wire),
        note = note,
        entryUpdatedAt = now,
    )
}

/** A note as stored: trailing whitespace removed, and a blank note is no note. */
fun normalizedDiaryNote(note: String?): String? = note?.trimEnd()?.takeIf { it.isNotBlank() }
