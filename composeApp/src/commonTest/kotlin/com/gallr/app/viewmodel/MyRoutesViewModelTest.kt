package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteListingState
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
            assertEquals(summary("route-9"), viewModel.state.value.confirmOpen)
            assertEquals("작성 중", drafts.draft.value.route.name)

            viewModel.confirmOpen()
            advanceUntilIdle()
            assertNull(viewModel.state.value.confirmOpen)
            assertEquals("route-9", drafts.draft.value.route.id)
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
            assertEquals("route-9", confirming?.id)

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

    private suspend fun routesHolding(id: String) =
        FakePersonalRouteRepository().apply { save(PersonalRoute(id = id, name = "저장된 동선 $id", stops = stops(3))) }

    private fun MyRoutesViewModel.savedIds(): List<String> {
        val loaded = assertIs<SavedRoutesState.Loaded>(state.value.saved)
        return loaded.routes.map { it.id }
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
        routes: FakePersonalRouteRepository,
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
