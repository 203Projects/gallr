package com.gallr.app.viewmodel

import com.gallr.shared.route.BrowsedPublicRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PublicRouteBrowseQuery
import com.gallr.shared.route.PublicRouteDistrict
import com.gallr.shared.route.PublicRouteSort
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 092: the 동선 둘러보기 screen's pages, sort, district filter and states (data-model.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicRouteBrowseViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** 00:30 on 2026-10-15 in Seoul, still the 14th in UTC, so the label's date must come from the Seoul zone. */
    private val now = Instant.parse("2026-10-14T15:30:00Z")
    private val popular = PublicRouteBrowseQuery()
    private val newest = PublicRouteBrowseQuery(sort = PublicRouteSort.NEWEST)
    private val seongsu = PublicRouteBrowseQuery(districtKo = "성수동")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // US1: browse every listed route

    @Test
    fun openingLoadsThePopularFirstPageForEveryDistrict() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(3) }
            val viewModel = browse(routes)
            observe(viewModel)

            viewModel.open()
            advanceUntilIdle()

            assertEquals(listOf(Triple(popular, 0, 20)), routes.browseCalls)
            val list = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("r1", "r2", "r3"), list.routes.map { it.summary.id })
            assertFalse(list.hasMore)
            assertEquals(LocalDate(2026, 10, 15), viewModel.state.value.today, "today is the Seoul date")
        }

    @Test
    fun nothingLoadsBeforeTheScreenIsOpened() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository()
            val viewModel = browse(routes)
            observe(viewModel)

            assertTrue(routes.browseCalls.isEmpty())
            assertEquals(BrowseListState.Loading, viewModel.state.value.list)
        }

    @Test
    fun morePagesLoadTwentyAtATimeUntilTheLast() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(45) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(0, 20, 40), routes.browseCalls.map { it.second })
            val list = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertEquals((1..45).map { "r$it" }, list.routes.map { it.summary.id })
            assertFalse(list.hasMore, "after the last page nothing more is asked for")
        }

    @Test
    fun aFastScrollAsksForTheNextPageOnce() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(45) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            routes.browseGate = gate

            viewModel.loadMore()
            assertTrue(assertIs<BrowseListState.Loaded>(viewModel.state.value.list).appending)
            viewModel.loadMore()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(0, 20), routes.browseCalls.map { it.second })
            assertFalse(assertIs<BrowseListState.Loaded>(viewModel.state.value.list).appending)
        }

    @Test
    fun aRouteThatMovedIntoALaterPageIsShownOnce() =
        runTest(dispatcher) {
            val first = routes(20)
            // Between the reads route r20 gained copies elsewhere and the server listed it again on the next page.
            val second = listOf(route("r20"), route("r21"))
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = first + second }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            val ids = assertIs<BrowseListState.Loaded>(viewModel.state.value.list).routes.map { it.summary.id }
            assertEquals((1..21).map { "r$it" }, ids)
        }

    @Test
    fun theScrollPositionIsKeptForTheReturnFromAPreview() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(30) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.saveScrollAnchor(index = 12, offset = 30)

            assertEquals(ScrollAnchor(index = 12, offset = 30), viewModel.state.value.scrollAnchor)
            assertEquals(1, routes.browseCalls.size, "keeping the position reads nothing")
        }

    @Test
    fun openingAgainStartsFromTheTopWithTheSameQuery() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    browseResults[newest] = routes(30)
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            viewModel.selectSort(PublicRouteSort.NEWEST)
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.saveScrollAnchor(index = 25, offset = 4)

            viewModel.open()
            advanceUntilIdle()

            assertEquals(Triple(newest, 0, 20), routes.browseCalls.last())
            assertEquals(newest, viewModel.state.value.query)
            assertEquals(ScrollAnchor(), viewModel.state.value.scrollAnchor)
            assertEquals(20, assertIs<BrowseListState.Loaded>(viewModel.state.value.list).routes.size)
        }

    // US2: sort

    @Test
    fun choosingNewestReloadsFromTheTop() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    browseResults[popular] = routes(30)
                    browseResults[newest] = listOf(route("n1"), route("n2"))
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.saveScrollAnchor(index = 22, offset = 8)

            viewModel.selectSort(PublicRouteSort.NEWEST)
            advanceUntilIdle()

            assertEquals(Triple(newest, 0, 20), routes.browseCalls.last())
            assertEquals(newest, viewModel.state.value.query)
            val list = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("n1", "n2"), list.routes.map { it.summary.id })
            assertEquals(ScrollAnchor(), viewModel.state.value.scrollAnchor)
        }

    @Test
    fun choosingTheCurrentSortDoesNothing() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(3) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.selectSort(PublicRouteSort.POPULAR)
            advanceUntilIdle()

            assertEquals(1, routes.browseCalls.size)
        }

    @Test
    fun aLatePageOfThePreviousSortIsIgnored() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    browseResults[popular] = routes(3)
                    browseResults[newest] = listOf(route("n1"))
                }
            val viewModel = browse(routes)
            observe(viewModel)
            val gate = CompletableDeferred<Unit>()
            routes.browseGate = gate
            viewModel.open()

            viewModel.selectSort(PublicRouteSort.NEWEST)
            routes.browseGate = null
            gate.complete(Unit)
            advanceUntilIdle()

            val list = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("n1"), list.routes.map { it.summary.id })
        }

    // US3: district filter

    @Test
    fun theDistrictsLoadOnceForEachOpening() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    districts = listOf(district("한남동", 3), district("성수동", 2))
                }
            val viewModel = browse(routes)
            observe(viewModel)

            viewModel.open()
            advanceUntilIdle()
            viewModel.selectSort(PublicRouteSort.NEWEST)
            advanceUntilIdle()

            assertEquals(1, routes.districtCalls)
            val state = viewModel.state.value
            assertEquals(listOf("한남동", "성수동"), state.districts.map { it.nameKo })
        }

    @Test
    fun choosingADistrictReloadsOnlyItsRoutes() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    districts = listOf(district("성수동", 2))
                    browseResults[popular] = routes(30)
                    browseResults[seongsu] = listOf(route("s1"), route("s2"))
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.selectDistrict("성수동")
            advanceUntilIdle()

            assertEquals(Triple(seongsu, 0, 20), routes.browseCalls.last())
            val list = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertEquals(listOf("s1", "s2"), list.routes.map { it.summary.id })
        }

    @Test
    fun choosingTheSelectedDistrictAgainOrAllClearsTheFilter() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { districts = listOf(district("성수동", 2)) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()

            viewModel.selectDistrict("성수동")
            advanceUntilIdle()
            viewModel.selectDistrict("성수동")
            advanceUntilIdle()
            assertNull(viewModel.state.value.query.districtKo, "tapping the selected chip again clears it")

            viewModel.selectDistrict("성수동")
            advanceUntilIdle()
            viewModel.clearDistrict()
            advanceUntilIdle()
            assertNull(viewModel.state.value.query.districtKo)
            assertEquals(popular, routes.browseCalls.last().first)
        }

    @Test
    fun aDistrictNoLongerOfferedFallsBackToAll() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    districts = listOf(district("성수동", 1))
                    browseResults[popular] = routes(2)
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            viewModel.selectDistrict("성수동")
            advanceUntilIdle()

            routes.districts = listOf(district("한남동", 2))
            viewModel.open()
            advanceUntilIdle()

            assertNull(viewModel.state.value.query.districtKo)
            assertEquals(popular, routes.browseCalls.last().first)
            assertEquals(2, assertIs<BrowseListState.Loaded>(viewModel.state.value.list).routes.size)
        }

    @Test
    fun aFailedDistrictReadLeavesNoChipsAndStillShowsRoutes() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    districtFailures += PersonalRouteFailure.Network
                    browseResults[seongsu] = listOf(route("s1"))
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            viewModel.selectDistrict("성수동")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.districts.isEmpty())
            assertEquals("성수동", state.query.districtKo, "a failed read never clears the filter")
            assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
        }

    // US4: states

    @Test
    fun aFailedFirstPageOffersARetryOfTheSameQuery() =
        runTest(dispatcher) {
            val routes =
                FakePersonalRouteRepository().apply {
                    browseFailures += PersonalRouteFailure.Network
                    browseResults[popular] = routes(2)
                }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            assertEquals(BrowseListState.FirstPageError, viewModel.state.value.list)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(listOf(Triple(popular, 0, 20), Triple(popular, 0, 20)), routes.browseCalls)
            assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
        }

    @Test
    fun aFailedLaterPageKeepsTheRowsAndRetriesTheSameOffset() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { browseResults[popular] = routes(30) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            routes.browseFailures += PersonalRouteFailure.Network

            viewModel.loadMore()
            advanceUntilIdle()
            val failed = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertTrue(failed.appendFailed)
            assertEquals(20, failed.routes.size)

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(2, routes.browseCalls.size, "scrolling does not retry a failed page by itself")

            viewModel.retryMore()
            advanceUntilIdle()

            assertEquals(listOf(0, 20, 20), routes.browseCalls.map { it.second })
            val loaded = assertIs<BrowseListState.Loaded>(viewModel.state.value.list)
            assertFalse(loaded.appendFailed)
            assertEquals(30, loaded.routes.size)
        }

    @Test
    fun nothingListedIsAnEmptyListAndAnEmptyDistrictSaysSo() =
        runTest(dispatcher) {
            val routes = FakePersonalRouteRepository().apply { districts = listOf(district("성수동", 1)) }
            val viewModel = browse(routes)
            observe(viewModel)
            viewModel.open()
            advanceUntilIdle()
            assertEquals(BrowseListState.Empty(filtered = false), viewModel.state.value.list)

            viewModel.selectDistrict("성수동")
            advanceUntilIdle()
            assertEquals(BrowseListState.Empty(filtered = true), viewModel.state.value.list)
        }

    private fun TestScope.observe(viewModel: PublicRouteBrowseViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun browse(routes: FakePersonalRouteRepository): PublicRouteBrowseViewModel =
        PublicRouteBrowseViewModel(
            routeRepository = routes,
            clock =
                object : Clock {
                    override fun now(): Instant = now
                },
        )

    private fun district(
        nameKo: String,
        routeCount: Int,
    ) = PublicRouteDistrict(nameKo = nameKo, nameEn = nameKo, routeCount = routeCount)

    private fun routes(count: Int): List<BrowsedPublicRoute> = (1..count).map { route("r$it") }

    private fun route(id: String) =
        BrowsedPublicRoute(
            summary =
                PublicRouteSummary(
                    id = id,
                    name = "동선 $id",
                    stopCount = 2,
                    firstDistrictKo = "한남동",
                    firstDistrictEn = "Hannam-dong",
                    lastDistrictKo = "이태원동",
                    lastDistrictEn = "Itaewon-dong",
                    authorDisplayName = "작가",
                    isEditor = false,
                    copyCount30d = 1,
                    firstSharedDay = LocalDate(2026, 10, 15),
                    approvedAt = Instant.parse("2026-10-12T00:00:00Z"),
                ),
            coverUrls = listOf("https://img/$id.jpg"),
        )
}
