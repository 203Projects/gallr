package com.gallr.app.viewmodel

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.home.HomeCollectionKind
import com.gallr.shared.repository.CuratorNoteRepository
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MostViewedExhibitionRepository
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 10, 10)
    private val mostViewed = FakeMostViewed(Result.success(emptyList()))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theCatalogueDecidesLoadingAndError() {
        assertEquals(
            HomeUiState.Loading,
            homeUiState(
                ExhibitionListState.Loading,
                ExhibitionListState.Loading,
                emptyList(),
                AuthState.Anonymous,
                today,
            ),
        )
        assertEquals(
            HomeUiState.Error("network"),
            homeUiState(
                ExhibitionListState.Error("network"),
                ExhibitionListState.Success(listOf(exhibition("f", isFeatured = true))),
                emptyList(),
                AuthState.Anonymous,
                today,
            ),
        )
    }

    @Test
    fun theHeroFollowsTheFeaturedListAndFallsBackToTheCatalogue() {
        val a = exhibition("a", isFeatured = true)
        val b = exhibition("b", isFeatured = true)
        val plain = exhibition("plain")
        val catalogue = ExhibitionListState.Success(listOf(plain, a, b))

        val ordered =
            homeUiState(catalogue, ExhibitionListState.Success(listOf(b, a)), emptyList(), AuthState.Anonymous, today)
        val fallback = homeUiState(catalogue, ExhibitionListState.Loading, emptyList(), AuthState.Anonymous, today)

        assertEquals(listOf("b", "a"), assertIs<HomeUiState.Ready>(ordered).feed.hero.map { it.id })
        assertEquals(listOf("a", "b"), assertIs<HomeUiState.Ready>(fallback).feed.hero.map { it.id })
    }

    @Test
    fun endedAndFarFutureExhibitionsAreLeftOut() {
        val ended = exhibition("ended", closing = LocalDate(2026, 10, 9), isFeatured = true)
        val farFuture =
            exhibition("later", opening = LocalDate(2026, 11, 20), closing = LocalDate(2027, 1, 1), isFeatured = true)
        val running = exhibition("now", isFeatured = true)
        val catalogue = ExhibitionListState.Success(listOf(ended, farFuture, running))

        val state = homeUiState(catalogue, catalogue, emptyList(), AuthState.Anonymous, today)

        assertEquals(listOf("now"), assertIs<HomeUiState.Ready>(state).feed.hero.map { it.id })
    }

    @Test
    fun theGreetingNamesASignedInVisitorOnly() {
        val catalogue = ExhibitionListState.Success(listOf(exhibition("a")))
        val named = AuthState.Authenticated(GallrUser("u1", " 하신 ", null))
        val unnamed = AuthState.Authenticated(GallrUser("u2", "", null))

        assertEquals(
            "하신",
            assertIs<HomeUiState.Ready>(homeUiState(catalogue, catalogue, emptyList(), named, today)).greetingName,
        )
        assertNull(
            assertIs<HomeUiState.Ready>(homeUiState(catalogue, catalogue, emptyList(), unnamed, today)).greetingName,
        )
        assertNull(
            assertIs<HomeUiState.Ready>(
                homeUiState(catalogue, catalogue, emptyList(), AuthState.Anonymous, today),
            ).greetingName,
        )
    }

    @Test
    fun theStateFollowsTheCatalogueAndTheSession() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featured = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel =
                HomeViewModel(
                    exhibitionsState = exhibitions,
                    featuredState = featured,
                    followedGalleryRepository = NoFollows,
                    authState = auth,
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            assertEquals(HomeUiState.Loading, viewModel.state.value)

            exhibitions.value = ExhibitionListState.Success(listOf(exhibition("a", isFeatured = true)))
            assertEquals(listOf("a"), assertIs<HomeUiState.Ready>(viewModel.state.value).feed.hero.map { it.id })

            auth.value = AuthState.Authenticated(GallrUser("u1", "hanshin", null))
            assertEquals("hanshin", assertIs<HomeUiState.Ready>(viewModel.state.value).greetingName)
        }

    @Test
    fun approvedNotesBecomeTheCuratorEyeSection() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("a"), exhibition("b"))),
                )
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.success(listOf(note("b"), note("a"), note("missing")))),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertEquals(listOf("b", "a"), ready.feed.curatorEye.map { it.exhibition.id })
        }

    @Test
    fun aNotesFailureLeavesTheSectionEmptyAndTheTabReady() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(listOf(exhibition("a"))))
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.failure(IllegalStateException("offline"))),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertTrue(ready.feed.curatorEye.isEmpty())
        }

    @Test
    fun theRoutesRailShowsTheRankingsFirstThreeToAnyVisitorAndReadsOnce() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(listOf(exhibition("a"))))
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val routes =
                FakePersonalRouteRepository().apply {
                    publicRoutes = listOf("r1", "r2", "r3", "r4").map(::publicRoute)
                }
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = auth,
                    routeRepository = routes,
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val signedOut = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertEquals(listOf("r1", "r2", "r3"), signedOut.feed.routes.map { it.summary.id })

            auth.value = AuthState.Authenticated(GallrUser("u1", "hanshin", null))
            val signedIn = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertEquals(listOf("r1", "r2", "r3"), signedIn.feed.routes.map { it.summary.id })
            assertEquals(1, routes.publicListCalls)
        }

    @Test
    fun aRoutesFailureHidesTheRailAndKeepsTheTabReady() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(listOf(exhibition("a"))))
            val routes =
                FakePersonalRouteRepository().apply {
                    publicRoutes = listOf(publicRoute("r1"))
                    publicListFailures += PersonalRouteFailure.Network
                }
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = routes,
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertTrue(ready.feed.routes.isEmpty())
            assertEquals(1, routes.publicListCalls)
        }

    @Test
    fun aRouteCardCarriesItsStopsCoversAndSurvivesAFailedStopsRead() =
        runTest(dispatcher) {
            val covered = exhibition("a").copy(coverImageUrl = "https://img/a.jpg")
            val catalogue =
                MutableStateFlow<ExhibitionListState>(ExhibitionListState.Success(listOf(covered, exhibition("b"))))
            val routes =
                FakePersonalRouteRepository().apply {
                    publicRoutes = listOf(publicRoute("r1"), publicRoute("r2"))
                    // r2 has no stops entry: its read answers "no longer shown", and its card shows no covers.
                    publicStops["r1"] = PersonalRoute(id = "r1", name = "동선 r1", stops = listOf(stop("b"), stop("a")))
                }
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = routes,
                    mostViewedRepository = mostViewed,
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val cards = assertIs<HomeUiState.Ready>(viewModel.state.value).feed.routes
            assertEquals(listOf("https://img/a.jpg"), cards[0].stopCoverUrls)
            assertTrue(cards[1].stopCoverUrls.isEmpty())
            assertEquals(setOf("r1", "r2"), routes.publicStopsReads.toSet())
        }

    @Test
    fun theMostViewedTileFollowsTheServersRanking() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("a"), exhibition("b"), exhibition("c"))),
                )
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = FakeMostViewed(Result.success(listOf("c", "a", "b"))),
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val collection = assertIs<HomeUiState.Ready>(viewModel.state.value).feed.collections.first()
            assertEquals(HomeCollectionKind.MOST_VIEWED, collection.kind)
            assertEquals(listOf("c", "a", "b"), collection.exhibitions.map { it.id })
        }

    @Test
    fun aMostViewedFailureHidesTheTileAndKeepsTheTabReady() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("a"), exhibition("b"), exhibition("c"))),
                )
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = FakeMostViewed(Result.failure(IllegalStateException("offline"))),
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertTrue(ready.feed.collections.none { it.kind == HomeCollectionKind.MOST_VIEWED })
        }

    @Test
    fun theFeedIsReadyWhileAnEditorialReadIsStillPending() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("a"), exhibition("b"), exhibition("c"))),
                )
            val pending = CompletableDeferred<Result<List<String>>>()
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = FakeMostViewed(pending = pending),
                    curatorNoteRepository = FakeNotes(Result.success(listOf(note("a")))),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )
            backgroundScope.launch { viewModel.state.collect {} }

            val waiting = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertEquals(listOf("a"), waiting.feed.curatorEye.map { it.exhibition.id })
            assertTrue(waiting.feed.collections.none { it.kind == HomeCollectionKind.MOST_VIEWED })

            pending.complete(Result.success(listOf("c", "a", "b")))

            val filled = assertIs<HomeUiState.Ready>(viewModel.state.value)
            val firstFilled = filled.feed.collections.first()
            assertEquals(HomeCollectionKind.MOST_VIEWED, firstFilled.kind)
        }

    @Test
    fun theEditorialReadsAreMadeOncePerViewModel() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("a"), exhibition("b"), exhibition("c"))),
                )
            val popular = FakeMostViewed(Result.success(listOf("c", "a", "b")))
            val viewModel =
                HomeViewModel(
                    exhibitionsState = catalogue,
                    featuredState = catalogue,
                    followedGalleryRepository = NoFollows,
                    authState = MutableStateFlow(AuthState.Anonymous),
                    routeRepository = FakePersonalRouteRepository(),
                    mostViewedRepository = popular,
                    curatorNoteRepository = FakeNotes(Result.success(emptyList())),
                    todayProvider = { today },
                    backgroundDispatcher = dispatcher,
                )

            // The visitor leaves Home for longer than the state keeps its upstream, then comes back.
            val firstVisit = backgroundScope.launch { viewModel.state.collect {} }
            firstVisit.cancel()
            advanceTimeBy(6_000)
            backgroundScope.launch { viewModel.state.collect {} }

            assertEquals(1, popular.reads)
            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            val first = ready.feed.collections.first()
            assertEquals(HomeCollectionKind.MOST_VIEWED, first.kind)
        }

    private class FakeMostViewed(
        private val result: Result<List<String>> = Result.success(emptyList()),
        private val pending: CompletableDeferred<Result<List<String>>>? = null,
    ) : MostViewedExhibitionRepository {
        var reads = 0
            private set

        override suspend fun mostViewedIds(): Result<List<String>> {
            reads += 1
            return pending?.await() ?: result
        }
    }

    private fun stop(exhibitionId: String) =
        PersonalRouteStop(
            exhibitionId = exhibitionId,
            nameKo = "전시 $exhibitionId",
            nameEn = "Show $exhibitionId",
            venueNameKo = "장소",
            venueNameEn = "Venue",
            point = GeoPoint(latitude = 37.58, longitude = 126.98),
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            cityKo = "서울",
        )

    private fun publicRoute(id: String) =
        PublicRouteSummary(
            id = id,
            name = "동선 $id",
            stopCount = 3,
            firstDistrictKo = "종로구",
            firstDistrictEn = "Jongno-gu",
            lastDistrictKo = "용산구",
            lastDistrictEn = "Yongsan-gu",
            authorDisplayName = "작가",
            isEditor = false,
            copyCount30d = 4,
            firstSharedDay = today,
            approvedAt = Instant.parse("2026-10-09T00:00:00Z"),
        )

    private class FakeNotes(
        private val result: Result<List<CuratorNote>>,
    ) : CuratorNoteRepository {
        override suspend fun getPublishedNotes(): Result<List<CuratorNote>> = result

        override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> =
            result.map { notes -> notes.firstOrNull { it.exhibitionId == exhibitionId } }

        override suspend fun getGalleryImages(exhibitionId: String): Result<List<ExhibitionImage>> =
            Result.success(emptyList())
    }

    private fun note(exhibitionId: String) =
        CuratorNote(
            exhibitionId = exhibitionId,
            versionNumber = 1,
            hookKo = "훅 $exhibitionId",
            hookEn = "",
            insightsKo = listOf("하나", "둘"),
            insightsEn = emptyList(),
            tipKo = "팁",
            tipEn = "",
            approvedAt = Instant.parse("2026-10-0${if (exhibitionId == "b") 9 else 8}T03:00:00Z"),
        )

    private object NoFollows : FollowedGalleryRepository {
        override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = MutableStateFlow(emptyList())

        override suspend fun followGalleries(galleries: List<FollowedGallery>) = error("not used here")

        override suspend fun unfollowGallery(galleryKey: String) = error("not used here")

        override suspend fun acknowledgeGallery(
            galleryKey: String,
            currentExhibitionIds: Set<String>,
        ) = error("not used here")
    }

    private fun exhibition(
        id: String,
        isFeatured: Boolean = false,
        opening: LocalDate = LocalDate(2026, 9, 1),
        closing: LocalDate = LocalDate(2026, 11, 30),
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
        isFeatured = isFeatured,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
