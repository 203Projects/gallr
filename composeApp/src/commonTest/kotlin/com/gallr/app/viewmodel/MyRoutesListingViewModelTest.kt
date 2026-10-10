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
    fun aFirstPublishFromTheListIsCountedOnce() =
        runTest(dispatcher) {
            val analytics = RecordingRouteAnalytics()
            val routes = FakePersonalRouteRepository()
            routes.save(PersonalRoute(id = "a", name = "동선 a", stops = stops(2)))
            routes.summaries = listOf(summary("a"))
            val viewModel = myRoutes(routes, analytics)
            observe(viewModel)

            viewModel.requestListing(summary("a"))
            viewModel.confirmListing()
            advanceUntilIdle()
            assertEquals(listOf("published:2"), analytics.events)

            // Listing a route whose link is already public publishes nothing.
            viewModel.withdrawListing(viewModel.row("a"))
            advanceUntilIdle()
            viewModel.requestListing(viewModel.row("a"))
            viewModel.confirmListing()
            advanceUntilIdle()
            assertEquals(listOf("published:2"), analytics.events)
            assertEquals(listOf("a"), routes.published)
        }

    @Test
    fun aRequestThatFailsAfterPublishingKeepsTheRowPublishedAndRetriesOnlyTheRequest() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            routes.save(PersonalRoute(id = "a", name = "동선 a", stops = stops(2)))
            routes.summaries = listOf(summary("a"))
            routes.listingFailures += PersonalRouteFailure.Network
            val viewModel = myRoutes(routes)
            observe(viewModel)

            viewModel.requestListing(summary("a"))
            viewModel.confirmListing()
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.published)
            assertTrue(viewModel.row("a").isPublished, "the link is public even though the request failed")
            assertIs<ListingMessage.Failed>(viewModel.state.value.listingMessage)

            viewModel.retryListing()
            advanceUntilIdle()

            assertEquals(listOf("a"), routes.published, "the retry does not publish again")
            assertEquals(listOf("a", "a"), routes.listingRequests)
            assertEquals(RouteListingState.Requested, viewModel.row("a").listingState)
        }

    @Test
    fun aRefusedListingCallReloadsTheListAndOffersNoRetry() =
        runTest(dispatcher) {
            val refusals =
                mapOf(
                    PersonalRouteFailure.ListingInvalidTransition to ListingRefusal.STATE_CHANGED,
                    PersonalRouteFailure.ListingRequiresPublished to ListingRefusal.NOT_PUBLISHED,
                    PersonalRouteFailure.Revoked to ListingRefusal.REVOKED,
                )
            for ((failure, refusal) in refusals) {
                val routes =
                    FakePersonalRouteRepository().apply {
                        summaries = listOf(summary("a", published = true))
                        listingFailures += failure
                    }
                val viewModel = myRoutes(routes)
                observe(viewModel)

                viewModel.requestListing(summary("a", published = true))
                // Staff removed the route meanwhile; the reload shows that instead of the stale row.
                routes.summaries = listOf(summary("a", published = true).copy(listingState = RouteListingState.Removed))
                viewModel.confirmListing()
                advanceUntilIdle()

                assertEquals(ListingMessage.Refused(refusal), viewModel.state.value.listingMessage, "$failure")
                assertEquals(RouteListingState.Removed, viewModel.row("a").listingState, "$failure")
                assertEquals(emptySet(), viewModel.state.value.listingBusy)
                viewModel.retryListing()
                advanceUntilIdle()
                assertEquals(listOf("a"), routes.listingRequests, "$failure was retried")
            }
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

    private fun myRoutes(
        routes: FakePersonalRouteRepository,
        analytics: RouteAnalytics = RouteAnalytics.None,
    ): MyRoutesViewModel {
        val drafts = FakePersonalRouteDraftRepository()
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        return MyRoutesViewModel(
            draftRepository = drafts,
            routeRepository = routes,
            authState = MutableStateFlow(signedIn),
            shareOrchestrator = RouteShareOrchestrator(drafts, routes, clock, analytics),
            analytics = analytics,
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
