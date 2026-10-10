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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 089 US7 (DD12, DD14, DD17): listing a saved route from its 내 동선 row. */
@OptIn(ExperimentalCoroutinesApi::class)
class MyRoutesListingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-08T04:00:00Z")
    private val signedIn = AuthState.Authenticated(GallrUser("account-1", "하나", null))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun listingAPublishedRouteAsksForConsentFirst() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("a", published = true)) }
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a", published = true))

            val consent = viewModel.state.value.confirmListing
            assertEquals("a", consent?.route?.id)
            assertEquals(false, consent?.alsoPublishes)
            assertEquals("하나", consent?.authorName)
            assertEquals(emptyList(), routes.listingRequests, "nothing is sent before consent")

            viewModel.dismissDialogs()
            assertNull(viewModel.state.value.confirmListing)
            assertEquals(emptyList(), routes.listingRequests)
        }

    @Test
    fun aConfirmedRequestUpdatesTheRowFromTheServerAndConfirms() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("a", published = true)) }
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a", published = true))
            viewModel.confirmListing()
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.listingRequests)
            assertEquals(RouteListingState.Requested, viewModel.row("a").listingState)
            assertEquals(ListingMessage.Requested(isEditor = false), viewModel.state.value.listingMessage)
            assertEquals(emptySet(), viewModel.state.value.listingBusy)
            viewModel.dismissListingMessage()
            assertNull(viewModel.state.value.listingMessage)
        }

    @Test
    fun anEditorIsListedAtOnce() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    authorIsEditor = true
                    summaries = listOf(summary("a", published = true, editor = true))
                }
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a", published = true, editor = true))
            val consent = viewModel.state.value.confirmListing
            assertEquals(true, consent?.isEditor)
            viewModel.confirmListing()
            advanceUntilIdle()

            assertEquals(RouteListingState.Approved, viewModel.row("a").listingState)
            assertEquals(ListingMessage.Requested(isEditor = true), viewModel.state.value.listingMessage)
        }

    @Test
    fun anUnpublishedRouteIsPublishedThenRequested() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            routes.save(PersonalRoute(id = "a", name = "동선 a", stops = stops(2)))
            routes.summaries = listOf(summary("a"))
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a"))
            val consent = viewModel.state.value.confirmListing
            assertEquals(true, consent?.alsoPublishes)
            viewModel.confirmListing()
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.published)
            assertEquals(listOf("a"), routes.listingRequests)
            assertEquals(RouteListingState.Requested, viewModel.row("a").listingState)
            assertTrue(viewModel.row("a").isPublished)
        }

    @Test
    fun aFailedRequestLeavesTheRowAndOffersARetryOfTheSameCall() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    summaries = listOf(summary("a", published = true))
                    listingFailures += PersonalRouteFailure.Network
                }
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a", published = true))
            viewModel.confirmListing()
            advanceUntilIdle()

            assertEquals(RouteListingState.Unlisted, viewModel.row("a").listingState)
            assertIs<ListingMessage.Failed>(viewModel.state.value.listingMessage)

            viewModel.retryListing()
            advanceUntilIdle()

            assertEquals(listOf("a", "a"), routes.listingRequests)
            assertEquals(RouteListingState.Requested, viewModel.row("a").listingState)
            assertEquals(ListingMessage.Requested(isEditor = false), viewModel.state.value.listingMessage)
        }

    @Test
    fun withdrawingTakesTheRouteOffTheListWithoutAConfirm() =
        runTest(dispatcher) {
            val listed = summary("a", published = true).copy(listingState = RouteListingState.Approved)
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(listed) }
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.withdrawListing(listed)
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.listingWithdrawals)
            assertEquals(RouteListingState.Unlisted, viewModel.row("a").listingState)
            assertNull(viewModel.state.value.listingMessage)
        }

    @Test
    fun aRouteIsBusyWhileItsListingCallRuns() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { summaries = listOf(summary("a", published = true)) }
            val viewModel = myRoutes(routes)
            observe(viewModel)
            val busySeen = mutableListOf<Set<String>>()
            backgroundScope.launch { viewModel.state.collect { busySeen += it.listingBusy } }

            viewModel.requestListing(summary("a", published = true))
            viewModel.confirmListing()
            advanceUntilIdle()

            assertTrue(busySeen.any { "a" in it }, "the row is busy while the request runs")
            assertEquals(emptySet(), viewModel.state.value.listingBusy)
        }

    private fun MyRoutesViewModel.row(id: String): PersonalRouteSummary {
        val loaded = assertIs<SavedRoutesState.Loaded>(state.value.saved)
        return loaded.routes.single { it.id == id }
    }

    private fun TestScope.observe(viewModel: MyRoutesViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun myRoutes(routes: FakePersonalRouteRepository): MyRoutesViewModel {
        val drafts = FakePersonalRouteDraftRepository()
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        return MyRoutesViewModel(
            draftRepository = drafts,
            routeRepository = routes,
            authState = MutableStateFlow(signedIn),
            shareOrchestrator = RouteShareOrchestrator(drafts, routes, clock),
        )
    }

    private fun summary(
        id: String,
        published: Boolean = false,
        editor: Boolean = false,
    ) = PersonalRouteSummary(id, "동선 $id", 3, published, isRevoked = false, updatedAt = now, authorIsEditor = editor)

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
