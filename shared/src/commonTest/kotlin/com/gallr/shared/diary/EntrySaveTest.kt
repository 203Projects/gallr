package com.gallr.shared.diary

import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** What a sheet save writes: the first save creates the entry, later ones replace only the diary fields. */
class EntrySaveTest {
    private val exhibition = diaryExhibition("ex")
    private val now = Instant.parse("2026-11-11T09:00:00Z")
    private val draft =
        DiaryEntryDraft(
            visitDate = LocalDate(2026, 11, 10),
            feeling = VisitFeeling.LOVED,
            tags = listOf(VisitTag.THE_WORK, VisitTag.CALM_TO_LINGER),
            note = "좋은 전시",
        )

    @Test
    fun theFirstSaveCreatesAnEntryWithSnapshotRecordIdAndRecordTime() {
        val saved = applyEntrySave(null, exhibition, draft, now, "record-new")

        assertEquals("record-new", saved.clientRecordId)
        assertEquals("ex", saved.exhibitionId)
        assertEquals(ExhibitionVisitSnapshot.from(exhibition), saved.snapshot)
        assertEquals(now, saved.createdAt)
        assertEquals(LocalDate(2026, 11, 10), saved.visitDate)
        assertEquals(VisitFeeling.LOVED, saved.feeling)
        assertEquals(listOf(VisitTag.THE_WORK, VisitTag.CALM_TO_LINGER), saved.tags)
        assertEquals("좋은 전시", saved.note)
        assertEquals(now, saved.entryUpdatedAt)
    }

    @Test
    fun aLaterSaveReplacesOnlyTheDiaryFieldsAndTheUpdateTime() {
        val existing =
            diaryVisit(
                "ex",
                createdAt = "2026-10-05T00:00:00Z",
                visitDate = LocalDate(2026, 10, 5),
                feeling = VisitFeeling.FINE,
                tags = listOf(VisitTag.HEAVY),
                note = "예전 메모",
                entryUpdatedAt = "2026-10-05T00:00:00Z",
            )
        val renamedInCatalogue = exhibition.copy(nameKo = "바뀐 이름")

        val saved =
            applyEntrySave(
                existing,
                renamedInCatalogue,
                DiaryEntryDraft(visitDate = LocalDate(2026, 10, 6), feeling = null, tags = emptyList(), note = null),
                now,
                "record-ignored",
            )

        assertEquals(
            existing.copy(
                visitDate = LocalDate(2026, 10, 6),
                feelingWire = null,
                tagWires = emptyList(),
                note = null,
                entryUpdatedAt = now,
            ),
            saved,
        )
    }

    @Test
    fun theNoteLosesTrailingWhitespaceAndABlankNoteIsStoredAsNone() {
        assertEquals("메모", applyEntrySave(null, exhibition, draft.copy(note = "메모 \n\n "), now, "r").note)
        assertEquals("  앞 공백", applyEntrySave(null, exhibition, draft.copy(note = "  앞 공백"), now, "r").note)
        assertNull(applyEntrySave(null, exhibition, draft.copy(note = " \n\t "), now, "r").note)
        assertNull(applyEntrySave(null, exhibition, draft.copy(note = ""), now, "r").note)
    }

    @Test
    fun moreThanThreeOrDuplicateTagsAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            applyEntrySave(
                null,
                exhibition,
                draft.copy(tags = listOf(VisitTag.THE_WORK, VisitTag.THE_SPACE, VisitTag.PLAYFUL, VisitTag.HEAVY)),
                now,
                "r",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            applyEntrySave(null, exhibition, draft.copy(tags = listOf(VisitTag.PLAYFUL, VisitTag.PLAYFUL)), now, "r")
        }
    }

    @Test
    fun aNoteLongerThanTheMergedLimitIsRefusedSoTheServerNeverRejectsTheOutbox() {
        assertEquals(
            DIARY_MERGED_NOTE_MAX_LENGTH,
            applyEntrySave(null, exhibition, draft.copy(note = "가".repeat(562)), now, "r").note!!.length,
        )
        assertFailsWith<IllegalArgumentException> {
            applyEntrySave(null, exhibition, draft.copy(note = "가".repeat(563)), now, "r")
        }
    }

    @Test
    fun anEntryForAnotherExhibitionIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            applyEntrySave(diaryVisit("other"), exhibition, draft, now, "r")
        }
    }
}
