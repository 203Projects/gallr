package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteException
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteReportReason
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 089 US9 (DD2, DD7, DD21, DD22, R8): the 추천 동선 list and the read-only preview. */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicRoutesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Thursday 2026-10-08, 13:00 in Seoul. */
    private val now = Instant.parse("2026-10-08T04:00:00Z")
    private val today = LocalDate(2026, 10, 8)
    private val signedIn = AuthState.Authenticated(GallrUser("account-1", "하나", null))
    private val catalogue = listOf(exhibition("a", 37.570), exhibition("b", 37.575))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theListShowsTheServersOrderThreeAtFirst() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = (1..5).map { row("p$it") } }
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            val list = assertIs<PublicRoutesListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), list.rows.map { it.id })
            val visible = viewModel.state.value.visibleRows
            assertEquals(listOf("p1", "p2", "p3"), visible.map { it.id })
            assertTrue(viewModel.state.value.canExpand)

            viewModel.toggleExpanded()
            assertEquals(5, viewModel.state.value.visibleRows.size)
            viewModel.toggleExpanded()
            assertEquals(3, viewModel.state.value.visibleRows.size)
        }

    @Test
    fun threeOrFewerRoutesNeedNoExpansion() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("p1"), row("p2")) }
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            assertFalse(viewModel.state.value.canExpand)
        }

    @Test
    fun anEmptyListHidesTheSection() =
        runTest(dispatcher) {
            val viewModel = publicRoutes(FakePersonalRouteRepository())
            observe(viewModel)

            assertEquals(PublicRoutesListState.Hidden, viewModel.state.value.list)
        }

    @Test
    fun aFailedLoadOffersARetry() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicListFailures += PersonalRouteFailure.Network }
            val viewModel = publicRoutes(routes)
            observe(viewModel)
            assertEquals(PublicRoutesListState.Error, viewModel.state.value.list)

            routes.publicRoutes = listOf(row("p1"))
            viewModel.retry()
            advanceUntilIdle()

            assertIs<PublicRoutesListState.Loaded>(viewModel.state.value.list)
        }

    @Test
    fun aPreviewOfARouteOpenTodayIsJudgedForToday() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            viewModel.openPreview(row("p1"))
            advanceUntilIdle()

            val preview = assertNotNull(viewModel.state.value.preview)
            assertEquals("p1", preview.summary.id)
            assertEquals(listOf("a", "b"), preview.route?.stops?.map { it.exhibitionId })
            assertNull(preview.laterDay)
            assertEquals(today, preview.evaluation?.plannedDay)
            assertFalse(preview.isOwn)
        }

    @Test
    fun aRouteThatOnlyWorksLaterIsJudgedForItsFirstSharedDay() =
        runTest(dispatcher) {
            val later = row("p1", firstSharedDay = LocalDate(2026, 10, 12))
            val viewModel = publicRoutes(listedRoute(later))
            observe(viewModel)

            viewModel.openPreview(later)
            advanceUntilIdle()

            val preview = assertNotNull(viewModel.state.value.preview)
            assertEquals(LocalDate(2026, 10, 12), preview.laterDay)
            assertEquals(LocalDate(2026, 10, 12), preview.evaluation?.plannedDay)
        }

    @Test
    fun theReadersOwnRouteIsRecognised() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { publicMine += "p1" }
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            viewModel.openPreview(row("p1"))
            advanceUntilIdle()

            assertTrue(assertNotNull(viewModel.state.value.preview).isOwn)
        }

    @Test
    fun aRouteNoLongerShownSaysSoAndReloadsTheList() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("gone")) }
            val viewModel = publicRoutes(routes)
            observe(viewModel)
            val callsBefore = routes.publicListCalls

            viewModel.openPreview(row("gone"))
            advanceUntilIdle()

            val preview = viewModel.state.value.preview
            assertEquals(PublicRouteNotice.NO_LONGER_LISTED, preview?.notice)
            assertTrue(routes.publicListCalls > callsBefore)
        }

    // Generated by /ship test coverage audit (spec 089).
    // Value: protects=a stops read that fails shows LOAD_FAILED, blocks copy, and the screen's retry reads again;
    // fails_when=the failure branch stops setting the notice, leaves loading on, or a copy runs without stops;
    // why_new=FakePersonalRouteRepository.loadPublicStops cannot fail, so no test reached LOAD_FAILED; seam=none
    @Test
    fun aPreviewThatCannotBeReadSaysSoAndReadsAgainOnRetry() =
        runTest(dispatcher) {
            val listed = listedRoute(row("p1"))
            val viewModel = publicRoutes(PreviewReads(listed, failures = 1))
            observe(viewModel)

            viewModel.openPreview(row("p1"))
            advanceUntilIdle()

            val failed = preview(viewModel)
            assertEquals(PublicRouteNotice.LOAD_FAILED, failed.notice)
            assertFalse(failed.loading)
            assertNull(failed.route)
            viewModel.copy()
            advanceUntilIdle()
            assertTrue(listed.copied.isEmpty(), "nothing can be copied before the stops are read")

            viewModel.openPreview(failed.summary)
            advanceUntilIdle()

            val shown = preview(viewModel)
            assertNull(shown.notice)
            assertEquals(listOf("a", "b"), shown.route?.stops?.map { it.exhibitionId })
        }

    // Generated by /ship test coverage audit (spec 089).
    // Value: protects=a late stops answer for a preview the reader already left never changes the preview on screen;
    // fails_when=openPreview applies an answer without checking it is for the open route (clears or mislabels it);
    // why_new=every existing preview test opens one route with an immediate answer, so no answer arrives late;
    // seam=none
    @Test
    fun aLateAnswerForAPreviewTheReaderLeftDoesNotTouchTheOneOnScreen() =
        runTest(dispatcher) {
            val listed =
                listedRoute(row("p1")).apply {
                    publicRoutes = listOf(row("p1"), row("p2"))
                    publicStops["p2"] = publicStops.getValue("p1").copy(id = "p2", name = "동선 p2")
                }
            val routes = PreviewReads(listed, heldRouteId = "p1")
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            viewModel.openPreview(row("p1"))
            viewModel.closePreview()
            viewModel.openPreview(row("p2"))
            advanceUntilIdle()
            routes.release()
            advanceUntilIdle()

            val shown = assertNotNull(viewModel.state.value.preview, "the late answer cleared the open preview")
            assertEquals("p2", shown.summary.id)
            assertEquals("p2", shown.route?.id)
            assertFalse(shown.loading)
            assertNull(shown.notice)
        }

    @Test
    fun closingThePreviewClearsIt() =
        runTest(dispatcher) {
            val viewModel = publicRoutes(listedRoute(row("p1")))
            observe(viewModel)
            viewModel.openPreview(row("p1"))
            advanceUntilIdle()

            viewModel.closePreview()

            assertNull(viewModel.state.value.preview)
        }

    @Test
    fun openingTheSheetAgainReadsTheListQuietly() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("p1")) }
            val viewModel = publicRoutes(routes)
            observe(viewModel)
            val seen = mutableListOf<PublicRoutesListState>()
            backgroundScope.launch { viewModel.state.collect { seen += it.list } }

            routes.publicRoutes = listOf(row("p2"), row("p1"))
            viewModel.sheetShown()
            advanceUntilIdle()

            val list = assertIs<PublicRoutesListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("p2", "p1"), list.rows.map { it.id })
            assertTrue(seen.none { it == PublicRoutesListState.Loading })
        }

    @Test
    fun aFailedQuietReadKeepsTheRowsOnScreen() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("p1")) }
            val viewModel = publicRoutes(routes)
            observe(viewModel)

            routes.publicListFailures += PersonalRouteFailure.Network
            viewModel.sheetShown()
            advanceUntilIdle()

            assertIs<PublicRoutesListState.Loaded>(viewModel.state.value.list)
        }

    @Test
    fun anEmptySectionAppearsOnceARouteIsListed() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            val viewModel = publicRoutes(routes)
            observe(viewModel)
            assertEquals(PublicRoutesListState.Hidden, viewModel.state.value.list)

            routes.publicRoutes = listOf(row("p1"))
            viewModel.sheetShown()
            advanceUntilIdle()

            assertIs<PublicRoutesListState.Loaded>(viewModel.state.value.list)
        }

    @Test
    fun openingTheSheetAgainWhileARereadIsRunningReadsOnce() =
        runTest(dispatcher) {
            val listed = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("p1")) }
            val routes = HeldList(listed)
            val viewModel = publicRoutes(routes)
            observe(viewModel)
            val callsBefore = listed.publicListCalls

            routes.hold = true
            viewModel.sheetShown()
            viewModel.sheetShown()
            routes.release()
            advanceUntilIdle()

            assertEquals(callsBefore + 1, listed.publicListCalls)
        }

    // Measurement (US11: P12)

    @Test
    fun eachShowingOfTheSheetIsCountedOnceWithTheRowsOnScreen() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { publicRoutes = (1..5).map { row("p$it") } }
            val analytics = RecordingRouteAnalytics()
            val viewModel = publicRoutes(routes, analytics = analytics)
            observe(viewModel)
            assertTrue(analytics.events.isEmpty(), "loading the list is not a showing")

            viewModel.sheetShown()
            advanceUntilIdle()
            // Expanding in place and reading again are not new showings.
            viewModel.toggleExpanded()
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(listOf("public_routes_viewed:3"), analytics.events)

            viewModel.sheetShown()
            advanceUntilIdle()
            assertEquals(listOf("public_routes_viewed:3", "public_routes_viewed:5"), analytics.events)
        }

    @Test
    fun aSheetShownWhileTheListIsStillLoadingIsCountedWhenTheRowsArrive() =
        runTest(dispatcher) {
            val listed = FakePersonalRouteRepository().apply { publicRoutes = listOf(row("p1")) }
            val routes = HeldList(listed).apply { hold = true }
            val analytics = RecordingRouteAnalytics()
            val viewModel = publicRoutes(routes, analytics = analytics)
            observe(viewModel)
            assertEquals(PublicRoutesListState.Loading, viewModel.state.value.list)

            viewModel.sheetShown()
            viewModel.sheetShown()
            assertTrue(analytics.events.isEmpty())
            routes.release()
            advanceUntilIdle()

            assertEquals(listOf("public_routes_viewed:1"), analytics.events)
            assertEquals(1, listed.publicListCalls)
        }

    @Test
    fun nothingIsCountedWhileTheSectionHasNoRows() =
        runTest(dispatcher) {
            val analytics = RecordingRouteAnalytics()
            val routes = FakePersonalRouteRepository()
            val viewModel = publicRoutes(routes, analytics = analytics)
            observe(viewModel)

            viewModel.sheetShown()
            advanceUntilIdle()
            assertTrue(analytics.events.isEmpty())

            // Rows that arrive later in the same showing are the first the reader sees.
            routes.publicRoutes = listOf(row("p1"))
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(listOf("public_routes_viewed:1"), analytics.events)
        }

    // Copy (US10: R9, R13, DD8, DD18)

    @Test
    fun aCopyIsCountedThenBecomesTheDraftAndOpensTheComposer() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = openedPreview(routes, drafts)

            viewModel.copy()
            advanceUntilIdle()

            assertEquals(listOf("p1"), routes.copied)
            assertEquals(listOf("동선 p1"), drafts.copies.map { it.first })
            assertEquals("draft-copy-1", preview(viewModel).copiedDraftId)
            assertFalse(preview(viewModel).copyBusy)
            viewModel.onCopyOpened()
            assertNull(preview(viewModel).copiedDraftId)
        }

    @Test
    fun anUnsavedDraftIsReplacedOnlyAfterConfirming() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository(catalogue.mapNotNull { it.toRouteStop() }, name = "작성 중")
            val viewModel = openedPreview(routes, drafts)

            viewModel.copy()
            advanceUntilIdle()
            assertTrue(preview(viewModel).confirmReplace)
            viewModel.cancelReplace()
            advanceUntilIdle()
            assertFalse(preview(viewModel).confirmReplace)
            assertTrue(routes.copied.isEmpty())
            assertEquals("작성 중", drafts.draft.value.route.name)

            viewModel.copy()
            advanceUntilIdle()
            viewModel.confirmReplace()
            advanceUntilIdle()
            assertEquals(listOf("p1"), routes.copied)
            assertEquals("동선 p1", drafts.draft.value.route.name)
        }

    @Test
    fun aDraftChangedDuringTheCopyIsLeftAloneAndCanBeRetried() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { copyGate = CompletableDeferred() }
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = openedPreview(routes, drafts)

            viewModel.copy()
            advanceUntilIdle()
            drafts.rename("다른 초안")
            routes.copyGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(PublicRouteMessage.DRAFT_CHANGED, preview(viewModel).message)
            assertEquals("다른 초안", drafts.draft.value.route.name)
            assertNull(preview(viewModel).copiedDraftId)

            routes.copyGate = null
            viewModel.retryMessage()
            advanceUntilIdle()
            assertEquals("동선 p1", drafts.draft.value.route.name)
        }

    @Test
    fun aFailedCopyLeavesTheDraftUntouched() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { copyFailures += PersonalRouteFailure.Network }
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = openedPreview(routes, drafts)

            viewModel.copy()
            advanceUntilIdle()

            assertEquals(PublicRouteMessage.COPY_FAILED, preview(viewModel).message)
            assertEquals("draft-1", drafts.draft.value.draftId)
            assertTrue(drafts.copies.isEmpty())
        }

    @Test
    fun aRouteThatLeftTheListCannotBeCopied() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { copyFailures += PersonalRouteFailure.NotListed }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())
            val callsBefore = routes.publicListCalls

            viewModel.copy()
            advanceUntilIdle()

            assertEquals(PublicRouteNotice.NO_LONGER_LISTED, preview(viewModel).notice)
            assertTrue(routes.publicListCalls > callsBefore)
        }

    @Test
    fun repeatedTapsWhileCopyingMakeOneCall() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { copyGate = CompletableDeferred() }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())

            repeat(3) { viewModel.copy() }
            advanceUntilIdle()
            assertTrue(preview(viewModel).copyBusy)

            routes.copyGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, routes.copied.size)
            assertFalse(preview(viewModel).copyBusy)
        }

    @Test
    fun aSignedOutCopyWaitsForSignInAndThenContinues() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository()
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = openedPreview(routes, drafts, auth)

            viewModel.copy()
            advanceUntilIdle()
            assertTrue(preview(viewModel).signInRequested)
            val pending = assertNotNull(drafts.draft.value.pendingAction)
            assertEquals(PendingKind.COPY, pending.kind)
            assertEquals("p1", pending.routeId)
            assertTrue(routes.copied.isEmpty())
            viewModel.onSignInRequestHandled()
            assertFalse(preview(viewModel).signInRequested)

            auth.value = signedIn
            advanceUntilIdle()

            assertEquals(listOf("p1", "p1"), routes.publicStopsReads, "the stops are read again once signed in")
            assertEquals(listOf("p1"), routes.copied)
            assertNull(drafts.draft.value.pendingAction)
            assertEquals("draft-copy-1", preview(viewModel).copiedDraftId)
        }

    @Test
    fun aCopyResumedAfterSignInStopsWhenTheRouteTurnsOutToBeTheReadersOwn() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository()
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = openedPreview(routes, drafts, auth)
            viewModel.copy()
            advanceUntilIdle()
            assertFalse(preview(viewModel).isOwn)

            // The signed-out read could not know the author; the signed-in one does (DD22).
            routes.publicMine += "p1"
            auth.value = signedIn
            advanceUntilIdle()

            assertTrue(preview(viewModel).isOwn)
            assertTrue(routes.copied.isEmpty())
            assertNull(drafts.draft.value.pendingAction)
            assertNull(preview(viewModel).copiedDraftId)
            assertNull(preview(viewModel).notice)
        }

    @Test
    fun aCopyResumedAfterSignInFindsTheRouteGoneFromTheList() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository()
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = openedPreview(routes, drafts, auth)
            viewModel.copy()
            advanceUntilIdle()
            val callsBefore = routes.publicListCalls

            routes.publicStops.remove("p1")
            auth.value = signedIn
            advanceUntilIdle()

            assertEquals(PublicRouteNotice.NO_LONGER_LISTED, preview(viewModel).notice)
            assertTrue(routes.copied.isEmpty())
            assertTrue(routes.publicListCalls > callsBefore)
        }

    @Test
    fun aCancelledSignInChangesNothing() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val drafts = FakePersonalRouteDraftRepository()
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = openedPreview(routes, drafts, auth)
            viewModel.copy()
            advanceUntilIdle()

            // Leaving sign-in signed out drops the waiting action (App does this through the composer).
            drafts.clearPending()
            auth.value = signedIn
            advanceUntilIdle()

            assertTrue(routes.copied.isEmpty())
            assertEquals("draft-1", drafts.draft.value.draftId)
        }

    // Report (US10: DD10, DD12)

    @Test
    fun aReportIsSentWithItsReasonAndCannotBeRepeated() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1"))
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())

            viewModel.startReport()
            assertTrue(preview(viewModel).reportSheet)
            viewModel.report(RouteReportReason.Promotional)
            advanceUntilIdle()

            assertEquals(listOf("p1" to RouteReportReason.Promotional), routes.reports)
            assertTrue(preview(viewModel).reported)
            assertFalse(preview(viewModel).reportSheet)
            assertEquals(PublicRouteMessage.REPORTED, preview(viewModel).message)
        }

    @Test
    fun anOpenReportAlreadyOnFileReadsAsReported() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { reportFailures += PersonalRouteFailure.ReportExists }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())

            viewModel.startReport()
            viewModel.report(RouteReportReason.Other)
            advanceUntilIdle()

            assertTrue(preview(viewModel).reported)
            assertFalse(preview(viewModel).reportSheet)
        }

    @Test
    fun aFailedReportRetriesTheSameCall() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { reportFailures += PersonalRouteFailure.Network }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())

            viewModel.startReport()
            viewModel.report(RouteReportReason.WrongInformation)
            advanceUntilIdle()
            assertEquals(PublicRouteMessage.REPORT_FAILED, preview(viewModel).message)
            assertFalse(preview(viewModel).reported)

            viewModel.retryMessage()
            advanceUntilIdle()
            assertEquals(listOf("p1" to RouteReportReason.WrongInformation), routes.reports)
            assertTrue(preview(viewModel).reported)
        }

    @Test
    fun aReportOnARouteThatLeftTheListSaysSoAndReloadsTheList() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { reportFailures += PersonalRouteFailure.NotListed }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())
            val callsBefore = routes.publicListCalls

            viewModel.startReport()
            viewModel.report(RouteReportReason.Other)
            advanceUntilIdle()

            assertEquals(PublicRouteNotice.NO_LONGER_LISTED, preview(viewModel).notice)
            assertNull(preview(viewModel).message, "there is nothing to retry")
            assertFalse(preview(viewModel).reportSheet)
            assertFalse(preview(viewModel).reportBusy)
            assertFalse(preview(viewModel).reported)
            assertTrue(routes.publicListCalls > callsBefore)
        }

    @Test
    fun aReportRefusedAsTheReadersOwnRouteNeedsNoRetry() =
        runTest(dispatcher) {
            val routes = listedRoute(row("p1")).apply { reportFailures += PersonalRouteFailure.ReportOwnRoute }
            val viewModel = openedPreview(routes, FakePersonalRouteDraftRepository())

            viewModel.startReport()
            viewModel.report(RouteReportReason.Other)
            advanceUntilIdle()

            assertNull(preview(viewModel).message)
            assertFalse(preview(viewModel).reportSheet)
            assertFalse(preview(viewModel).reportBusy)
            assertTrue(preview(viewModel).isOwn, "the server knows the reader wrote it: no 신고, 내 동선에서 열기")
            viewModel.retryMessage()
            advanceUntilIdle()
            assertTrue(routes.reports.isEmpty())
        }

    @Test
    fun aSignedOutReaderIsAskedToSignInBeforeReporting() =
        runTest(dispatcher) {
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = openedPreview(listedRoute(row("p1")), FakePersonalRouteDraftRepository(), auth)

            viewModel.startReport()

            assertTrue(preview(viewModel).signInRequested)
            assertFalse(preview(viewModel).reportSheet)
        }

    private fun TestScope.openedPreview(
        routes: FakePersonalRouteRepository,
        drafts: FakePersonalRouteDraftRepository,
        auth: MutableStateFlow<AuthState> = MutableStateFlow(signedIn),
    ): PublicRoutesViewModel {
        val viewModel = publicRoutes(routes, drafts, auth)
        observe(viewModel)
        viewModel.openPreview(routes.publicRoutes.first())
        advanceUntilIdle()
        return viewModel
    }

    private fun preview(viewModel: PublicRoutesViewModel): PublicRoutePreview =
        assertNotNull(viewModel.state.value.preview)

    private fun listedRoute(summary: PublicRouteSummary) =
        FakePersonalRouteRepository().apply {
            publicRoutes = listOf(summary)
            publicStops[summary.id] =
                PersonalRoute(
                    id = summary.id,
                    name = summary.name,
                    stops = catalogue.map { requireNotNull(it.toRouteStop()) },
                    isPublished = true,
                )
        }

    private fun TestScope.observe(viewModel: PublicRoutesViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun publicRoutes(
        routes: PersonalRouteRepository,
        drafts: FakePersonalRouteDraftRepository = FakePersonalRouteDraftRepository(),
        auth: MutableStateFlow<AuthState> = MutableStateFlow(signedIn),
        analytics: RouteAnalytics = RouteAnalytics.None,
    ): PublicRoutesViewModel {
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        return PublicRoutesViewModel(
            routeRepository = routes,
            draftRepository = drafts,
            exhibitionsState = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(catalogue)),
            authState = auth,
            clock = clock,
            backgroundDispatcher = dispatcher,
            analytics = analytics,
        )
    }

    /** Holds [listPublic] once [hold] is set, so a second read can be asked for while one is running. */
    private class HeldList(
        private val delegate: FakePersonalRouteRepository,
    ) : PersonalRouteRepository by delegate {
        var hold = false
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun listPublic(limit: Int): Result<List<PublicRouteSummary>> {
            if (hold) gate.await()
            return delegate.listPublic(limit)
        }
    }

    /** Answers a preview's stops read late or with a failure, which [FakePersonalRouteRepository] cannot. */
    private class PreviewReads(
        private val delegate: FakePersonalRouteRepository,
        private val heldRouteId: String? = null,
        private var failures: Int = 0,
    ) : PersonalRouteRepository by delegate {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun loadPublicStops(id: String): Result<PublicRouteStops?> {
            if (id == heldRouteId) gate.await()
            if (failures > 0) {
                failures -= 1
                return Result.failure(PersonalRouteException(PersonalRouteFailure.Network))
            }
            return delegate.loadPublicStops(id)
        }
    }

    private fun row(
        id: String,
        firstSharedDay: LocalDate = today,
    ) = PublicRouteSummary(
        id = id,
        name = "동선 $id",
        stopCount = 2,
        firstDistrictKo = "종로구",
        firstDistrictEn = "Jongno-gu",
        lastDistrictKo = "종로구",
        lastDistrictEn = "Jongno-gu",
        authorDisplayName = "작가",
        isEditor = false,
        copyCount30d = 3,
        firstSharedDay = firstSharedDay,
        approvedAt = Instant.parse("2026-10-07T00:00:00Z"),
    )

    private fun exhibition(
        id: String,
        latitude: Double,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 9, 1),
        closingDate = LocalDate(2026, 11, 30),
        isFeatured = false,
        latitude = latitude,
        longitude = 126.98,
        descriptionKo = "설명 $id",
        descriptionEn = "Description $id",
        addressKo = "주소 $id",
        addressEn = "Address $id",
        coverImageUrl = null,
        hours = "10am - 6pm",
        galleryId = "gallery-$id",
    )
}
