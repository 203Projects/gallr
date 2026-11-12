package com.gallr.app.viewmodel

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.MyGallrAccountArchive
import com.gallr.shared.data.model.MyGallrAccountMutation
import com.gallr.shared.data.network.MyGallrAccountAuthenticationRequiredException
import com.gallr.shared.data.network.MyGallrAccountCommandSource
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import com.gallr.shared.diary.PublicNoteStatus
import com.gallr.shared.diary.ThoughtRejectionReason
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import com.gallr.shared.repository.AuthAwareVisitRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountStore
import com.gallr.shared.repository.MyGallrAccountSyncCoordinator
import com.gallr.shared.repository.MyGallrSyncStatus
import com.gallr.shared.repository.PublicNoteRepositoryImpl
import com.gallr.shared.repository.StoredMyGallrAccountState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Spec 103 US1: the diary row and entry sheet on the exhibition page, and the save that left App.kt. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryEntryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    // 2026-11-12 12:00 in Seoul.
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val today = LocalDate(2026, 11, 12)
    private val yesterday = LocalDate(2026, 11, 11)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRunningShowWithNoEntryOffersTheRowAndPreselectsToday() =
        runTest(dispatcher) {
            val viewModel = diary(running)
            observe(viewModel)

            assertEquals(DiaryVisitRowState.NoEntry, viewModel.state.value.row)
            assertNull(viewModel.state.value.editor)

            viewModel.startEditing()
            val editor = viewModel.state.value.editor!!
            assertEquals(today, editor.visitDate)
            assertEquals(today, editor.today)
            assertTrue(editor.dateOptions.today)
            assertTrue(editor.dateOptions.yesterday)
            assertEquals(running.openingDate, editor.dateOptions.earliest)
            assertEquals(today, editor.dateOptions.latest)
            assertNull(editor.feeling)
            assertEquals(emptyList(), editor.tags)
            assertEquals("", editor.note)
            assertFalse(editor.isExistingEntry)
            assertFalse(editor.needsDate)
            assertTrue(editor.canSave)
            assertFalse(editor.hasUnsavedChanges)
        }

    @Test
    fun anExistingEntryShowsItsDateAndFeelingAndOpensWithEverySavedValue() =
        runTest(dispatcher) {
            val entry =
                visit(running).copy(
                    visitDate = LocalDate(2026, 11, 9),
                    feelingWire = VisitFeeling.LOVED.wire,
                    tagWires = listOf(VisitTag.THE_SPACE.wire, VisitTag.CALM_TO_LINGER.wire),
                    note = "빛이 좋았다",
                )
            val viewModel = diary(running, FakeDiaryVisitRepository(listOf(entry)))
            observe(viewModel)

            assertEquals(
                DiaryVisitRowState.Dated(LocalDate(2026, 11, 9), VisitFeeling.LOVED),
                viewModel.state.value.row,
            )

            viewModel.startEditing()
            val editor = viewModel.state.value.editor!!
            assertEquals(LocalDate(2026, 11, 9), editor.visitDate)
            assertEquals(VisitFeeling.LOVED, editor.feeling)
            assertEquals(listOf(VisitTag.THE_SPACE, VisitTag.CALM_TO_LINGER), editor.tags)
            assertEquals("빛이 좋았다", editor.note)
            assertTrue(editor.isExistingEntry)
            assertFalse(editor.hasUnsavedChanges)
        }

    @Test
    fun anUndatedVisitReadsDateNotSetAndCanBeSavedWithoutADate() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(closed)))
            val viewModel = diary(closed, visits)
            observe(viewModel)

            assertEquals(DiaryVisitRowState.Undated(feeling = null), viewModel.state.value.row)

            viewModel.startEditing()
            assertTrue(
                viewModel.state.value.editor!!
                    .needsDate,
            )
            assertTrue(
                viewModel.state.value.editor!!
                    .canSave,
            )
            viewModel.toggleFeeling(VisitFeeling.FINE)
            viewModel.save()

            assertEquals(VisitFeeling.FINE, visits.saved.single().feeling)
            assertNull(visits.saved.single().visitDate)
        }

    @Test
    fun anUndatedVisitWithAFeelingCarriesTheFeelingToTheRow() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(closed).copy(feelingWire = VisitFeeling.LOVED.wire)))
            val viewModel = diary(closed, visits)
            observe(viewModel)

            assertEquals(DiaryVisitRowState.Undated(VisitFeeling.LOVED), viewModel.state.value.row)
        }

    @Test
    fun aClosedShowPreselectsNothingAndCannotSaveUntilADateIsChosen() =
        runTest(dispatcher) {
            val viewModel = diary(closed)
            observe(viewModel)

            viewModel.startEditing()
            var editor = viewModel.state.value.editor!!
            assertNull(editor.visitDate)
            assertFalse(editor.dateOptions.today)
            assertFalse(editor.dateOptions.yesterday)
            assertEquals(closed.closingDate, editor.dateOptions.latest)
            assertFalse(editor.canSave)
            // A new entry asks through the disabled save button, not the line kept for undated entries.
            assertFalse(editor.needsDate)

            viewModel.selectDate(LocalDate(2026, 11, 3))
            editor = viewModel.state.value.editor!!
            assertEquals(LocalDate(2026, 11, 3), editor.visitDate)
            assertTrue(editor.canSave)
        }

    @Test
    fun aDateOutsideTheRunIsNotTaken() =
        runTest(dispatcher) {
            val viewModel = diary(closed)
            observe(viewModel)
            viewModel.startEditing()

            viewModel.selectDate(today)
            viewModel.selectDate(closed.openingDate.minusDays(1))

            assertNull(
                viewModel.state.value.editor!!
                    .visitDate,
            )
        }

    @Test
    fun anUpcomingShowHasNoRowAndNoSheet() =
        runTest(dispatcher) {
            val viewModel = diary(upcoming)
            observe(viewModel)

            assertNull(viewModel.state.value.row)
            viewModel.startEditing()
            assertNull(viewModel.state.value.editor)
        }

    @Test
    fun aFeelingTapSelectsOneAndASecondTapClearsIt() =
        runTest(dispatcher) {
            val viewModel = diary(running)
            observe(viewModel)
            viewModel.startEditing()

            viewModel.toggleFeeling(VisitFeeling.LOVED)
            assertEquals(
                VisitFeeling.LOVED,
                viewModel.state.value.editor!!
                    .feeling,
            )
            viewModel.toggleFeeling(VisitFeeling.NOT_FOR_ME)
            assertEquals(
                VisitFeeling.NOT_FOR_ME,
                viewModel.state.value.editor!!
                    .feeling,
            )
            viewModel.toggleFeeling(VisitFeeling.NOT_FOR_ME)
            assertNull(
                viewModel.state.value.editor!!
                    .feeling,
            )
        }

    @Test
    fun aFourthTagIsRefusedWithTheLimitMessage() =
        runTest(dispatcher) {
            val viewModel = diary(running)
            observe(viewModel)
            viewModel.startEditing()

            viewModel.toggleTag(VisitTag.THE_WORK)
            viewModel.toggleTag(VisitTag.THE_SPACE)
            viewModel.toggleTag(VisitTag.MOVING)
            assertFalse(
                viewModel.state.value.editor!!
                    .tagLimitReached,
            )

            viewModel.toggleTag(VisitTag.HEAVY)
            var editor = viewModel.state.value.editor!!
            assertEquals(listOf(VisitTag.THE_WORK, VisitTag.THE_SPACE, VisitTag.MOVING), editor.tags)
            assertTrue(editor.tagLimitReached)

            viewModel.toggleTag(VisitTag.THE_SPACE)
            editor = viewModel.state.value.editor!!
            assertEquals(listOf(VisitTag.THE_WORK, VisitTag.MOVING), editor.tags)
            assertFalse(editor.tagLimitReached)
        }

    @Test
    fun noteInputStopsAt280() =
        runTest(dispatcher) {
            val viewModel = diary(running)
            observe(viewModel)
            viewModel.startEditing()

            viewModel.updateNote("가".repeat(279))
            viewModel.updateNote("가".repeat(281))

            assertEquals(
                280,
                viewModel.state.value.editor!!
                    .note.length,
            )
            assertFalse(
                viewModel.state.value.editor!!
                    .noteOverPublishLimit,
            )
        }

    @Test
    fun aMergedNoteOver280IsKeptAndCanOnlyShrink() =
        runTest(dispatcher) {
            val merged = "a".repeat(200) + "\n\n" + "b".repeat(200)
            val entry = visit(running).copy(visitDate = today, note = merged)
            val viewModel = diary(running, FakeDiaryVisitRepository(listOf(entry)))
            observe(viewModel)
            viewModel.startEditing()

            assertEquals(
                merged,
                viewModel.state.value.editor!!
                    .note,
            )
            assertTrue(
                viewModel.state.value.editor!!
                    .noteOverPublishLimit,
            )

            viewModel.updateNote(merged + "c")
            assertEquals(
                merged,
                viewModel.state.value.editor!!
                    .note,
            )

            viewModel.updateNote(merged.dropLast(10))
            assertEquals(
                merged.dropLast(10),
                viewModel.state.value.editor!!
                    .note,
            )
        }

    @Test
    fun saveWritesTheEntryAndCountsTheVisitOnlyWhenItIsCreated() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository()
            var recorded = 0
            val viewModel = diary(running, visits, onVisitRecorded = { recorded += 1 })
            observe(viewModel)

            viewModel.startEditing()
            viewModel.selectDate(yesterday)
            viewModel.toggleFeeling(VisitFeeling.LOVED)
            viewModel.toggleTag(VisitTag.PLAYFUL)
            viewModel.updateNote("다시 가고 싶다  ")
            viewModel.save()

            val first = visits.saved.single()
            assertEquals(running.id, first.exhibitionId)
            assertEquals(yesterday, first.visitDate)
            assertEquals(VisitFeeling.LOVED, first.feeling)
            assertEquals(listOf(VisitTag.PLAYFUL), first.tags)
            assertEquals("다시 가고 싶다", first.note)
            assertEquals(now, first.createdAt)
            assertEquals(now, first.entryUpdatedAt)
            assertEquals(1, recorded)
            assertNull(viewModel.state.value.editor)
            assertEquals(DiaryVisitRowState.Dated(yesterday, VisitFeeling.LOVED), viewModel.state.value.row)

            viewModel.startEditing()
            viewModel.toggleFeeling(VisitFeeling.LOVED)
            viewModel.save()

            assertEquals(2, visits.saved.size)
            assertNull(visits.saved.last().feeling)
            assertEquals(first.clientRecordId, visits.saved.last().clientRecordId)
            assertEquals(1, recorded)
        }

    @Test
    fun savingAnUnchangedEntryWritesNothing() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(running).copy(visitDate = today)))
            val viewModel = diary(running, visits)
            observe(viewModel)

            viewModel.startEditing()
            viewModel.save()

            assertTrue(visits.saved.isEmpty())
            assertNull(viewModel.state.value.editor)
        }

    @Test
    fun deleteRemovesTheEntry() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(running).copy(visitDate = today)))
            val viewModel = diary(running, visits)
            observe(viewModel)

            viewModel.startEditing()
            viewModel.delete()

            assertEquals(listOf(running.id), visits.deleted)
            assertNull(viewModel.state.value.editor)
            assertEquals(DiaryVisitRowState.NoEntry, viewModel.state.value.row)
        }

    @Test
    fun aFailedSaveShowsOnTheRowLogsOnlyTheTypeAndKeepsTheDraft() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository().apply { failure = IllegalStateException("secret note text") }
            var recorded = 0
            val viewModel = diary(running, visits, onVisitRecorded = { recorded += 1 })
            observe(viewModel)
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            try {
                viewModel.startEditing()
                viewModel.toggleFeeling(VisitFeeling.FINE)
                viewModel.updateNote("secret note text")
                viewModel.save()
            } finally {
                Logger.setLogWriters(platformLogWriter())
            }

            assertEquals(DiaryEntryFailure.SAVE, viewModel.state.value.failure)
            assertEquals(0, recorded)
            val line = logs.messages.single { "diary_entry_save_failed" in it }
            assertEquals("operation=diary_entry_save_failed outcome=failure cause=IllegalStateException", line)

            viewModel.startEditing()
            val editor = viewModel.state.value.editor!!
            assertEquals(VisitFeeling.FINE, editor.feeling)
            assertEquals("secret note text", editor.note)

            visits.failure = null
            viewModel.save()
            assertNull(viewModel.state.value.failure)
            assertEquals(1, recorded)
        }

    @Test
    fun aFailedDeleteIsShownAndLogged() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(running).copy(visitDate = today)))
            val viewModel = diary(running, visits)
            observe(viewModel)
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            try {
                viewModel.startEditing()
                visits.failure = IllegalStateException("x")
                viewModel.delete()
            } finally {
                Logger.setLogWriters(platformLogWriter())
            }

            assertEquals(DiaryEntryFailure.DELETE, viewModel.state.value.failure)
            assertTrue(logs.messages.any { it.startsWith("operation=diary_entry_delete_failed outcome=failure") })
            assertEquals(DiaryVisitRowState.Dated(today, null), viewModel.state.value.row)
        }

    @Test
    fun withASessionRefreshPendingTheEntrySavesOnTheDeviceAndShowsAtOnce() =
        runTest(dispatcher) {
            // While the refresh is pending the app keeps the account active, and every sync fails for want of a token.
            val store = InMemoryAccountStore()
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = FakeDiaryVisitRepository(),
                    guestFollowedGalleryRepository = NoFollowedGalleries,
                    accountStore = store,
                    source = TokenlessSource,
                )
            coordinator.activateAccount("user-1")
            val viewModel =
                diary(
                    running,
                    visits = AuthAwareVisitRepository(FakeDiaryVisitRepository(), coordinator),
                    notes = PublicNoteRepositoryImpl(coordinator),
                )
            observe(viewModel)

            viewModel.startEditing()
            viewModel.toggleFeeling(VisitFeeling.LOVED)
            viewModel.save()

            assertNull(viewModel.state.value.failure)
            assertEquals(DiaryVisitRowState.Dated(today, VisitFeeling.LOVED), viewModel.state.value.row)
            assertEquals(listOf("update_visit"), store.state("user-1").pendingMutations.map { it.kind })
        }

    @Test
    fun aPublicPublicNoteWithNoVisitOpensWithItsTextAsksForADateAndItsFirstSaveRecordsTheVisit() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository()
            var recorded = 0
            val viewModel =
                diary(
                    running,
                    visits,
                    notes = FakePublicNoteRepository(listOf(publicNote("작은 방의 빛", PublicNoteModeration.APPROVED))),
                    onVisitRecorded = { recorded += 1 },
                )
            observe(viewModel)

            assertEquals(DiaryVisitRowState.Undated(feeling = null), viewModel.state.value.row)

            viewModel.startEditing()
            var editor = viewModel.state.value.editor!!
            assertEquals("작은 방의 빛", editor.note)
            assertNull(editor.visitDate)
            assertTrue(editor.needsDate)
            // It asks for the date but does not require one: the entry exists (e2e defect 8).
            assertTrue(editor.canSave)
            assertTrue(editor.isExistingEntry)
            assertTrue(editor.deleteRemovesPublicNote)

            viewModel.selectDate(today)
            editor = viewModel.state.value.editor!!
            assertTrue(editor.canSave)
            viewModel.save()

            val saved = visits.saved.single()
            assertEquals(today, saved.visitDate)
            assertEquals("작은 방의 빛", saved.note)
            assertEquals(1, recorded)
        }

    @Test
    fun aVisitWithNoNoteAndAPublicPublicNoteOpensWithThePublicNoteSoASaveKeepsIt() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(visit(running).copy(visitDate = today)))
            val viewModel =
                diary(
                    running,
                    visits,
                    notes = FakePublicNoteRepository(listOf(publicNote("빛의 방", PublicNoteModeration.APPROVED))),
                )
            observe(viewModel)

            viewModel.startEditing()
            assertEquals(
                "빛의 방",
                viewModel.state.value.editor!!
                    .note,
            )
            assertFalse(
                viewModel.state.value.editor!!
                    .hasUnsavedChanges,
            )

            viewModel.toggleFeeling(VisitFeeling.LOVED)
            viewModel.save()

            assertEquals("빛의 방", visits.saved.single().note)
        }

    @Test
    fun theDeleteWarningNamesThePublicNoteOnlyWhileItIsPublicOrInReview() =
        runTest(dispatcher) {
            val entry = visit(running).copy(visitDate = today, note = "메모")
            for (
            (moderation, warns) in
            listOf(
                PublicNoteModeration.PENDING to true,
                PublicNoteModeration.APPROVED to true,
                PublicNoteModeration.REJECTED to false,
            )
            ) {
                val viewModel =
                    diary(
                        running,
                        FakeDiaryVisitRepository(listOf(entry)),
                        notes = FakePublicNoteRepository(listOf(publicNote("메모", moderation))),
                    )
                observe(viewModel)
                viewModel.startEditing()
                assertEquals(
                    warns,
                    viewModel.state.value.editor!!
                        .deleteRemovesPublicNote,
                    moderation.name,
                )
            }
            val plain = diary(running, FakeDiaryVisitRepository(listOf(entry)))
            observe(plain)
            plain.startEditing()
            assertFalse(
                plain.state.value.editor!!
                    .deleteRemovesPublicNote,
            )
        }

    @Test
    fun closingWithoutSavingDropsTheDraftAndOpeningWithTheNoteFocusedSaysSo() =
        runTest(dispatcher) {
            val viewModel = diary(running)
            observe(viewModel)

            viewModel.startEditing()
            viewModel.toggleFeeling(VisitFeeling.LOVED)
            assertTrue(
                viewModel.state.value.editor!!
                    .hasUnsavedChanges,
            )
            viewModel.discardEditing()
            assertNull(viewModel.state.value.editor)

            viewModel.startEditing(focusNote = true)
            assertNull(
                viewModel.state.value.editor!!
                    .feeling,
            )
            assertTrue(
                viewModel.state.value.editor!!
                    .focusNote,
            )
        }

    // ── Publishing (spec 103 US3, T099) ──

    @Test
    fun theExhibitionPageChoiceNeedsASignedInVisitorAndANote() =
        runTest(dispatcher) {
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = diary(running, auth = auth)
            observe(viewModel)

            viewModel.startEditing()
            var editor = viewModel.state.value.editor!!
            assertFalse(editor.isSignedIn)
            assertEquals(DiaryVisibility.ONLY_ME, editor.shownVisibility)

            auth.value = signedIn().value
            editor = viewModel.state.value.editor!!
            assertTrue(editor.isSignedIn)
            assertFalse(editor.publicChoiceEnabled)
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            assertEquals(
                DiaryVisibility.ONLY_ME,
                viewModel.state.value.editor!!
                    .shownVisibility,
            )

            viewModel.updateNote("빛이 좋았다")
            assertTrue(
                viewModel.state.value.editor!!
                    .publicChoiceEnabled,
            )
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            assertEquals(
                DiaryVisibility.EXHIBITION_PAGE,
                viewModel.state.value.editor!!
                    .shownVisibility,
            )
        }

    @Test
    fun aMergedNoteOver280CannotBePublishedUntilItIsTrimmed() =
        runTest(dispatcher) {
            val merged = "a".repeat(200) + "\n\n" + "b".repeat(200)
            val viewModel = diary(running, FakeDiaryVisitRepository(listOf(noteVisit(merged))), auth = signedIn())
            observe(viewModel)
            viewModel.startEditing()

            assertFalse(
                viewModel.state.value.editor!!
                    .publicChoiceEnabled,
            )
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            assertEquals(
                DiaryVisibility.ONLY_ME,
                viewModel.state.value.editor!!
                    .shownVisibility,
            )

            viewModel.updateNote("a".repeat(200))
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            assertEquals(
                DiaryVisibility.EXHIBITION_PAGE,
                viewModel.state.value.editor!!
                    .shownVisibility,
            )
        }

    @Test
    fun savingWithTheExhibitionPageQueuesTheEntryThenThePublishAndReadsSendingDuringARefresh() =
        runTest(dispatcher) {
            val store = InMemoryAccountStore()
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = FakeDiaryVisitRepository(),
                    guestFollowedGalleryRepository = NoFollowedGalleries,
                    accountStore = store,
                    source = TokenlessSource,
                )
            coordinator.activateAccount("user-1")
            val viewModel =
                diary(
                    running,
                    visits = AuthAwareVisitRepository(FakeDiaryVisitRepository(), coordinator),
                    notes = PublicNoteRepositoryImpl(coordinator),
                    auth = signedIn(refreshPending = true),
                    sync = MutableStateFlow(MyGallrSyncStatus.RETRY_NEEDED),
                )
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("작은 방의 빛")
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            viewModel.save()

            assertNull(viewModel.state.value.failure)
            assertEquals(
                listOf("update_visit", "publish_note"),
                store.state("user-1").pendingMutations.map { it.kind },
            )
            // The sync failed for want of a token, but the refresh is pending: the publish is on its way, not lost.
            assertEquals(PublicNoteStatus.SENDING, viewModel.state.value.noteStatus)
        }

    @Test
    fun aFailedSyncOutsideARefreshAsksForARetry() =
        runTest(dispatcher) {
            val store = InMemoryAccountStore()
            val coordinator =
                MyGallrAccountSyncCoordinator(
                    guestVisitRepository = FakeDiaryVisitRepository(),
                    guestFollowedGalleryRepository = NoFollowedGalleries,
                    accountStore = store,
                    source = TokenlessSource,
                )
            coordinator.activateAccount("user-1")
            val viewModel =
                diary(
                    running,
                    visits = AuthAwareVisitRepository(FakeDiaryVisitRepository(), coordinator),
                    notes = PublicNoteRepositoryImpl(coordinator),
                    auth = signedIn(),
                    sync = MutableStateFlow(MyGallrSyncStatus.RETRY_NEEDED),
                )
            observe(viewModel)

            viewModel.startEditing(focusNote = true)
            viewModel.updateNote("작은 방의 빛")
            viewModel.save()

            assertEquals(PublicNoteStatus.RETRY, viewModel.state.value.noteStatus)
        }

    @Test
    fun theStatusFollowsTheReview() =
        runTest(dispatcher) {
            for (
            (moderation, status) in
            listOf(
                PublicNoteModeration.PENDING to PublicNoteStatus.IN_REVIEW,
                PublicNoteModeration.APPROVED to PublicNoteStatus.PUBLISHED,
                PublicNoteModeration.REJECTED to PublicNoteStatus.NOT_PUBLISHED,
            )
            ) {
                val viewModel =
                    diary(
                        running,
                        FakeDiaryVisitRepository(listOf(noteVisit("빛"))),
                        notes = FakePublicNoteRepository(listOf(publicNote("빛", moderation))),
                        auth = signedIn(),
                    )
                observe(viewModel)
                assertEquals(status, viewModel.state.value.noteStatus, moderation.name)
                viewModel.startEditing()
                assertEquals(
                    status,
                    viewModel.state.value.editor!!
                        .noteStatus,
                    moderation.name,
                )
                assertEquals(
                    DiaryVisibility.EXHIBITION_PAGE,
                    viewModel.state.value.editor!!
                        .shownVisibility,
                )
            }
        }

    @Test
    fun anEditedPublishedNoteKeepsItsPublicTextUntilTheAuthorAsksAgain() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.APPROVED)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            assertFalse(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.updateNote("빛과 그림자")
            assertTrue(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.save()

            assertEquals("빛과 그림자", visits.saved.single().note)
            assertTrue(notes.calls.isEmpty())

            viewModel.startEditing()
            assertTrue(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.askAgain()
            assertFalse(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.save()

            assertEquals(listOf("publish:running"), notes.calls)
        }

    @Test
    fun aNoteInReviewEditedAfterwardsCanBeSentAgainWithTheEdit() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.PENDING)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("빛과 그림자")
            assertTrue(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.askAgain()
            viewModel.save()

            assertEquals(listOf("publish:running"), notes.calls)
        }

    @Test
    fun aRejectedNoteCanBeEditedAndAskedAgain() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("광고")))
            val notes = FakePublicNoteRepository(listOf(publicNote("광고", PublicNoteModeration.REJECTED)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            assertTrue(
                viewModel.state.value.editor!!
                    .offersEditAndAskAgain,
            )
            viewModel.askAgain()
            val editor = viewModel.state.value.editor!!
            assertFalse(editor.offersEditAndAskAgain)
            assertTrue(editor.focusNote)
            viewModel.updateNote("작품 이야기")
            viewModel.save()

            assertEquals("작품 이야기", visits.saved.single().note)
            assertEquals(listOf("publish:running"), notes.calls)
        }

    /**
     * Staging defect: 수정 on a rejected 감상 opened on the note with the keyboard up, which hid 수정해서 다시 요청, and
     * a save then left the note at 공개 안 됨 (FR-020). The note waits for that action to take the focus.
     */
    @Test
    fun editOnARejectedNoteOpensOnItsAskAgainRatherThanTheNote() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("광고")))
            val notes = FakePublicNoteRepository(listOf(publicNote("광고", PublicNoteModeration.REJECTED)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.editOwnNote()
            val opened = viewModel.state.value.editor!!
            assertTrue(opened.offersEditAndAskAgain)
            assertFalse(opened.focusNote)

            viewModel.askAgain()
            assertTrue(
                viewModel.state.value.editor!!
                    .focusNote,
            )
        }

    @Test
    fun editOnANoteInReviewOrPublishedOpensOnTheNote() =
        runTest(dispatcher) {
            listOf(PublicNoteModeration.PENDING, PublicNoteModeration.APPROVED).forEach { moderation ->
                val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
                val notes = FakePublicNoteRepository(listOf(publicNote("빛", moderation)))
                val viewModel = diary(running, visits, notes = notes, auth = signedIn())
                observe(viewModel)

                viewModel.editOwnNote()
                assertTrue(
                    viewModel.state.value.editor!!
                        .focusNote,
                    "$moderation",
                )
            }
        }

    @Test
    fun choosingOnlyMeAgainUnpublishesAndKeepsTheNote() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.APPROVED)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            viewModel.selectVisibility(DiaryVisibility.ONLY_ME)
            assertTrue(
                viewModel.state.value.editor!!
                    .hasUnsavedChanges,
            )
            viewModel.save()

            assertEquals(listOf("unpublish:running"), notes.calls)
            assertTrue(visits.saved.isEmpty())
            assertEquals(
                "빛",
                visits.visits.value
                    .single()
                    .note,
            )
        }

    @Test
    fun savingWithoutANoteWarnsThatThePublicNoteGoesToo() =
        runTest(dispatcher) {
            val viewModel =
                diary(
                    running,
                    FakeDiaryVisitRepository(listOf(noteVisit("빛"))),
                    notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.APPROVED))),
                    auth = signedIn(),
                )
            observe(viewModel)
            viewModel.startEditing()
            assertFalse(
                viewModel.state.value.editor!!
                    .savingRemovesPublicNote,
            )

            viewModel.updateNote("   ")

            assertTrue(
                viewModel.state.value.editor!!
                    .savingRemovesPublicNote,
            )
            assertEquals(
                DiaryPublishAction.NONE,
                viewModel.state.value.editor!!
                    .publishAction,
            )
        }

    @Test
    fun theApprovalLineShowsOnceAndIsAcknowledged() =
        runTest(dispatcher) {
            val notes =
                FakePublicNoteRepository(
                    listOf(publicNote("빛", PublicNoteModeration.APPROVED).copy(approvalSeen = false)),
                )
            val viewModel =
                diary(running, FakeDiaryVisitRepository(listOf(noteVisit("빛"))), notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            assertTrue(
                viewModel.state.value.editor!!
                    .showApprovalLine,
            )
            assertEquals(listOf("ack:running"), notes.calls)

            viewModel.discardEditing()
            viewModel.startEditing()
            assertFalse(
                viewModel.state.value.editor!!
                    .showApprovalLine,
            )
            assertEquals(listOf("ack:running"), notes.calls)
        }

    @Test
    fun writeANotePreselectsTheExhibitionPageAndAGuestReturnsToTheSheetAfterSignIn() =
        runTest(dispatcher) {
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val visits = FakeDiaryVisitRepository()
            val notes = FakePublicNoteRepository()
            val viewModel = diary(running, visits, notes = notes, auth = auth)
            observe(viewModel)

            viewModel.startEditing(focusNote = true)
            viewModel.updateNote("빛")
            assertFalse(
                viewModel.state.value.editor!!
                    .isSignedIn,
            )
            assertTrue(
                viewModel.state.value.editor!!
                    .hasUnsavedChanges,
            )

            // Sign-in opens over the page; the draft waits in the ViewModel and the sheet shows it again.
            auth.value = signedIn().value
            val editor = viewModel.state.value.editor!!
            assertTrue(editor.isSignedIn)
            assertEquals("빛", editor.note)
            assertEquals(DiaryVisibility.EXHIBITION_PAGE, editor.shownVisibility)

            viewModel.save()
            assertEquals(1, visits.saved.size)
            assertEquals(listOf("publish:running"), notes.calls)
        }

    @Test
    fun aGuestNeverPublishes() =
        runTest(dispatcher) {
            val notes = FakePublicNoteRepository()
            val viewModel = diary(running, notes = notes)
            observe(viewModel)

            viewModel.startEditing(focusNote = true)
            viewModel.updateNote("빛")
            viewModel.save()

            assertTrue(notes.calls.isEmpty())
        }

    @Test
    fun aFailedPublishIsShownAndLogsOnlyTheType() =
        runTest(dispatcher) {
            val notes = FakePublicNoteRepository().apply { failure = IllegalStateException("secret") }
            val viewModel = diary(running, notes = notes, auth = signedIn())
            observe(viewModel)
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            try {
                viewModel.startEditing(focusNote = true)
                viewModel.updateNote("빛")
                viewModel.save()
            } finally {
                Logger.setLogWriters(platformLogWriter())
            }

            assertEquals(DiaryEntryFailure.PUBLISH, viewModel.state.value.failure)
            assertEquals(
                "operation=diary_note_publish_failed outcome=failure cause=IllegalStateException",
                logs.messages.single { "diary_note_publish_failed" in it },
            )
        }

    @Test
    fun theStateSaysWhileASaveIsStillGoing() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val visits = FakeDiaryVisitRepository().apply { this.gate = gate }
            val viewModel = diary(running, visits)
            observe(viewModel)

            viewModel.startEditing()
            viewModel.save()
            assertNull(viewModel.state.value.editor)
            assertTrue(viewModel.state.value.isWorking)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isWorking)
        }

    // ── End-to-end defects 2, 5, 7 and 8 ──

    @Test
    fun writeANoteOnAnEntryWithAPrivateNoteKeepsOnlyMe() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("나만 볼 메모")))
            val notes = FakePublicNoteRepository()
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing(focusNote = true)
            val editor = viewModel.state.value.editor!!
            assertTrue(editor.focusNote)
            assertEquals(DiaryVisibility.ONLY_ME, editor.visibility)
            assertEquals(DiaryVisibility.ONLY_ME, editor.shownVisibility)
            assertFalse(editor.hasUnsavedChanges)

            viewModel.updateNote("나만 볼 메모, 조금 더")
            viewModel.save()
            // Owner decision 10: nothing goes to review without the visitor choosing it.
            assertTrue(notes.calls.isEmpty())
        }

    @Test
    fun writeANoteOnAnEntryWithoutANotePreselectsTheExhibitionPage() =
        runTest(dispatcher) {
            val emptyNote = FakeDiaryVisitRepository(listOf(visit(running).copy(visitDate = today)))
            val viewModel = diary(running, emptyNote, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing(focusNote = true)
            assertEquals(
                DiaryVisibility.EXHIBITION_PAGE,
                viewModel.state.value.editor!!
                    .visibility,
            )

            val fresh = diary(running, auth = signedIn())
            observe(fresh)
            fresh.startEditing(focusNote = true)
            assertEquals(
                DiaryVisibility.EXHIBITION_PAGE,
                fresh.state.value.editor!!
                    .visibility,
            )
        }

    @Test
    fun askingAgainSaysSavingWillAskAndOnlyMeTakesItBack() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.APPROVED)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("빛과 그림자")
            assertFalse(
                viewModel.state.value.editor!!
                    .askAgainPending,
            )
            viewModel.askAgain()
            var editor = viewModel.state.value.editor!!
            assertTrue(editor.askAgainPending)
            assertFalse(editor.offersAskAgainWithEdit)

            viewModel.selectVisibility(DiaryVisibility.ONLY_ME)
            editor = viewModel.state.value.editor!!
            assertFalse(editor.askAgainPending)
            assertFalse(editor.askAgain)

            // Back to the page without asking again: the button returns and a save sends nothing for review.
            viewModel.selectVisibility(DiaryVisibility.EXHIBITION_PAGE)
            assertTrue(
                viewModel.state.value.editor!!
                    .offersAskAgainWithEdit,
            )
            viewModel.save()
            assertTrue(notes.calls.isEmpty())
        }

    @Test
    fun anUndatedNoteFromAnOlderAppCanBeMadePrivateWithoutADate() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository()
            val notes = FakePublicNoteRepository(listOf(publicNote("1.15 감상", PublicNoteModeration.PENDING)))
            val viewModel = diary(running, visits, notes = notes, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            var editor = viewModel.state.value.editor!!
            assertNull(editor.visitDate)
            assertTrue(editor.needsDate)
            assertTrue(editor.canSave)

            viewModel.selectVisibility(DiaryVisibility.ONLY_ME)
            editor = viewModel.state.value.editor!!
            assertTrue(editor.canSave)
            viewModel.save()

            // The private note is kept in an undated entry (FR-020); the 감상 is withdrawn.
            val saved = visits.saved.single()
            assertNull(saved.visitDate)
            assertEquals("1.15 감상", saved.note)
            assertEquals(listOf("unpublish:running"), notes.calls)
        }

    @Test
    fun aNewVisitStillNeedsADate() =
        runTest(dispatcher) {
            val viewModel = diary(closed, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            assertFalse(
                viewModel.state.value.editor!!
                    .canSave,
            )
        }

    @Test
    fun openingTheSheetReadsTheAccountAgain() =
        runTest(dispatcher) {
            var refreshes = 0
            val viewModel = diary(running, auth = signedIn(), refreshAccount = { refreshes += 1 })
            observe(viewModel)

            viewModel.startEditing()
            advanceUntilIdle()
            assertEquals(1, refreshes)

            val guest = diary(running, refreshAccount = { refreshes += 1 })
            observe(guest)
            guest.startEditing()
            advanceUntilIdle()
            assertEquals(1, refreshes, "a guest's entries live on the device only")
        }

    @Test
    fun anEntryDeletedOnAnotherDeviceClosesTheSheetAndSaysSo() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val viewModel = diary(running, visits, auth = signedIn())
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("빛과 그림자")
            // The account's archive arrives without the entry.
            visits.visits.value = emptyList()
            advanceUntilIdle()

            assertNull(viewModel.state.value.editor)
            assertEquals(DiaryEntryFailure.REMOVED_ELSEWHERE, viewModel.state.value.failure)
            assertTrue(visits.saved.isEmpty())
        }

    @Test
    fun aSaveWaitsForTheOpeningReadAndNeverRecreatesAnEntryDeletedElsewhere() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val gate = CompletableDeferred<Unit>()
            val viewModel =
                diary(
                    running,
                    visits,
                    auth = signedIn(),
                    refreshAccount = {
                        gate.await()
                        visits.visits.value = emptyList()
                    },
                )
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("빛과 그림자")
            viewModel.save()
            assertTrue(visits.saved.isEmpty())

            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(visits.saved.isEmpty())
            assertNull(viewModel.state.value.editor)
            assertEquals(DiaryEntryFailure.REMOVED_ELSEWHERE, viewModel.state.value.failure)
            assertFalse(viewModel.state.value.isWorking)
        }

    @Test
    fun aSaveAfterTheOpeningReadWritesTheEntry() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val viewModel = diary(running, visits, auth = signedIn(), refreshAccount = {})
            observe(viewModel)

            viewModel.startEditing()
            viewModel.updateNote("빛과 그림자")
            viewModel.save()

            assertEquals("빛과 그림자", visits.saved.single().note)
            assertNull(viewModel.state.value.failure)
        }

    @Test
    fun aSheetLeftOpenWhileAnotherDeviceDeletedTheEntryReadsTheAccountOnSaveAndSendsNothing() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            var deletedElsewhere = false
            var reads = 0
            val viewModel =
                diary(
                    running,
                    visits,
                    auth = signedIn(),
                    refreshAccount = {
                        reads += 1
                        // The opening read still finds the entry; the other device deletes it while the sheet is open.
                        if (deletedElsewhere) visits.visits.value = emptyList()
                    },
                )
            observe(viewModel)

            viewModel.startEditing()
            advanceUntilIdle()
            assertEquals(1, reads)
            viewModel.updateNote("빛과 그림자")
            deletedElsewhere = true

            viewModel.save()
            advanceUntilIdle()

            assertEquals(2, reads, "the save reads the account once more")
            assertTrue(visits.saved.isEmpty())
            assertNull(viewModel.state.value.editor)
            assertEquals(DiaryEntryFailure.REMOVED_ELSEWHERE, viewModel.state.value.failure)
            assertFalse(viewModel.state.value.isWorking)
        }

    @Test
    fun aSaveWhoseAccountReadFailsStillSavesOnTheDevice() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            var reads = 0
            val viewModel =
                diary(
                    running,
                    visits,
                    auth = signedIn(),
                    refreshAccount = {
                        reads += 1
                        if (reads > 1) error("offline")
                    },
                )
            observe(viewModel)

            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.updateNote("빛과 그림자")
            viewModel.save()
            advanceUntilIdle()

            assertEquals(2, reads)
            assertEquals("빛과 그림자", visits.saved.single().note)
            assertNull(viewModel.state.value.failure)
        }

    @Test
    fun aBrandNewEntrySavesWithoutASecondAccountRead() =
        runTest(dispatcher) {
            val visits = FakeDiaryVisitRepository()
            var reads = 0
            val viewModel = diary(running, visits, auth = signedIn(), refreshAccount = { reads += 1 })
            observe(viewModel)

            viewModel.startEditing()
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            assertEquals(1, reads)
            assertEquals(1, visits.saved.size)
            assertNull(viewModel.state.value.failure)
        }

    @Test
    fun enteringTheExhibitionPageReadsTheAccountAtMostEveryThirtySeconds() =
        runTest(dispatcher) {
            val clock = MutableClock(now)
            var reads = 0
            val viewModel = diary(running, auth = signedIn(), refreshAccount = { reads += 1 }, clock = clock)
            observe(viewModel)

            viewModel.onPageShown()
            advanceUntilIdle()
            assertEquals(1, reads, "the page reads the account, so a status changed by review shows")

            clock.instant += 10.seconds
            viewModel.onPageShown()
            advanceUntilIdle()
            assertEquals(1, reads, "coming back within the interval does not read again")

            clock.instant += 25.seconds
            viewModel.onPageShown()
            advanceUntilIdle()
            assertEquals(2, reads)

            val guest = diary(running, refreshAccount = { reads += 1 }, clock = clock)
            observe(guest)
            guest.onPageShown()
            advanceUntilIdle()
            assertEquals(2, reads, "a guest's entries live on the device only")
        }

    @Test
    fun anApprovalReadOnEnteringThePageReachesTheAuthorsStatus() =
        runTest(dispatcher) {
            val notes = FakePublicNoteRepository(listOf(publicNote("빛", PublicNoteModeration.PENDING)))
            val visits = FakeDiaryVisitRepository(listOf(noteVisit("빛")))
            val viewModel =
                diary(
                    running,
                    visits,
                    notes = notes,
                    auth = signedIn(),
                    sync = MutableStateFlow(MyGallrSyncStatus.SYNCED),
                    refreshAccount = {
                        notes.notes.value = listOf(publicNote("빛", PublicNoteModeration.APPROVED))
                    },
                )
            observe(viewModel)
            assertEquals(PublicNoteStatus.IN_REVIEW, viewModel.state.value.noteStatus)

            viewModel.onPageShown()
            advanceUntilIdle()

            assertEquals(PublicNoteStatus.PUBLISHED, viewModel.state.value.noteStatus)
        }

    private fun TestScope.observe(viewModel: DiaryEntryViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun diary(
        exhibition: Exhibition,
        visits: com.gallr.shared.repository.VisitRepository = FakeDiaryVisitRepository(),
        notes: com.gallr.shared.repository.PublicNoteRepository = FakePublicNoteRepository(),
        onVisitRecorded: () -> Unit = {},
        auth: Flow<AuthState> = MutableStateFlow(AuthState.Anonymous),
        sync: Flow<MyGallrSyncStatus> = MutableStateFlow(MyGallrSyncStatus.DEVICE_ONLY),
        refreshAccount: suspend () -> Unit = {},
        clock: Clock = FixedClock(now),
    ) = DiaryEntryViewModel(
        exhibition = exhibition,
        visitRepository = visits,
        publicNoteRepository = notes,
        authState = auth,
        syncStatus = sync,
        onVisitRecorded = onVisitRecorded,
        refreshAccount = refreshAccount,
        clock = clock,
    )

    private fun signedIn(refreshPending: Boolean = false): MutableStateFlow<AuthState> =
        MutableStateFlow(
            AuthState.Authenticated(GallrUser("user-1", "하나", null), isSessionRefreshPending = refreshPending),
        )

    private fun visit(exhibition: Exhibition) =
        ExhibitionVisit(
            clientRecordId = "${exhibition.id}:1",
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = Instant.parse("2026-11-01T00:00:00Z"),
        )

    private fun publicNote(
        content: String,
        moderation: PublicNoteModeration,
    ) = OwnPublicNote(
        exhibitionId = running.id,
        thoughtId = "thought-1",
        content = content,
        moderation = moderation,
        rejectionReason = if (moderation == PublicNoteModeration.REJECTED) ThoughtRejectionReason.OTHER else null,
        requestedAt = Instant.parse("2026-10-01T00:00:00Z"),
        approvedAt = null,
        approvalSeen = true,
    )

    private fun noteVisit(note: String) = visit(running).copy(visitDate = today, note = note)

    private val running = exhibition("running", LocalDate(2026, 11, 1), LocalDate(2026, 11, 30))
    private val closed = exhibition("closed", LocalDate(2026, 10, 1), LocalDate(2026, 11, 5))
    private val upcoming = exhibition("upcoming", LocalDate(2026, 11, 20), LocalDate(2026, 12, 20))

    private fun exhibition(
        id: String,
        opening: LocalDate,
        closing: LocalDate,
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "장소",
        venueNameEn = "Venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "",
        regionEn = "",
        openingDate = opening,
        closingDate = closing,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    private fun LocalDate.minusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() - days)

    private class MutableClock(
        var instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private class CapturingLogWriter : LogWriter() {
        val messages = mutableListOf<String>()

        override fun log(
            severity: Severity,
            message: String,
            tag: String,
            throwable: Throwable?,
        ) {
            messages += message
        }
    }

    private class InMemoryAccountStore : MyGallrAccountStore {
        private val accounts = MutableStateFlow<Map<String, StoredMyGallrAccountState>>(emptyMap())

        fun state(userId: String) = accounts.value[userId] ?: StoredMyGallrAccountState()

        override fun observe(userId: String): Flow<StoredMyGallrAccountState> =
            accounts.map { it[userId] ?: StoredMyGallrAccountState() }

        override suspend fun get(userId: String): StoredMyGallrAccountState = state(userId)

        override suspend fun update(
            userId: String,
            transform: (StoredMyGallrAccountState) -> StoredMyGallrAccountState,
        ) {
            accounts.value = accounts.value + (userId to transform(state(userId)))
        }
    }

    private object TokenlessSource : MyGallrAccountCommandSource {
        override suspend fun sync(mutations: List<MyGallrAccountMutation>): MyGallrAccountArchive =
            throw MyGallrAccountAuthenticationRequiredException()
    }

    private object NoFollowedGalleries : FollowedGalleryRepository {
        override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = MutableStateFlow(emptyList())

        override suspend fun followGalleries(galleries: List<FollowedGallery>) = Unit

        override suspend fun unfollowGallery(galleryKey: String) = Unit

        override suspend fun acknowledgeGallery(
            galleryKey: String,
            currentExhibitionIds: Set<String>,
        ) = Unit
    }
}
