package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteListingState
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

/** Spec 089 US4 (DR-D10): the 내 동선 list of the draft and saved routes. */
@OptIn(ExperimentalCoroutinesApi::class)
class MyRoutesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-08T04:00:00Z")
    private val signedIn = AuthState.Authenticated(GallrUser("account-1", "hanshin", null))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun signedInListsSavedRoutesAfterTheUnsavedDraft() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중인 동선")
            val routes = FakePersonalRouteRepository()
            routes.summaries = listOf(summary("a", published = true), summary("b"))
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            val state = viewModel.state.value
            assertEquals(DraftRouteRow("작성 중인 동선", 2), state.draftRow)
            val loaded = assertIs<SavedRoutesState.Loaded>(state.saved)
            assertEquals(listOf("a", "b"), loaded.routes.map { it.id })
        }

    @Test
    fun anEmptyOrSavedDraftHasNoDraftRow() =
        runTest(dispatcher) {
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), FakePersonalRouteRepository())
            observe(viewModel)

            assertNull(viewModel.state.value.draftRow)
            assertEquals(SavedRoutesState.Loaded(emptyList()), viewModel.state.value.saved)
        }

    @Test
    fun signedOutShowsOnlyTheDraft() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("a")) }
            val signedOut = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(stops(1)), routes, signedOut)
            observe(viewModel)

            assertEquals(SavedRoutesState.SignedOut, viewModel.state.value.saved)
            assertNotNull(viewModel.state.value.draftRow)
        }

    @Test
    fun theRouteCountCountsEachRouteOnce() =
        runTest(dispatcher) {
            val newDraft = FakePersonalRouteDraftRepository(stops(2))
            val listing = FakePersonalRouteRepository().apply { summaries = listOf(summary("a"), summary("b")) }
            val withNewDraft = myRoutes(newDraft, listing)
            observe(withNewDraft)
            assertEquals(3, withNewDraft.state.value.routeCount)

            // Unsaved edits to a saved route list as a draft row, but it is still one route.
            val editedListing = FakePersonalRouteRepository()
            editedListing.summaries = listOf(summary("route-1"), summary("b"))
            val withEdits = myRoutes(FakePersonalRouteDraftRepository(stops(2)), editedListing)
            observe(withEdits)
            assertEquals(2, withEdits.state.value.routeCount)

            val signedOut = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val signedOutDraft = myRoutes(FakePersonalRouteDraftRepository(stops(1)), listing, signedOut)
            observe(signedOutDraft)
            assertEquals(1, signedOutDraft.state.value.routeCount)

            val nothing = myRoutes(FakePersonalRouteDraftRepository(), FakePersonalRouteRepository())
            observe(nothing)
            assertEquals(0, nothing.state.value.routeCount)
        }

    @Test
    fun aFailedListingCanBeRetried() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { listFailure = PersonalRouteFailure.Network }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)
            assertEquals(SavedRoutesState.Error, viewModel.state.value.saved)

            routes.listFailure = null
            routes.summaries = listOf(summary("a"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf("a"), viewModel.savedIds())
        }

    @Test
    fun openingReplacesASavedDraftAtOnceAndOpensTheComposer() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository()
            val routes = routesHolding("route-9")
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.open(summary("route-9"))
            advanceUntilIdle()

            assertEquals("route-9", drafts.draft.value.route.id)
            assertEquals("account-1", drafts.draft.value.ownerAccountId)
            assertTrue(viewModel.state.value.openComposer)
            viewModel.onComposerOpened()
            assertFalse(viewModel.state.value.openComposer)
        }

    @Test
    fun openingOverUnsavedEditsAsksFirst() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val routes = routesHolding("route-9")
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.open(summary("route-9"))
            advanceUntilIdle()
            assertEquals(PendingOpen(summary("route-9"), OpenOrigin.LIST), viewModel.state.value.confirmOpen)
            assertEquals("작성 중", drafts.draft.value.route.name)

            viewModel.confirmOpen()
            advanceUntilIdle()
            assertNull(viewModel.state.value.confirmOpen)
            assertEquals("route-9", drafts.draft.value.route.id)
        }

    // Generated by /ship test coverage audit (spec 089).
    // Value: protects=a saved route that cannot be read (deleted elsewhere, offline) reports OPEN_FAILED and the
    // draft the author agreed to replace is still there; fails_when=the draft is cleared or replaced before the read
    // succeeds, or the failure is silent; why_new=every open test loads a route the fake holds; seam=none
    @Test
    fun aRouteThatCannotBeOpenedSaysSoAndKeepsTheDraft() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val routes = FakePersonalRouteRepository()
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.open(summary("gone"))
            advanceUntilIdle()
            viewModel.confirmOpen()
            advanceUntilIdle()

            assertEquals(listOf("gone"), routes.loaded)
            assertEquals(MyRoutesError.OPEN_FAILED, viewModel.state.value.error)
            assertNull(viewModel.state.value.previewOpenFailed)
            assertFalse(viewModel.state.value.openComposer)
            assertEquals("작성 중", drafts.draft.value.route.name)
            assertEquals(2, drafts.stopCount())
            viewModel.dismissError()
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun aFailedOpenFromThePreviewIsReportedToThePreviewNotTheList() =
        runTest(dispatcher) {
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), FakePersonalRouteRepository())
            observe(viewModel)

            viewModel.openOwn("gone")
            advanceUntilIdle()

            assertEquals("gone", viewModel.state.value.previewOpenFailed)
            assertNull(viewModel.state.value.error)
            assertFalse(viewModel.state.value.openComposer)
            viewModel.dismissPreviewOpenFailure()
            assertNull(viewModel.state.value.previewOpenFailed)
        }

    // Value: protects=the preview reports the failure shown when it is left, even when nothing failed, and that
    // changes nothing; fails_when=dismissPreviewOpenFailure touches other state or emits a new state for a flag that
    // is already clear; why_new=every dismiss in this file follows a real failure; seam=none
    @Test
    fun dismissingAPreviewOpenFailureThatNeverHappenedChangesNothing() =
        runTest(dispatcher) {
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(stops(2)), FakePersonalRouteRepository())
            observe(viewModel)
            val emissions = mutableListOf<MyRoutesUiState>()
            backgroundScope.launch { viewModel.state.collect { emissions += it } }
            val before = viewModel.state.value
            assertNull(before.previewOpenFailed)

            viewModel.dismissPreviewOpenFailure()
            advanceUntilIdle()

            assertEquals(before, viewModel.state.value)
            assertEquals(1, emissions.size, "a cleared flag is not emitted again")
        }

    @Test
    fun aFailedOpenFromThePreviewAfterTheReplaceConfirmStaysWithThePreview() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val viewModel = myRoutes(drafts, FakePersonalRouteRepository())
            observe(viewModel)

            viewModel.openOwn("gone")
            advanceUntilIdle()
            viewModel.confirmOpen()
            advanceUntilIdle()

            assertEquals("gone", viewModel.state.value.previewOpenFailed)
            assertNull(viewModel.state.value.error)
            assertEquals("작성 중", drafts.draft.value.route.name)

            // The next open from a 내 동선 row reports to the list again.
            viewModel.dismissPreviewOpenFailure()
            viewModel.open(summary("gone"))
            advanceUntilIdle()
            viewModel.confirmOpen()
            advanceUntilIdle()
            assertEquals(MyRoutesError.OPEN_FAILED, viewModel.state.value.error)
            assertNull(viewModel.state.value.previewOpenFailed)
        }

    // Value: protects=a preview open that fails after the preview was left reports nowhere: not to the preview, where
    // it would greet that route's next preview, and not to the list; fails_when=the failure is reported wherever the
    // last open was asked for, or the ViewModel ignores that the preview reported itself left while the read ran;
    // why_new=every failed open in this file fails before the author moves on; seam=HeldLoad holds loadMine
    @Test
    fun aPreviewOpenThatFailsAfterThePreviewWasLeftReportsNowhere() =
        runTest(dispatcher) {
            val held = HeldLoad(FakePersonalRouteRepository())
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), held)
            observe(viewModel)

            viewModel.openOwn("gone")
            // Leaving the preview reports the failure shown, whether or not one was showing (PublicRoutePreviewRoute).
            viewModel.dismissPreviewOpenFailure()
            held.release()
            advanceUntilIdle()

            assertNull(viewModel.state.value.previewOpenFailed)
            assertNull(viewModel.state.value.error)
            assertFalse(viewModel.state.value.openComposer)
        }

    // Value: protects=two overlapping opens each report their failure to the surface that asked: the list open to
    // the list, the later preview open to the preview; fails_when=the origin is one field written by the latest
    // open, so the earlier list open's failure lands on the preview; why_new=no test overlaps two opens;
    // seam=HeldLoad holds loadMine
    @Test
    fun overlappingOpensReportEachFailureToTheSurfaceThatAsked() =
        runTest(dispatcher) {
            val held = HeldLoad(FakePersonalRouteRepository())
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), held)
            observe(viewModel)

            viewModel.open(summary("gone-from-list"))
            viewModel.openOwn("gone-from-preview")
            held.release()
            advanceUntilIdle()

            assertEquals(MyRoutesError.OPEN_FAILED, viewModel.state.value.error)
            assertEquals("gone-from-preview", viewModel.state.value.previewOpenFailed)
        }

    // Value: protects=a list open started while a preview open is still running reports to the list, and the preview
    // open's late failure is dropped because the author has moved on; fails_when=the preview open's failure is
    // reported to the surface that asked last, which here is the list; why_new=no test overlaps two opens;
    // seam=HeldLoad holds loadMine
    @Test
    fun aListOpenAfterAPreviewOpenReportsToTheListAndDropsThePreviewFailure() =
        runTest(dispatcher) {
            val held = HeldLoad(routesHolding("route-9"))
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = myRoutes(drafts, held)
            observe(viewModel)

            viewModel.openOwn("gone")
            viewModel.open(summary("route-9"))
            held.release()
            advanceUntilIdle()

            assertNull(viewModel.state.value.error, "the preview open's failure is not the list's")
            assertNull(viewModel.state.value.previewOpenFailed)
            assertEquals("route-9", drafts.draft.value.route.id)
            assertTrue(viewModel.state.value.openComposer)
        }

    @Test
    fun aRouteSavedInTheComposerAppearsInTheListWithoutSigningInAgain() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "새 동선")
            val routes = FakePersonalRouteRepository()
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)
            assertEquals(emptyList(), viewModel.savedIds())

            // The composer saves the draft: the server now lists it and the draft is acknowledged.
            routes.summaries = listOf(summary("route-1"))
            drafts.acknowledgeSave(
                draftId = "draft-1",
                sentRevision = 0,
                saved = PersonalRoute(id = "route-1", name = "새 동선", stops = stops(2)),
                ownerAccountId = "account-1",
            )
            advanceUntilIdle()

            assertEquals(listOf("route-1"), viewModel.savedIds())
            assertEquals(1, viewModel.state.value.routeCount)
        }

    @Test
    fun showingTheSectionAgainPicksUpAStaffDecisionWithoutAFlashOfPlaceholders() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("route-9")) }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)
            val seen = mutableListOf<SavedRoutesState>()
            backgroundScope.launch { viewModel.state.collect { seen += it.saved } }

            routes.summaries = listOf(summary("route-9").copy(name = "새 이름", listingState = RouteListingState.Declined))
            viewModel.sectionShown()
            advanceUntilIdle()

            val loaded = assertIs<SavedRoutesState.Loaded>(viewModel.state.value.saved)
            assertEquals(RouteListingState.Declined, loaded.routes.single().listingState)
            assertTrue(seen.none { it == SavedRoutesState.Loading })
        }

    @Test
    fun showingTheSectionAgainWhileARereadIsRunningReadsOnce() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("route-9")) }
            val held = HeldListing(routes)
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), held)
            observe(viewModel)
            val callsBefore = routes.listMineCalls

            held.hold = true
            viewModel.sectionShown()
            viewModel.sectionShown()
            held.release()
            advanceUntilIdle()

            assertEquals(callsBefore + 1, routes.listMineCalls)
            assertIs<SavedRoutesState.Loaded>(viewModel.state.value.saved)
        }

    // Value: protects=a refusal that arrives while a quiet read is running is followed by a read that starts after
    // it, so a row the server says is gone leaves the list now rather than at the next showing; fails_when=the
    // refusal's re-read is dropped because a read is already in flight, and that read delivers the stale row;
    // why_new=the held-read test above repeats sectionShown, which the running read answers; seam=HeldListing
    // answers at once and delivers after release, like a slow response that predates the change
    @Test
    fun aRefusalDuringAHeldQuietReadIsFollowedByOneMoreRead() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    summaries = listOf(summary("a", published = true))
                    listingFailures += PersonalRouteFailure.NotFound
                }
            val held = HeldListing(routes)
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), held)
            observe(viewModel)
            val callsBefore = routes.listMineCalls

            held.hold = true
            viewModel.sectionShown()
            // The route is deleted from another device after that read was answered; the listing call learns first.
            routes.summaries = emptyList()
            viewModel.requestListing(summary("a", published = true))
            viewModel.confirmListing()
            held.release()
            advanceUntilIdle()

            assertEquals(ListingMessage.Refused(ListingRefusal.GONE), viewModel.state.value.listingMessage)
            assertEquals(callsBefore + 2, routes.listMineCalls, "one more read follows the held one")
            assertEquals(SavedRoutesState.Loaded(emptyList()), viewModel.state.value.saved)
        }

    // Value: protects=showing the section after a failed load reads the list again, so the author sees rows without
    // tapping 다시 시도; fails_when=sectionShown treats Error like Loading and reads nothing; why_new=the existing
    // sectionShown tests start from Loaded or SignedOut; seam=none
    @Test
    fun showingTheSectionAfterAFailedLoadReadsTheListAgain() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { listFailure = PersonalRouteFailure.Network }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)
            assertEquals(SavedRoutesState.Error, viewModel.state.value.saved)
            val callsBefore = routes.listMineCalls

            routes.listFailure = null
            routes.summaries = listOf(summary("a"), summary("b"))
            viewModel.sectionShown()
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), viewModel.savedIds())
            assertEquals(callsBefore + 1, routes.listMineCalls)
        }

    // Value: protects=showing the section while the first load is still running sends no second read; fails_when=
    // sectionShown refreshes or reads again during Loading, doubling the first read; why_new=the held-read test
    // holds a quiet reread, not the first load; seam=HeldListing holds listMine from construction
    @Test
    fun showingTheSectionWhileTheFirstLoadIsStillRunningReadsOnce() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("route-9")) }
            val held = HeldListing(routes).apply { hold = true }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), held)
            observe(viewModel)
            assertEquals(SavedRoutesState.Loading, viewModel.state.value.saved)

            viewModel.sectionShown()
            held.release()
            advanceUntilIdle()

            assertEquals(1, routes.listMineCalls)
            assertEquals(listOf("route-9"), viewModel.savedIds())
        }

    @Test
    fun showingTheSectionWhileSignedOutReadsNothing() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes, MutableStateFlow(AuthState.Anonymous))
            observe(viewModel)

            viewModel.sectionShown()
            advanceUntilIdle()

            assertEquals(SavedRoutesState.SignedOut, viewModel.state.value.saved)
        }

    @Test
    fun theAuthorsOwnListedRouteOpensByIdFromThePreview() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val routes = routesHolding("route-9")
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.openOwn("route-9")
            advanceUntilIdle()
            val confirming = viewModel.state.value.confirmOpen
            assertEquals("route-9", confirming?.route?.id)
            assertEquals(OpenOrigin.PREVIEW, confirming?.origin)

            viewModel.confirmOpen()
            advanceUntilIdle()
            assertEquals("route-9", drafts.draft.value.route.id)
            assertTrue(viewModel.state.value.openComposer)
        }

    @Test
    fun openingTheRouteAlreadyInTheDraftJustOpensIt() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val routes = routesHolding("route-1")
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.open(summary("route-1"))
            advanceUntilIdle()

            assertTrue(routes.loaded.isEmpty())
            assertTrue(viewModel.state.value.openComposer)
        }

    @Test
    fun aNewRouteStartsAnEmptyDraftAtOnceWhenNothingIsUnsaved() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository()
            val viewModel = myRoutes(drafts, FakePersonalRouteRepository())
            observe(viewModel)

            viewModel.startNewRoute()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.confirmNewRoute)
            assertTrue(viewModel.state.value.openComposer)
            assertEquals(0, drafts.stopCount())
        }

    @Test
    fun aNewRouteOverUnsavedEditsAsksAndCanKeepEditing() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "작성 중")
            val viewModel = myRoutes(drafts, FakePersonalRouteRepository())
            observe(viewModel)

            viewModel.startNewRoute()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.confirmNewRoute)

            viewModel.keepEditing()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.confirmNewRoute)
            assertTrue(viewModel.state.value.openComposer)
            assertEquals(2, drafts.draft.value.route.stops.size)

            viewModel.onComposerOpened()
            viewModel.startNewRoute()
            viewModel.confirmNewRoute()
            advanceUntilIdle()
            assertEquals(0, drafts.stopCount())
            assertTrue(viewModel.state.value.openComposer)
        }

    @Test
    fun sharingAnUnpublishedRoutePublishesItFirst() =
        runTest(dispatcher) {
            val routes = routesHolding("route-9")
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)

            viewModel.share(summary("route-9"))
            advanceUntilIdle()

            assertEquals(listOf("route-9"), routes.published)
            assertNotNull(viewModel.state.value.sharePayload)
            viewModel.onShareSheetShown()
            assertNull(viewModel.state.value.sharePayload)
        }

    @Test
    fun sharingAnUnpublishedRouteMarksItsRowAsPublished() =
        runTest(dispatcher) {
            val routes = routesHolding("route-9").apply { summaries = listOf(summary("route-9")) }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)
            assertFalse(viewModel.row("route-9").isPublished)

            viewModel.share(summary("route-9"))
            advanceUntilIdle()

            assertEquals(listOf("route-9"), routes.published)
            assertTrue(viewModel.row("route-9").isPublished, "the row offers 공개 목록에 올리기, not 공개하고 목록에 올리기")
        }

    @Test
    fun sharingTheSavedDraftRouteMarksItsRowAsPublished() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            drafts.acknowledgeSave(
                draftId = "draft-1",
                sentRevision = 0,
                saved = PersonalRoute(id = "route-1", name = "동선", stops = stops(2)),
                ownerAccountId = "account-1",
            )
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("route-1")) }
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.share(summary("route-1"))
            advanceUntilIdle()

            assertEquals(listOf("route-1"), routes.published)
            assertTrue(viewModel.row("route-1").isPublished)
        }

    @Test
    fun sharingTheOpenDraftsRouteSavesItsEditsFirst() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "편집 중")
            val routes = FakePersonalRouteRepository()
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.share(summary("route-1"))
            advanceUntilIdle()

            assertEquals("편집 중", routes.saved.single().name)
            assertNotNull(viewModel.state.value.sharePayload)
        }

    // Generated by /ship test coverage audit (spec 089).
    // Value: protects=a share from 내 동선 that fails shows SHARE_FAILED and opens no sheet, and an expired or
    // signed-out session asks for sign-in instead of showing an error; fails_when=share() shows an error for a
    // sign-in outcome, opens the sheet without a link, or says nothing; why_new=both share tests in this file
    // succeed; seam=none
    @Test
    fun aFailedShareSaysSoAndASignedOutShareAsksForSignIn() =
        runTest(dispatcher) {
            val routes = routesHolding("route-9").apply { publishFailure = PersonalRouteFailure.Network }
            val auth = MutableStateFlow<AuthState>(signedIn)
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes, auth)
            observe(viewModel)

            viewModel.share(summary("route-9"))
            advanceUntilIdle()
            assertEquals(MyRoutesError.SHARE_FAILED, viewModel.state.value.error)
            assertNull(viewModel.state.value.sharePayload)
            assertFalse(viewModel.state.value.signInRequested)
            viewModel.dismissError()

            // The session expired on the server while the app still shows the author as signed in.
            routes.publishFailure = PersonalRouteFailure.Unauthenticated
            viewModel.share(summary("route-9"))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.signInRequested)
            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.sharePayload)
            viewModel.onSignInRequestHandled()
            assertFalse(viewModel.state.value.signInRequested)

            auth.value = AuthState.Anonymous
            advanceUntilIdle()
            val requestsBefore = routes.loaded.size
            viewModel.share(summary("route-9"))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.signInRequested)
            assertNull(viewModel.state.value.error)
            assertEquals(requestsBefore, routes.loaded.size, "nothing is read for a signed-out author")
        }

    @Test
    fun deletingAPublicRouteWarnsThatTheLinkStops() =
        runTest(dispatcher) {
            val public = summary("a", published = true)
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(public, summary("b")) }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)

            viewModel.delete(public)
            assertEquals(public, viewModel.state.value.confirmDelete)
            assertTrue(routes.deleted.isEmpty())

            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.deleted)
            assertEquals(listOf("b"), viewModel.savedIds())
            assertNull(viewModel.state.value.confirmDelete)
        }

    @Test
    fun deletingTheDraftsRouteKeepsItsStopsAsANewDraft() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("route-1")) }
            val viewModel = myRoutes(drafts, routes)
            observe(viewModel)

            viewModel.delete(summary("route-1"))
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(1, drafts.detachCount)
            assertEquals(2, drafts.draft.value.route.stops.size)
        }

    @Test
    fun aFailedDeleteIsReported() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            routes.summaries = listOf(summary("a"))
            routes.deleteFailure = PersonalRouteFailure.Network
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)

            viewModel.delete(summary("a"))
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(MyRoutesError.DELETE_FAILED, viewModel.state.value.error)
            viewModel.dismissError()
            assertNull(viewModel.state.value.error)
        }

    // Value: protects=deleting a route another device already deleted, or one that is no longer this account's,
    // drops its row quietly and detaches a draft still pointing at it, with no error and nothing to retry;
    // fails_when=NotFound or NotOwner read as DELETE_FAILED, offering 다시 시도 over a row that stays; why_new=the
    // failed-delete test covers a network failure only; seam=none
    @Test
    fun deletingARouteThatIsGoneDropsItsRowWithoutAnError() =
        runTest(dispatcher) {
            for (failure in listOf(PersonalRouteFailure.NotFound, PersonalRouteFailure.NotOwner)) {
                val drafts = FakePersonalRouteDraftRepository(stops(2), name = "동선")
                val routes =
                    FakePersonalRouteRepository().apply {
                        summaries = listOf(summary("route-1"), summary("b"))
                        deleteFailure = failure
                    }
                val viewModel = myRoutes(drafts, routes)
                observe(viewModel)
                val seen = mutableListOf<SavedRoutesState>()
                backgroundScope.launch { viewModel.state.collect { seen += it.saved } }

                viewModel.delete(summary("route-1"))
                // The server no longer lists it.
                routes.summaries = listOf(summary("b"))
                viewModel.confirmDelete()
                advanceUntilIdle()

                assertNull(viewModel.state.value.error, "$failure")
                assertEquals(listOf("b"), viewModel.savedIds(), "$failure")
                assertTrue(seen.none { it == SavedRoutesState.Loading }, "$failure flashed placeholders")
                assertEquals(1, drafts.detachCount, "$failure left the draft pointing at the gone route")
            }
        }

    // Value: protects=a delete the server refuses because staff took the route down is explained with nothing to
    // retry, and the row is read again so it shows the revocation and stops offering 삭제; fails_when=Revoked reads
    // as DELETE_FAILED with 다시 시도, or the stale row stays; why_new=no delete test covers a refusal; seam=none
    @Test
    fun deletingARevokedRouteIsExplainedWithoutARetry() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    summaries = listOf(summary("a", published = true))
                    deleteFailure = PersonalRouteFailure.Revoked
                }
            val viewModel = myRoutes(FakePersonalRouteDraftRepository(), routes)
            observe(viewModel)
            val callsBefore = routes.listMineCalls

            viewModel.delete(summary("a", published = true))
            // Staff revoked the route after its row was read.
            routes.summaries = listOf(summary("a", published = true).copy(isRevoked = true))
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(MyRoutesError.DELETE_REFUSED_REVOKED, viewModel.state.value.error)
            assertEquals(callsBefore + 1, routes.listMineCalls, "the stale row is read again")
            assertTrue(viewModel.row("a").isRevoked)
            assertEquals(emptyList(), routes.deleted)
        }

    private suspend fun routesHolding(id: String) =
        FakePersonalRouteRepository().apply { save(PersonalRoute(id = id, name = "저장된 동선 $id", stops = stops(3))) }

    private fun MyRoutesViewModel.savedIds(): List<String> {
        val loaded = assertIs<SavedRoutesState.Loaded>(state.value.saved)
        return loaded.routes.map { it.id }
    }

    private fun MyRoutesViewModel.row(id: String): PersonalRouteSummary {
        val loaded = assertIs<SavedRoutesState.Loaded>(state.value.saved)
        return loaded.routes.single { it.id == id }
    }

    /**
     * Holds [listMine] once [hold] is set: the rows are read at once, as the server answers them, and delivered after
     * [release], like a slow response. A second read can then be asked for while one is running.
     */
    private class HeldListing(
        private val delegate: FakePersonalRouteRepository,
    ) : PersonalRouteRepository by delegate {
        var hold = false
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun listMine(): Result<List<PersonalRouteSummary>> {
            val rows = delegate.listMine()
            if (hold) gate.await()
            return rows
        }
    }

    /** Holds every [loadMine] until [release], so an open can be left or followed by another while it runs. */
    private class HeldLoad(
        private val delegate: FakePersonalRouteRepository,
    ) : PersonalRouteRepository by delegate {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun loadMine(id: String): Result<PersonalRoute> {
            gate.await()
            return delegate.loadMine(id)
        }
    }

    private fun FakePersonalRouteDraftRepository.stopCount(): Int {
        val route = draft.value.route
        return route.stops.size
    }

    private fun TestScope.observe(viewModel: MyRoutesViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun myRoutes(
        drafts: FakePersonalRouteDraftRepository,
        routes: PersonalRouteRepository,
        auth: MutableStateFlow<AuthState> = MutableStateFlow(signedIn),
    ): MyRoutesViewModel {
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        return MyRoutesViewModel(
            draftRepository = drafts,
            routeRepository = routes,
            authState = auth,
            shareOrchestrator = RouteShareOrchestrator(drafts, routes, clock),
        )
    }

    private fun summary(
        id: String,
        published: Boolean = false,
    ) = PersonalRouteSummary(id, "동선 $id", 3, published, isRevoked = false, updatedAt = now)

    private fun stops(count: Int) =
        (0 until count).map { index ->
            PersonalRouteStop(
                exhibitionId = "e$index",
                nameKo = "전시 $index",
                nameEn = "Show $index",
                venueNameKo = "공간 $index",
                venueNameEn = "Venue $index",
                point = GeoPoint(37.57 + index / 1_000.0, 126.98),
                regionKo = "종로구",
                regionEn = "Jongno-gu",
                cityKo = "서울",
            )
        }
}
