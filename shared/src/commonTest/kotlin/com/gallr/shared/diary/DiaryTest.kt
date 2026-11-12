package com.gallr.shared.diary

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** contracts/shared-diary-api.md "Guarantees for buildDiary" and spec US2. */
class DiaryTest {
    private val today = LocalDate(2026, 11, 11)

    private fun diary(
        visits: List<ExhibitionVisit> = emptyList(),
        publicNotes: List<OwnPublicNote> = emptyList(),
        catalogue: List<Exhibition> = emptyList(),
        savedIds: Set<String> = emptySet(),
        filter: DiaryFilter = DiaryFilter.ALL,
        dismissedEndedSavedCount: Int = 0,
        pending: Map<String, PendingNoteMutation> = emptyMap(),
        syncFailed: Boolean = false,
        sessionRefreshPending: Boolean = false,
        language: AppLanguage = AppLanguage.KO,
    ) = buildDiary(
        visits = visits,
        publicNotes = publicNotes,
        catalogue = catalogue,
        savedIds = savedIds,
        today = today,
        filter = filter,
        dismissedEndedSavedCount = dismissedEndedSavedCount,
        pendingNoteMutations = pending,
        syncFailed = syncFailed,
        sessionRefreshPending = sessionRefreshPending,
        language = language,
    )

    @Test
    fun monthsRunNewestFirstAndEntriesInAMonthNewestFirstThenByName() {
        val result =
            diary(
                visits =
                    listOf(
                        diaryVisit("oct", visitDate = LocalDate(2026, 10, 20)),
                        diaryVisit("nov-early", visitDate = LocalDate(2026, 11, 2)),
                        diaryVisit("nov-b", visitDate = LocalDate(2026, 11, 9), nameKo = "나 전시"),
                        diaryVisit("nov-a", visitDate = LocalDate(2026, 11, 9), nameKo = "가 전시"),
                        diaryVisit("last-year", visitDate = LocalDate(2025, 12, 31)),
                    ),
            )

        assertEquals(
            listOf(2026 to 11, 2026 to 10, 2025 to 12),
            result.months.map { it.year to it.month },
        )
        assertEquals(
            listOf("nov-a", "nov-b", "nov-early"),
            result.months
                .first()
                .entries
                .map { it.exhibitionId },
        )
        assertTrue(result.undated.isEmpty())
    }

    @Test
    fun namesTieBreakInTheReadersLanguage() {
        val visits =
            listOf(
                diaryVisit("a", visitDate = LocalDate(2026, 11, 9), nameKo = "가", nameEn = "Zebra"),
                diaryVisit("b", visitDate = LocalDate(2026, 11, 9), nameKo = "나", nameEn = "Apple"),
            )

        assertEquals(
            listOf("a", "b"),
            diary(visits)
                .months
                .single()
                .entries
                .map { it.exhibitionId },
        )
        assertEquals(
            listOf("b", "a"),
            diary(visits, language = AppLanguage.EN)
                .months
                .single()
                .entries
                .map { it.exhibitionId },
        )
    }

    @Test
    fun undatedEntriesComeLastByRecordTimeNewestFirst() {
        val result =
            diary(
                visits =
                    listOf(
                        diaryVisit("bulk-old", createdAt = "2026-09-01T00:00:00Z"),
                        diaryVisit("dated", visitDate = LocalDate(2026, 11, 1)),
                        diaryVisit("bulk-new", createdAt = "2026-11-05T00:00:00Z"),
                    ),
            )

        assertEquals(
            listOf("dated"),
            result.months
                .single()
                .entries
                .map { it.exhibitionId },
        )
        assertEquals(listOf("bulk-new", "bulk-old"), result.undated.map { it.exhibitionId })
    }

    @Test
    fun aPublicNoteWithNoVisitBecomesAnUndatedEntry() {
        val inCatalogue = diaryExhibition("legacy")
        val result =
            diary(
                publicNotes =
                    listOf(
                        ownNote("legacy", PublicNoteModeration.APPROVED, requestedAt = "2026-08-01T00:00:00Z"),
                        ownNote("gone", PublicNoteModeration.APPROVED, requestedAt = "2026-09-01T00:00:00Z"),
                    ),
                catalogue = listOf(inCatalogue),
            )

        assertTrue(result.months.isEmpty())
        assertEquals(listOf("gone", "legacy"), result.undated.map { it.exhibitionId })
        val legacy = result.undated.last()
        assertNull(legacy.visit)
        assertEquals(ExhibitionVisitSnapshot.from(inCatalogue), legacy.snapshot)
        assertEquals(PublicNoteStatus.PUBLISHED, legacy.noteStatus)
        assertNull(result.undated.first().snapshot)
    }

    @Test
    fun everyVisitIsListedWhateverTheCatalogueHolds() {
        // SC-008: no LIST search, city or filter is an input, and a show off the catalogue keeps its entry.
        val result =
            diary(
                visits =
                    listOf(
                        diaryVisit("off-catalogue", visitDate = LocalDate(2026, 11, 1)),
                        diaryVisit("undated-off-catalogue"),
                    ),
                catalogue = emptyList(),
            )

        assertEquals(
            listOf("off-catalogue"),
            result.months
                .single()
                .entries
                .map { it.exhibitionId },
        )
        assertEquals(listOf("undated-off-catalogue"), result.undated.map { it.exhibitionId })
        assertFalse(result.isEmpty)
    }

    @Test
    fun theEndedSavedCountCountsClosedSavedShowsWithNoEntry() {
        val catalogue =
            listOf(
                diaryExhibition("closed-a", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)),
                diaryExhibition("closed-b", LocalDate(2026, 9, 1), LocalDate(2026, 11, 10)),
                diaryExhibition("closed-recorded", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)),
                diaryExhibition("closed-noted", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)),
                diaryExhibition("closes-today", LocalDate(2026, 9, 1), today),
                diaryExhibition("closed-unsaved", LocalDate(2026, 9, 1), LocalDate(2026, 10, 1)),
            )
        val saved = setOf("closed-a", "closed-b", "closed-recorded", "closed-noted", "closes-today", "not-in-catalogue")

        fun count(dismissed: Int) =
            diary(
                visits = listOf(diaryVisit("closed-recorded")),
                publicNotes = listOf(ownNote("closed-noted")),
                catalogue = catalogue,
                savedIds = saved,
                dismissedEndedSavedCount = dismissed,
            )

        assertEquals(2, count(dismissed = 0).endedSavedCount)
        assertTrue(count(dismissed = 0).showEndedSavedStrip)
        assertTrue(count(dismissed = 1).showEndedSavedStrip)
        assertFalse(count(dismissed = 2).showEndedSavedStrip)
        assertFalse(count(dismissed = 5).showEndedSavedStrip)
        assertFalse(diary(catalogue = catalogue).showEndedSavedStrip)
        assertEquals(0, diary(catalogue = catalogue).endedSavedCount)
    }

    @Test
    fun thePublishedNotesFilterKeepsOnlyEntriesWithAPublicStatus() {
        val visits =
            listOf(
                diaryVisit("no-note", visitDate = LocalDate(2026, 11, 1)),
                diaryVisit("private", visitDate = LocalDate(2026, 11, 2), note = "비공개"),
                diaryVisit("sending", visitDate = LocalDate(2026, 11, 3), note = "보내는 중"),
                diaryVisit("review", visitDate = LocalDate(2026, 10, 3), note = "검토"),
                diaryVisit("rejected", note = "반려"),
            )
        val notes =
            listOf(
                ownNote("review"),
                ownNote("rejected", PublicNoteModeration.REJECTED, rejectionReason = ThoughtRejectionReason.OTHER),
                ownNote("legacy", PublicNoteModeration.APPROVED),
            )

        val all = diary(visits = visits, publicNotes = notes, pending = mapOf("sending" to PendingNoteMutation.PUBLISH))
        val published =
            diary(
                visits = visits,
                publicNotes = notes,
                pending = mapOf("sending" to PendingNoteMutation.PUBLISH),
                filter = DiaryFilter.PUBLISHED_NOTES,
            )

        assertEquals(listOf(2026 to 11, 2026 to 10), all.months.map { it.year to it.month })
        assertEquals(
            listOf("sending", "review"),
            published.months.flatMap { month -> month.entries.map { it.exhibitionId } },
        )
        assertEquals(setOf("rejected", "legacy"), published.undated.mapTo(mutableSetOf()) { it.exhibitionId })
        assertEquals(
            PublicNoteStatus.SENDING,
            published.months
                .first()
                .entries
                .single()
                .noteStatus,
        )
        assertFalse(published.isEmpty)
    }

    @Test
    fun aQueuedPublishReadsSendingWhileTheSessionRefreshIsPendingAndRetryOutsideIt() {
        val visits = listOf(diaryVisit("ex", visitDate = LocalDate(2026, 11, 1), note = "메모"))
        val pending = mapOf("ex" to PendingNoteMutation.PUBLISH)

        fun statusOf(sessionRefreshPending: Boolean) =
            diary(visits = visits, pending = pending, syncFailed = true, sessionRefreshPending = sessionRefreshPending)
                .months
                .single()
                .entries
                .single()
                .noteStatus

        assertEquals(PublicNoteStatus.SENDING, statusOf(sessionRefreshPending = true))
        assertEquals(PublicNoteStatus.RETRY, statusOf(sessionRefreshPending = false))
    }

    @Test
    fun theApprovalLineShowsOnlyForAnApprovedNoteNotYetSeen() {
        val visits =
            listOf("unseen", "seen", "pending").map { diaryVisit(it, visitDate = LocalDate(2026, 11, 1), note = "메모") }
        val result =
            diary(
                visits = visits,
                publicNotes =
                    listOf(
                        ownNote("unseen", PublicNoteModeration.APPROVED, approvalSeen = false),
                        ownNote("seen", PublicNoteModeration.APPROVED, approvalSeen = true),
                        ownNote("pending", PublicNoteModeration.PENDING, approvalSeen = false),
                    ),
            )

        assertEquals(
            mapOf("unseen" to true, "seen" to false, "pending" to false),
            result.months
                .single()
                .entries
                .associate { it.exhibitionId to it.showApprovalLine },
        )
    }

    @Test
    fun anEmptyDiaryIsEmptyAndAFilterThatHidesEverythingIsNot() {
        assertTrue(diary().isEmpty)
        assertTrue(diary().months.isEmpty())

        val onlyPrivate = diary(visits = listOf(diaryVisit("ex", note = "메모")), filter = DiaryFilter.PUBLISHED_NOTES)

        assertTrue(onlyPrivate.months.isEmpty())
        assertTrue(onlyPrivate.undated.isEmpty())
        assertFalse(onlyPrivate.isEmpty)
    }
}
