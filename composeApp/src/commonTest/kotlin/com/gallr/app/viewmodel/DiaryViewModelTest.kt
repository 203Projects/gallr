package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.diary.Diary
import com.gallr.shared.diary.DiaryEntry
import com.gallr.shared.diary.DiaryFilter
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.repository.DiaryDeviceStateRepository
import com.gallr.shared.repository.MyGallrSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

// Value: protects=MY 일기 lists every entry by month from the shared diary rules, filters to published notes, and keeps
// the ended-saved strip closed until the count grows (spec 103 US2, SC-008); fails_when=DiaryViewModel drops an entry
// the LIST tab would hide, forgets the closed count, clears the list while a session refresh is pending, or opens a
// row on the wrong show; why_new=the screen is new; seam=DiaryViewModel over fake repositories and flows;
// no_claim=layout (DiaryListTest)

/** Spec 103 US2: MY → 일기. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    // 2026-11-12 12:00 in Seoul.
    private val now = Instant.parse("2026-11-12T03:00:00Z")

    private val running = exhibition("running", LocalDate(2026, 11, 1), LocalDate(2026, 12, 31))
    private val closedOctober = exhibition("closed-october", LocalDate(2026, 9, 1), LocalDate(2026, 10, 20))
    private val closedLongAgo = exhibition("closed-long-ago", LocalDate(2026, 3, 1), LocalDate(2026, 4, 30))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun entriesAreGroupedByMonthNewestFirstWithTheUndatedLast() =
        runTest(dispatcher) {
            val visits =
                FakeDiaryVisitRepository(
                    listOf(
                        visit(running, LocalDate(2026, 11, 9)),
                        visit(closedOctober, LocalDate(2026, 10, 3)),
                        visit(closedLongAgo, visitDate = null),
                    ),
                )
            val viewModel = diary(visits = visits)
            observe(viewModel)

            val diary = assertNotNull(viewModel.state.value.diary)
            assertEquals(listOf(2026 to 11, 2026 to 10), diary.months.map { it.year to it.month })
            assertEquals(listOf("running"), diary.months[0].entries.map(DiaryEntry::exhibitionId))
            assertEquals(listOf("closed-long-ago"), diary.undated.map(DiaryEntry::exhibitionId))
            assertEquals(3, viewModel.state.value.entryCount)
            assertFalse(viewModel.state.value.loadFailed)
        }

    @Test
    fun everyVisitIsListedWhetherOrNotTheCatalogueStillHoldsItsShow() =
        runTest(dispatcher) {
            // The LIST tab's search, city and filters narrow what it shows; none of them is an input here (SC-008),
            // and a show that left the catalogue keeps its row from the record made at the time.
            val gone = exhibition("gone", LocalDate(2026, 1, 1), LocalDate(2026, 2, 1))
            val visits =
                FakeDiaryVisitRepository(
                    listOf(visit(running, LocalDate(2026, 11, 9)), visit(gone, LocalDate(2026, 1, 15))),
                )
            val viewModel = diary(visits = visits, catalogue = listOf(running))
            observe(viewModel)

            val diary = assertNotNull(viewModel.state.value.diary)
            assertEquals(
                setOf("running", "gone"),
                diary.months
                    .flatMap { it.entries }
                    .map(DiaryEntry::exhibitionId)
                    .toSet(),
            )
            val goneEntry = diary.months.flatMap { it.entries }.single { it.exhibitionId == "gone" }
            assertEquals("gone", goneEntry.snapshot?.nameEn)
        }

    @Test
    fun thePublishedNotesFilterKeepsOnlyNotesWithAPublicStatusAndCountsStayWhole() =
        runTest(dispatcher) {
            val visits =
                FakeDiaryVisitRepository(
                    listOf(
                        visit(running, LocalDate(2026, 11, 9), note = "빛"),
                        visit(closedOctober, LocalDate(2026, 10, 3), note = "나만 보는 메모"),
                    ),
                )
            val notes = FakePublicNoteRepository(listOf(note(running, PublicNoteModeration.PENDING)))
            val viewModel = diary(visits = visits, notes = notes)
            observe(viewModel)

            assertEquals(DiaryFilter.ALL, viewModel.state.value.filter)
            viewModel.selectFilter(DiaryFilter.PUBLISHED_NOTES)

            val diary = assertNotNull(viewModel.state.value.diary)
            assertEquals(DiaryFilter.PUBLISHED_NOTES, viewModel.state.value.filter)
            val shown = diary.months.flatMap { it.entries }
            assertEquals(listOf("running"), shown.map(DiaryEntry::exhibitionId))
            assertEquals(PublicNoteStatus.IN_REVIEW, shown.single().noteStatus)
            assertEquals(2, viewModel.state.value.entryCount)

            viewModel.selectFilter(DiaryFilter.ALL)
            assertEquals(2, viewModel.shown.months.sumOf { it.entries.size })
        }

    @Test
    fun aNoteWithNoVisitIsAnUndatedEntryAndCounts() =
        runTest(dispatcher) {
            val notes = FakePublicNoteRepository(listOf(note(closedOctober, PublicNoteModeration.APPROVED)))
            val viewModel = diary(notes = notes)
            observe(viewModel)

            val diary = assertNotNull(viewModel.state.value.diary)
            assertEquals(listOf("closed-october"), diary.undated.map(DiaryEntry::exhibitionId))
            assertEquals(1, viewModel.state.value.entryCount)
        }

    @Test
    fun theStripCountsEndedSavedShowsWithoutAnEntryAndStaysClosedUntilTheCountGrows() =
        runTest(dispatcher) {
            val saved = MutableStateFlow(setOf("running", "closed-october"))
            val device = FakeDiaryDeviceState()
            val viewModel = diary(savedIds = saved, device = device)
            observe(viewModel)

            assertEquals(1, viewModel.shown.endedSavedCount)
            assertTrue(viewModel.shown.showEndedSavedStrip)

            viewModel.dismissEndedSavedStrip()
            assertEquals(listOf(1), device.dismissedAt)
            assertFalse(viewModel.shown.showEndedSavedStrip)

            saved.value = saved.value + "closed-long-ago"
            assertEquals(2, viewModel.shown.endedSavedCount)
            assertTrue(viewModel.shown.showEndedSavedStrip)
        }

    @Test
    fun aSavedShowWithAnEntryLeavesTheStripCount() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository()
            val viewModel = diary(visits = visits, savedIds = MutableStateFlow(setOf("closed-october")))
            observe(viewModel)
            assertEquals(1, viewModel.shown.endedSavedCount)

            visits.addVisits(listOf(visit(closedOctober, visitDate = null)))

            assertEquals(0, viewModel.shown.endedSavedCount)
            assertFalse(viewModel.shown.showEndedSavedStrip)
        }

    @Test
    fun withASessionRefreshPendingEveryEntryStaysAndAQueuedPublishReadsSending() =
        runTest(dispatcher) {
            val visits =
                FakeDiaryVisitRepository(
                    listOf(
                        visit(running, LocalDate(2026, 11, 9), note = "빛"),
                        visit(closedOctober, LocalDate(2026, 10, 3)),
                    ),
                )
            val notes = FakePublicNoteRepository()
            notes.pending.value = mapOf("running" to PendingNoteMutation.PUBLISH)
            val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user, isSessionRefreshPending = true))
            val sync = MutableStateFlow(MyGallrSyncStatus.RETRY_NEEDED)
            val viewModel = diary(visits = visits, notes = notes, authState = auth, syncStatus = sync)
            observe(viewModel)

            val pending = viewModel.shown
            assertEquals(2, pending.months.sumOf { it.entries.size })
            assertEquals(PublicNoteStatus.SENDING, pending.firstNoteStatus)

            auth.value = AuthState.Authenticated(user)
            val refreshed = viewModel.shown
            assertEquals(2, refreshed.months.sumOf { it.entries.size })
            assertEquals(PublicNoteStatus.RETRY, refreshed.firstNoteStatus)

            sync.value = MyGallrSyncStatus.SYNCED
            notes.pending.value = emptyMap()
            assertEquals(PublicNoteStatus.PRIVATE, viewModel.shown.firstNoteStatus)
        }

    @Test
    fun aFailedReadShowsTheRetryAndRetryReadsAgain() =
        runTest(dispatcher) {
            val visits = FailingOnceVisitRepository(listOf(visit(running, LocalDate(2026, 11, 9))))
            val viewModel = diary(visits = visits)
            observe(viewModel)

            assertTrue(viewModel.state.value.loadFailed)
            assertNull(viewModel.state.value.diary)

            viewModel.retry()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(1, viewModel.state.value.entryCount)
        }

    @Test
    fun theEntryOpensOnItsCatalogueShowOrOnTheRecordKeptAtTheTime() {
        val catalogue = listOf(running)
        val visitEntry = entry(running, visit(running, LocalDate(2026, 11, 9)))
        assertEquals(running, diaryEntryExhibition(visitEntry, catalogue))

        val gone = exhibition("gone", LocalDate(2026, 1, 1), LocalDate(2026, 2, 1))
        val opened = assertNotNull(diaryEntryExhibition(entry(gone, visit(gone, null)), catalogue))
        assertEquals("gone", opened.id)
        assertEquals(gone.openingDate, opened.openingDate)
        assertEquals(gone.closingDate, opened.closingDate)
        assertEquals(gone.nameKo, opened.nameKo)
        assertEquals(gone.venueNameEn, opened.venueNameEn)

        // A 감상 on a show that left the catalogue has no run to pick a date in.
        val unknown =
            DiaryEntry(
                exhibitionId = "unknown",
                snapshot = null,
                visit = null,
                publicNote = null,
                noteStatus = null,
                showApprovalLine = false,
            )
        assertNull(diaryEntryExhibition(unknown, catalogue))
    }

    private val DiaryViewModel.shown: Diary get() = assertNotNull(state.value.diary)

    private val Diary.firstNoteStatus: PublicNoteStatus?
        get() =
            months
                .first()
                .entries
                .single()
                .noteStatus

    private fun TestScope.observe(viewModel: DiaryViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun diary(
        visits: com.gallr.shared.repository.VisitRepository = FakeDiaryVisitRepository(),
        notes: FakePublicNoteRepository = FakePublicNoteRepository(),
        device: FakeDiaryDeviceState = FakeDiaryDeviceState(),
        catalogue: List<Exhibition> = listOf(running, closedOctober, closedLongAgo),
        savedIds: Flow<Set<String>> = MutableStateFlow(emptySet()),
        syncStatus: Flow<MyGallrSyncStatus> = MutableStateFlow(MyGallrSyncStatus.SYNCED),
        authState: Flow<AuthState> = MutableStateFlow(AuthState.Anonymous),
    ) = DiaryViewModel(
        visitRepository = visits,
        publicNoteRepository = notes,
        deviceStateRepository = device,
        exhibitionsState = MutableStateFlow(ExhibitionListState.Success(catalogue)),
        savedIds = savedIds,
        syncStatus = syncStatus,
        authState = authState,
        language = MutableStateFlow(AppLanguage.KO),
        clock =
            object : Clock {
                override fun now(): Instant = now
            },
    )

    private fun entry(
        exhibition: Exhibition,
        visit: ExhibitionVisit,
    ) = DiaryEntry(
        exhibitionId = exhibition.id,
        snapshot = visit.snapshot,
        visit = visit,
        publicNote = null,
        noteStatus = null,
        showApprovalLine = false,
    )

    private fun visit(
        exhibition: Exhibition,
        visitDate: LocalDate?,
        note: String? = null,
    ) = ExhibitionVisit(
        clientRecordId = "record-${exhibition.id}",
        exhibitionId = exhibition.id,
        snapshot = ExhibitionVisitSnapshot.from(exhibition),
        createdAt = now,
        visitDate = visitDate,
        note = note,
    )

    private fun note(
        exhibition: Exhibition,
        moderation: PublicNoteModeration,
    ) = OwnPublicNote(
        exhibitionId = exhibition.id,
        thoughtId = "thought-${exhibition.id}",
        content = "공개한 감상",
        moderation = moderation,
        rejectionReason = null,
        requestedAt = now,
        approvedAt = null,
        approvalSeen = false,
    )

    private fun exhibition(
        id: String,
        opening: LocalDate,
        closing: LocalDate,
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = id,
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = opening,
        closingDate = closing,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = "https://example.com/$id.jpg",
    )

    private companion object {
        val user = GallrUser(id = "user-1", displayName = "User", avatarUrl = null)
    }
}

/** Records each close of the strip; the stored count is what the next read returns. */
private class FakeDiaryDeviceState : DiaryDeviceStateRepository {
    private val stored = MutableStateFlow(0)
    val dismissedAt = mutableListOf<Int>()

    override val dismissedEndedSavedCount: Flow<Int> = stored

    override suspend fun dismissEndedSavedStrip(count: Int) {
        dismissedAt += count
        stored.value = count
    }
}

/** Fails the first read, as a corrupt or unreadable store would, and reads normally after. */
private class FailingOnceVisitRepository(
    private val initial: List<ExhibitionVisit>,
) : com.gallr.shared.repository.VisitRepository {
    private var reads = 0

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        flow {
            reads += 1
            if (reads == 1) throw IllegalStateException("unreadable")
            emit(initial)
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) = Unit

    override suspend fun removeVisit(exhibitionId: String) = Unit

    override suspend fun saveEntry(visit: ExhibitionVisit) = Unit

    override suspend fun deleteEntry(exhibitionId: String) = Unit
}
