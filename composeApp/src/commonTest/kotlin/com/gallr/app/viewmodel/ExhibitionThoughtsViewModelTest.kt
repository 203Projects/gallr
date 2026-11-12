package com.gallr.app.viewmodel

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PendingNoteMutation
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.ThoughtNoLongerPublicException
import com.gallr.shared.repository.ThoughtReportAlreadySentException
import com.gallr.shared.repository.ThoughtReportSignInRequiredException
import com.gallr.shared.thoughts.ThoughtReportReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 103 US4: 감상 read through the reader RPC, reported, and hidden per author on this device. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExhibitionThoughtsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val reader = AuthState.Authenticated(GallrUser("reader", "Reader", null))
    private val refreshPending = reader.copy(isSessionRefreshPending = true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theListComesFromTheReaderRpcWithoutHiddenAuthorsButWithTheReadersOwn() =
        runTest(dispatcher) {
            val own = publicThought("own", authorId = "reader")
            val hiddenOne = publicThought("hidden", authorId = "b")
            val other = publicThought("other", authorId = "c")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(own, hiddenOne, other)))
            val hidden = FakeHiddenThoughtAuthorRepository(listOf(hiddenAuthor("b"), hiddenAuthor("reader")))

            val viewModel = thoughts(repository, hidden = hidden)
            advanceUntilIdle()

            assertEquals(1, repository.listCalls)
            assertEquals(listOf(own, other), viewModel.uiState.value.thoughts)
            assertTrue(viewModel.uiState.value.hasLoaded)
            assertTrue(viewModel.uiState.value.hasUserThought)
            assertEquals("reader", viewModel.uiState.value.currentUserId)
        }

    @Test
    fun aFailedFirstLoadIsNotShownAsAnEmptyListAndLogsOnlyTheType() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(publicThoughtsResult = Result.failure(IllegalStateException("secret text")))
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            val viewModel =
                try {
                    thoughts(repository).also { advanceUntilIdle() }
                } finally {
                    Logger.setLogWriters(platformLogWriter())
                }

            assertTrue(viewModel.uiState.value.loadFailed)
            assertFalse(viewModel.uiState.value.hasLoaded)
            assertEquals(emptyList(), viewModel.uiState.value.thoughts)
            val line = logs.messages.single { "thought_list_failed" in it }
            assertEquals("operation=thought_list_failed outcome=failure cause=IllegalStateException", line)
        }

    @Test
    fun aFailedRefreshKeepsTheLastList() =
        runTest(dispatcher) {
            val listed = publicThought("1")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(listed)))
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            repository.publicThoughtsResult = Result.failure(IllegalStateException("offline"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(listed), viewModel.uiState.value.thoughts)
            assertTrue(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun hidingAnAuthorRemovesAllTheirThoughtsAndUndoBringsThemBack() =
        runTest(dispatcher) {
            val first = publicThought("1", authorId = "b", displayName = "민지")
            val second = publicThought("2", authorId = "b", displayName = "민지")
            val other = publicThought("3", authorId = "c")
            val repository =
                FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(first, other, second)))
            val hidden = FakeHiddenThoughtAuthorRepository()
            val viewModel = thoughts(repository, auth = AuthState.Anonymous, hidden = hidden)
            advanceUntilIdle()

            viewModel.hideAuthor(first)
            advanceUntilIdle()

            assertEquals(listOf(other), viewModel.uiState.value.thoughts)
            assertEquals(listOf("b"), hidden.authors.value.map { it.userId })
            assertEquals("민지", hidden.authors.value.single().displayName)
            assertEquals(ThoughtsMessage.AuthorHidden("b", "민지"), viewModel.uiState.value.message)
            assertEquals(1, repository.listCalls)

            viewModel.messageShown()
            viewModel.undoHideAuthor("b")
            advanceUntilIdle()

            assertEquals(listOf(first, other, second), viewModel.uiState.value.thoughts)
            assertEquals(emptyList(), hidden.authors.value)
            assertNull(viewModel.uiState.value.message)
        }

    @Test
    fun aFailedHideKeepsTheThoughtsAndSaysSo() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(thought)))
            val hidden = FakeHiddenThoughtAuthorRepository().apply { failure = IllegalStateException("disk") }
            val viewModel = thoughts(repository, hidden = hidden)
            advanceUntilIdle()

            viewModel.hideAuthor(thought)
            advanceUntilIdle()

            assertEquals(listOf(thought), viewModel.uiState.value.thoughts)
            assertEquals(ThoughtsMessage.HideFailed, viewModel.uiState.value.message)
        }

    @Test
    fun aSentReportClosesTheSheetSaysReportedAndReadsTheListAgain() =
        runTest(dispatcher) {
            val reported = publicThought("1", authorId = "b")
            val other = publicThought("2", authorId = "c")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(reported, other)))
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            viewModel.startReport(reported)
            assertEquals(reported, viewModel.uiState.value.reportTarget)

            val gate = CompletableDeferred<Unit>()
            repository.reportGate = gate
            // The server leaves the reported 감상 out for this reader from now on.
            repository.publicThoughtsResult = Result.success(listOf(other))
            viewModel.report(ThoughtReportReason.PROMOTIONAL)
            assertTrue(viewModel.uiState.value.reportBusy)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("1" to ThoughtReportReason.PROMOTIONAL), repository.reports)
            assertNull(viewModel.uiState.value.reportTarget)
            assertFalse(viewModel.uiState.value.reportBusy)
            assertEquals(ThoughtsMessage.Reported, viewModel.uiState.value.message)
            assertEquals(2, repository.listCalls)
            assertEquals(listOf(other), viewModel.uiState.value.thoughts)
        }

    @Test
    fun aSecondReportOfTheSameTextSaysAlreadyReported() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository =
                FakeThoughtRepository(
                    publicThoughtsResult = Result.success(listOf(thought)),
                    reportResult = Result.failure(ThoughtReportAlreadySentException()),
                )
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            viewModel.startReport(thought)
            viewModel.report(ThoughtReportReason.OTHER)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.reportTarget)
            assertEquals(ThoughtsMessage.AlreadyReported, viewModel.uiState.value.message)
            assertEquals(2, repository.listCalls)
        }

    @Test
    fun aThoughtAlreadyReportedAndKeptOffersNoSheet() =
        runTest(dispatcher) {
            val kept = publicThought("1", authorId = "b", reportedByMe = true)
            val viewModel = thoughts(FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(kept))))
            advanceUntilIdle()

            viewModel.startReport(kept)

            assertNull(viewModel.uiState.value.reportTarget)
            assertFalse(viewModel.uiState.value.signInRequested)
        }

    @Test
    fun aFailedReportSaysSoAndLogsOnlyTheType() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository =
                FakeThoughtRepository(
                    publicThoughtsResult = Result.success(listOf(thought)),
                    reportResult = Result.failure(IllegalStateException("secret reason")),
                )
            val viewModel = thoughts(repository)
            advanceUntilIdle()
            val logs = CapturingLogWriter()
            Logger.setLogWriters(logs)
            try {
                viewModel.startReport(thought)
                viewModel.report(ThoughtReportReason.INAPPROPRIATE)
                advanceUntilIdle()
            } finally {
                Logger.setLogWriters(platformLogWriter())
            }

            assertNull(viewModel.uiState.value.reportTarget)
            assertFalse(viewModel.uiState.value.reportBusy)
            assertEquals(ThoughtsMessage.ReportFailed, viewModel.uiState.value.message)
            assertEquals(listOf(thought), viewModel.uiState.value.thoughts)
            val line = logs.messages.single { "thought_report_failed" in it }
            assertEquals("operation=thought_report_failed outcome=failure cause=IllegalStateException", line)
        }

    @Test
    fun aThoughtNoLongerPublicFailsGenericallyAndRefreshes() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository =
                FakeThoughtRepository(
                    publicThoughtsResult = Result.success(listOf(thought)),
                    reportResult = Result.failure(ThoughtNoLongerPublicException()),
                )
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            repository.publicThoughtsResult = Result.success(emptyList())
            viewModel.startReport(thought)
            viewModel.report(ThoughtReportReason.OTHER)
            advanceUntilIdle()

            assertEquals(ThoughtsMessage.ReportFailed, viewModel.uiState.value.message)
            assertEquals(2, repository.listCalls)
            assertEquals(emptyList(), viewModel.uiState.value.thoughts)
        }

    @Test
    fun aGuestReportAsksForSignInAndTheSheetOpensAfterSignIn() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(thought)))
            val viewModel = thoughts(repository, auth = AuthState.Anonymous)
            advanceUntilIdle()

            viewModel.startReport(thought)

            assertTrue(viewModel.uiState.value.signInRequested)
            assertNull(viewModel.uiState.value.reportTarget)
            viewModel.onSignInRequestHandled()
            assertFalse(viewModel.uiState.value.signInRequested)

            viewModel.onAuthStateChanged(reader)
            advanceUntilIdle()

            assertEquals(2, repository.listCalls)
            assertEquals(thought, viewModel.uiState.value.reportTarget)
            assertEquals(emptyList(), repository.reports)
        }

    @Test
    fun aSessionTheServerDoesNotAcceptForReportsAsksForSignIn() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository =
                FakeThoughtRepository(
                    publicThoughtsResult = Result.success(listOf(thought)),
                    reportResult = Result.failure(ThoughtReportSignInRequiredException()),
                )
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            viewModel.startReport(thought)
            viewModel.report(ThoughtReportReason.OTHER)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.reportTarget)
            assertTrue(viewModel.uiState.value.signInRequested)
        }

    @Test
    fun whileTheSessionRefreshIsPendingTheListDoesNotReloadAndReportAsksToCheckTheConnection() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(thought)))
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            viewModel.onAuthStateChanged(refreshPending)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(1, repository.listCalls)

            viewModel.startReport(thought)
            assertNull(viewModel.uiState.value.reportTarget)
            assertFalse(viewModel.uiState.value.signInRequested)
            assertEquals(ThoughtsMessage.CheckConnection, viewModel.uiState.value.message)

            // Once the refresh lands, the list is read again with the session, so the reporter filter applies.
            viewModel.onAuthStateChanged(reader)
            advanceUntilIdle()
            assertEquals(2, repository.listCalls)
        }

    @Test
    fun aPageOpenedWhileTheRefreshIsPendingStillShowsTheList() =
        runTest(dispatcher) {
            val thought = publicThought("1", authorId = "b")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(thought)))

            val viewModel = thoughts(repository, auth = refreshPending)
            advanceUntilIdle()

            assertEquals(listOf(thought), viewModel.uiState.value.thoughts)
            assertEquals("reader", viewModel.uiState.value.currentUserId)
        }

    @Test
    fun theReadersOwnThoughtIsNeitherReportedNorHidden() =
        runTest(dispatcher) {
            val own = publicThought("own", authorId = "reader")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(own)))
            val hidden = FakeHiddenThoughtAuthorRepository()
            val viewModel = thoughts(repository, hidden = hidden)
            advanceUntilIdle()

            viewModel.startReport(own)
            viewModel.hideAuthor(own)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.reportTarget)
            assertEquals(emptyList(), hidden.authors.value)
            assertEquals(listOf(own), viewModel.uiState.value.thoughts)
        }

    @Test
    fun unpublishingTheReadersOwnThoughtGoesThroughThePublicNotesAndLeavesThePage() =
        runTest(dispatcher) {
            val own = publicThought("own", authorId = "reader")
            val other = publicThought("other", authorId = "c")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(own, other)))
            val notes = RecordingPublicNoteRepository()
            val viewModel = thoughts(repository, notes = notes)
            advanceUntilIdle()

            viewModel.unpublishOwn(own)
            advanceUntilIdle()

            assertEquals(listOf(EXHIBITION), notes.unpublished)
            assertEquals(listOf(other), viewModel.uiState.value.thoughts)
            assertFalse(viewModel.uiState.value.hasUserThought)
        }

    @Test
    fun aFailedUnpublishKeepsTheThoughtAndSaysSo() =
        runTest(dispatcher) {
            val own = publicThought("own", authorId = "reader")
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(own)))
            val notes = RecordingPublicNoteRepository().apply { failure = IllegalStateException("no account") }
            val viewModel = thoughts(repository, notes = notes)
            advanceUntilIdle()

            viewModel.unpublishOwn(own)
            advanceUntilIdle()

            assertEquals(listOf(own), viewModel.uiState.value.thoughts)
            assertEquals(ThoughtsMessage.UnpublishFailed, viewModel.uiState.value.message)
        }

    @Test
    fun signingOutReadsTheListAgainAsAGuest() =
        runTest(dispatcher) {
            val repository = FakeThoughtRepository(publicThoughtsResult = Result.success(listOf(publicThought("1"))))
            val viewModel = thoughts(repository)
            advanceUntilIdle()

            viewModel.onAuthStateChanged(reader)
            assertEquals(1, repository.listCalls)

            viewModel.onAuthStateChanged(AuthState.Anonymous)
            advanceUntilIdle()

            assertEquals(2, repository.listCalls)
            assertNull(viewModel.uiState.value.currentUserId)
        }

    @Test
    fun theOwnPendingThoughtStillLoadsUntilThePublishSliceReplacesIt() =
        runTest(dispatcher) {
            val pending = thought("pending-1").copy(isApproved = false)
            val repository =
                FakeThoughtRepository(
                    publicThoughtsResult = Result.success(emptyList()),
                    userExhibitionThoughtResult = Result.success(pending),
                )

            val viewModel = thoughts(repository)
            advanceUntilIdle()

            assertEquals(pending, viewModel.uiState.value.ownPendingThought)
            assertTrue(viewModel.uiState.value.hasUserThought)
        }

    private fun thoughts(
        repository: FakeThoughtRepository,
        auth: AuthState = reader,
        hidden: FakeHiddenThoughtAuthorRepository = FakeHiddenThoughtAuthorRepository(),
        notes: PublicNoteRepository = RecordingPublicNoteRepository(),
    ) = ExhibitionThoughtsViewModel(
        exhibitionId = EXHIBITION,
        authState = auth,
        thoughtRepository = repository,
        hiddenThoughtAuthorRepository = hidden,
        publicNoteRepository = notes,
    )

    private class RecordingPublicNoteRepository : PublicNoteRepository {
        val unpublished = mutableListOf<String>()
        var failure: Exception? = null

        override fun observeOwnNotes(): Flow<List<OwnPublicNote>> = MutableStateFlow(emptyList())

        override fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>> =
            MutableStateFlow(emptyMap())

        override suspend fun publish(exhibitionId: String) = Unit

        override suspend fun unpublish(exhibitionId: String) {
            failure?.let { throw it }
            unpublished += exhibitionId
        }

        override suspend fun acknowledgeApproval(exhibitionId: String) = Unit
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

    private companion object {
        const val EXHIBITION = "exhibition-1"
    }
}
