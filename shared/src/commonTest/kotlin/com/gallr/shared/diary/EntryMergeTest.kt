package com.gallr.shared.diary

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * research P5: the sign-in merge fills only empty account fields and joins two differing notes account first. The
 * cases mirror pgTAP 079's merge table so the device and the server agree.
 */
class EntryMergeTest {
    private val october = LocalDate(2026, 10, 20)
    private val november = LocalDate(2026, 11, 2)

    @Test
    fun anEmptyAccountEntryTakesEveryDeviceField() {
        val account = diaryVisit("ex", createdAt = "2026-10-01T00:00:00Z")
        val device =
            diaryVisit(
                "ex",
                createdAt = "2026-11-01T00:00:00Z",
                visitDate = november,
                feeling = VisitFeeling.LOVED,
                tags = listOf(VisitTag.THE_WORK),
                note = "기기 메모",
                entryUpdatedAt = "2026-11-03T00:00:00Z",
            )

        val merged = mergeEntry(account, device)

        assertEquals(november, merged.visitDate)
        assertEquals(VisitFeeling.LOVED, merged.feeling)
        assertEquals(listOf(VisitTag.THE_WORK), merged.tags)
        assertEquals("기기 메모", merged.note)
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"), merged.entryUpdatedAt)
        // The account's record is the kept record: its id, snapshot and record time stay.
        assertEquals(account.clientRecordId, merged.clientRecordId)
        assertEquals(account.createdAt, merged.createdAt)
    }

    @Test
    fun anEmptyDeviceEntryChangesNothing() {
        val account =
            diaryVisit(
                "ex",
                visitDate = october,
                feeling = VisitFeeling.FINE,
                tags = listOf(VisitTag.HEAVY),
                note = "계정 메모",
                entryUpdatedAt = "2026-10-21T00:00:00Z",
            )

        assertEquals(account, mergeEntry(account, diaryVisit("ex")))
    }

    @Test
    fun equalEntriesMergeToTheSameEntry() {
        val entry =
            diaryVisit(
                "ex",
                visitDate = october,
                feeling = VisitFeeling.LOVED,
                tags = listOf(VisitTag.PLAYFUL, VisitTag.SEE_AGAIN),
                note = "같은 메모",
                entryUpdatedAt = "2026-10-21T00:00:00Z",
            )

        assertEquals(entry, mergeEntry(entry, entry))
    }

    @Test
    fun differingEntriesKeepTheAccountFieldsAndJoinTheNotesAccountFirst() {
        val account =
            diaryVisit(
                "ex",
                visitDate = october,
                feeling = VisitFeeling.FINE,
                note = "계정 메모",
                entryUpdatedAt = "2026-10-21T00:00:00Z",
            )
        val device =
            diaryVisit(
                "ex",
                visitDate = november,
                feeling = VisitFeeling.NOT_FOR_ME,
                note = "기기 메모",
                entryUpdatedAt = "2026-11-03T00:00:00Z",
            )

        val merged = mergeEntry(account, device)

        assertEquals(october, merged.visitDate)
        assertEquals(VisitFeeling.FINE, merged.feeling)
        assertEquals("계정 메모\n\n기기 메모", merged.note)
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"), merged.entryUpdatedAt)
    }

    @Test
    fun accountTagsWinWhenPresentAndDeviceTagsFillAnEmptyList() {
        val withTags = diaryVisit("ex", tags = listOf(VisitTag.THE_SPACE))
        val otherTags = diaryVisit("ex", tags = listOf(VisitTag.MOVING, VisitTag.HEAVY))

        assertEquals(listOf(VisitTag.THE_SPACE), mergeEntry(withTags, otherTags).tags)
        assertEquals(listOf(VisitTag.MOVING, VisitTag.HEAVY), mergeEntry(diaryVisit("ex"), otherTags).tags)
    }

    @Test
    fun twoFullNotesJoinWithinTheMergedLimit() {
        val merged = mergeEntry(diaryVisit("ex", note = "가".repeat(280)), diaryVisit("ex", note = "나".repeat(280)))

        assertEquals(562, merged.note!!.length)
        assertEquals(DIARY_MERGED_NOTE_MAX_LENGTH, merged.note!!.length)
    }

    @Test
    fun theLaterEntryUpdateIsKeptWhicheverSideHasIt() {
        val older = diaryVisit("ex", entryUpdatedAt = "2026-10-01T00:00:00Z")
        val newer = diaryVisit("ex", entryUpdatedAt = "2026-11-01T00:00:00Z")

        assertEquals(newer.entryUpdatedAt, mergeEntry(older, newer).entryUpdatedAt)
        assertEquals(newer.entryUpdatedAt, mergeEntry(newer, older).entryUpdatedAt)
        assertEquals(older.entryUpdatedAt, mergeEntry(diaryVisit("ex"), older).entryUpdatedAt)
    }
}
