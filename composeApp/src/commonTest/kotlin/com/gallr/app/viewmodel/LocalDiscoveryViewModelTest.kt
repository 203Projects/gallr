package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.EstimatedLeg
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.ExhibitionRecommendationIndex
import com.gallr.shared.recommendation.ExhibitionRecommender
import com.gallr.shared.recommendation.RecommendationContext
import com.gallr.shared.recommendation.RecommendationEvidence
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDiscoveryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 8, 30)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cold catalogue prepares local recommendations without inferred signals`() =
        runTest(dispatcher) {
            val recommender = RecordingRecommender()
            val language = MutableStateFlow(AppLanguage.KO)
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b")),
                    language = language,
                    recommender = recommender,
                )
            advanceUntilIdle()

            val ready = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("a", "b"), ready.items.map { it.exhibition.id })
            assertEquals(1L, ready.runId)
            assertTrue(
                recommender.contexts
                    .single()
                    .bookmarkedExhibitionIds
                    .isEmpty(),
            )
            assertTrue(
                recommender.contexts
                    .single()
                    .visits
                    .isEmpty(),
            )
            assertTrue(
                recommender.contexts
                    .single()
                    .followedGalleries
                    .isEmpty(),
            )
            assertEquals(today, recommender.contexts.single().today)

            language.value = AppLanguage.EN
            assertEquals(AppLanguage.EN, viewModel.language.value)
        }

    @Test
    fun `bookmark visit and follow changes rerank through the same prepared index`() =
        runTest(dispatcher) {
            val a = exhibition("a")
            val b = exhibition("b")
            val c = exhibition("c")
            val bookmarks = MutableStateFlow<Set<String>>(emptySet())
            val visits = LocalDiscoveryVisitRepository()
            val follows = LocalDiscoveryFollowedGalleryRepository()
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(a, b, c),
                    bookmarks = bookmarks,
                    visits = visits,
                    follows = follows,
                    recommender = recommender,
                )
            advanceUntilIdle()
            val first = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            val prepared = recommender.returnedIndexes.single()

            bookmarks.value = setOf("a")
            advanceUntilIdle()
            val afterBookmark = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("b", "c"), afterBookmark.items.map { it.exhibition.id })
            assertNotEquals(first.runId, afterBookmark.runId)
            assertSame(prepared, recommender.previousIndexes.last())
            assertEquals(1, recommender.createdIndexCount)

            visits.visits.value = listOf(visit(b))
            follows.followed.value = listOf(followedGallery("gallery-c"))
            advanceUntilIdle()
            val afterAllSignals = assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
            assertEquals(listOf("c"), afterAllSignals.items.map { it.exhibition.id })
            assertEquals(setOf("a"), recommender.contexts.last().bookmarkedExhibitionIds)
            assertEquals(
                listOf("b"),
                recommender.contexts
                    .last()
                    .visits
                    .map { it.exhibitionId },
            )
            assertEquals(
                listOf("gallery-c"),
                recommender.contexts
                    .last()
                    .followedGalleries
                    .map { it.galleryKey },
            )
            assertEquals(1, recommender.createdIndexCount)
        }

    @Test
    fun `catalogue loading empty and failure become explicit recommendation states`() =
        runTest(dispatcher) {
            val catalogue = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val viewModel = createViewModel(exhibitionsState = catalogue)

            assertIs<RecommendationUiState.Loading>(viewModel.recommendationState.value)
            catalogue.value = ExhibitionListState.Success(emptyList())
            advanceUntilIdle()
            assertIs<RecommendationUiState.Empty>(viewModel.recommendationState.value)

            catalogue.value = ExhibitionListState.Error("private backend detail")
            advanceUntilIdle()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)
        }

    @Test
    fun `repository flow failure retries and recovers local recommendations`() =
        runTest(dispatcher) {
            val visits = RecoveringLocalDiscoveryVisitRepository()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    visits = visits,
                )

            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, visits.subscriptionCount)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `persistent repository failure stops bounded retries until explicit retry`() =
        runTest(dispatcher) {
            val visits = FailingLocalDiscoveryVisitRepository()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    visits = visits,
                )

            advanceUntilIdle()

            assertEquals(3, visits.subscriptionCount)
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, visits.subscriptionCount)

            viewModel.retryRecommendations()
            advanceUntilIdle()
            assertEquals(6, visits.subscriptionCount)
        }

    @Test
    fun `explicit retry reruns an equal catalogue after a computation failure`() =
        runTest(dispatcher) {
            val recommender = RecordingRecommender().apply { failingPrepareCalls = 1 }
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                )
            advanceUntilIdle()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            viewModel.retryRecommendations()
            advanceUntilIdle()

            assertEquals(2, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `repeated retry taps coalesce behind one computation`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            val recommender = RecordingRecommender().apply { failingPrepareCalls = 1 }
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                    backgroundDispatcher = backgroundDispatcher,
                )
            runCurrent()
            assertIs<RecommendationUiState.Error>(viewModel.recommendationState.value)

            viewModel.retryRecommendations()
            viewModel.retryRecommendations()
            runCurrent()

            assertEquals(2, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `every route mode plans from the latest organic snapshot`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val exhibitions =
                listOf(
                    exhibition("saved-a", latitude = 37.5705, longitude = 126.9805),
                    exhibition("saved-b", latitude = 37.5710, longitude = 126.9810),
                    exhibition("for-you-a", latitude = 37.5715, longitude = 126.9815),
                    exhibition("for-you-b", latitude = 37.5720, longitude = 126.9820),
                )
            val viewModel =
                createViewModel(
                    exhibitions = exhibitions,
                    bookmarks = MutableStateFlow(setOf("saved-a", "saved-b")),
                )
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(origin)

            RouteCurationMode.entries.forEach { mode ->
                viewModel.setRouteMode(mode)
                viewModel.setStopCount(2)
                viewModel.buildRoute()
                advanceUntilIdle()

                val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
                assertEquals(mode, ready.request.mode)
                assertEquals(2, ready.estimate.stops.size)
                when (mode) {
                    RouteCurationMode.FOR_YOU -> {
                        assertEquals(
                            setOf("for-you-a", "for-you-b"),
                            ready.estimate.stops.mapTo(mutableSetOf()) { it.id },
                        )
                    }

                    RouteCurationMode.SAVED -> {
                        assertEquals(
                            setOf("saved-a", "saved-b"),
                            ready.estimate.stops.mapTo(mutableSetOf()) { it.id },
                        )
                    }

                    RouteCurationMode.NEIGHBORHOOD,
                    RouteCurationMode.CLOSING_SOON,
                    -> {
                        assertTrue(
                            ready.estimate.stops.all { stop ->
                                exhibitions.any { it.id == stop.id }
                            },
                        )
                    }
                }
            }
        }

    @Test
    fun `route shortage retains the request and available count for editing`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val viewModel = createViewModel(exhibitions = listOf(exhibition("only")))
            advanceUntilIdle()

            viewModel.beginRouteIfNeeded(origin)
            viewModel.setStopCount(3)
            viewModel.buildRoute()
            advanceUntilIdle()

            val insufficient = assertIs<RouteUiState.Insufficient>(viewModel.routeState.value)
            assertEquals(3, insufficient.request.stopCount)
            assertEquals(1, insufficient.available)
            assertEquals(origin, insufficient.request.origin)

            viewModel.setStopCount(2)
            val editing = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(2, editing.request.stopCount)
        }

    @Test
    fun `ready route and started status survive returning with the same origin`() =
        runTest(dispatcher) {
            val origin = GeoPoint(37.5700, 126.9800)
            val viewModel =
                createViewModel(
                    exhibitions =
                        listOf(
                            exhibition("a", latitude = 37.5705, longitude = 126.9805),
                            exhibition("b", latitude = 37.5710, longitude = 126.9810),
                        ),
                )
            advanceUntilIdle()
            viewModel.beginRouteIfNeeded(origin)
            viewModel.setStopCount(2)
            viewModel.buildRoute()
            advanceUntilIdle()
            val ready = assertIs<RouteUiState.Ready>(viewModel.routeState.value)

            assertTrue(viewModel.markRouteStarted())
            assertFalse(viewModel.markRouteStarted())
            val started = assertIs<RouteUiState.Ready>(viewModel.routeState.value)
            assertEquals(ready.buildId, started.buildId)
            assertTrue(started.hasStarted)

            viewModel.beginRouteIfNeeded(origin)
            assertSame(started, viewModel.routeState.value)

            viewModel.beginRouteIfNeeded(
                origin = origin,
                initialMode = RouteCurationMode.SAVED,
                requestId = 2L,
            )
            val newSession = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(RouteCurationMode.SAVED, newSession.request.mode)

            val newOrigin = GeoPoint(37.5800, 126.9900)
            viewModel.beginRouteIfNeeded(newOrigin, RouteCurationMode.SAVED)
            val editing = assertIs<RouteUiState.Editing>(viewModel.routeState.value)
            assertEquals(newOrigin, editing.request.origin)
            assertEquals(RouteCurationMode.SAVED, editing.request.mode)
        }

    @Test
    fun `recommendation preparation is dispatched instead of running inline on Main`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            val recommender = RecordingRecommender()
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a")),
                    recommender = recommender,
                    backgroundDispatcher = backgroundDispatcher,
                )

            assertEquals(0, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Loading>(viewModel.recommendationState.value)

            runCurrent()

            assertEquals(1, recommender.prepareCalls.size)
            assertIs<RecommendationUiState.Ready>(viewModel.recommendationState.value)
        }

    @Test
    fun `route planning is dispatched instead of running inline on Main`() =
        runTest(dispatcher) {
            val backgroundDispatcher = StandardTestDispatcher(testScheduler)
            var estimateCalls = 0
            val routePlanner =
                NeighborhoodRoutePlanner(
                    RouteLegEstimator { _, _ ->
                        estimateCalls += 1
                        EstimatedLeg(distanceMeters = 100, travelMinutes = 2)
                    },
                )
            val viewModel =
                createViewModel(
                    exhibitions = listOf(exhibition("a"), exhibition("b")),
                    backgroundDispatcher = backgroundDispatcher,
                    routePlanner = routePlanner,
                )
            runCurrent()

            viewModel.beginRouteIfNeeded(GeoPoint(37.5700, 126.9800))
            viewModel.setStopCount(2)
            viewModel.buildRoute()

            assertEquals(0, estimateCalls)
            assertIs<RouteUiState.Planning>(viewModel.routeState.value)

            runCurrent()

            assertTrue(estimateCalls > 0)
            assertIs<RouteUiState.Ready>(viewModel.routeState.value)
        }

    private fun createViewModel(
        exhibitions: List<Exhibition> = emptyList(),
        exhibitionsState: StateFlowExhibitions = MutableStateFlow(ExhibitionListState.Success(exhibitions)),
        bookmarks: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        visits: VisitRepository = LocalDiscoveryVisitRepository(),
        follows: FollowedGalleryRepository = LocalDiscoveryFollowedGalleryRepository(),
        language: MutableStateFlow<AppLanguage> = MutableStateFlow(AppLanguage.KO),
        recommender: RecordingRecommender = RecordingRecommender(),
        backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = dispatcher,
        routePlanner: NeighborhoodRoutePlanner =
            NeighborhoodRoutePlanner(
                RouteLegEstimator { _, _ -> EstimatedLeg(distanceMeters = 100, travelMinutes = 2) },
            ),
    ) = LocalDiscoveryViewModel(
        exhibitionsState = exhibitionsState,
        bookmarkedIds = bookmarks,
        visitRepository = visits,
        followedGalleryRepository = follows,
        language = language,
        backgroundDispatcher = backgroundDispatcher,
        todayProvider = { today },
        recommender = recommender,
        routePlanner = routePlanner,
    )

    private fun exhibition(
        id: String,
        latitude: Double = 37.5705,
        longitude: Double = 126.9805,
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
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 9, 30),
        isFeatured = true,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "설명 $id",
        descriptionEn = "Description $id",
        addressKo = "주소 $id",
        addressEn = "Address $id",
        coverImageUrl = null,
        galleryId = "gallery-$id",
    )

    private fun visit(exhibition: Exhibition) =
        ExhibitionVisit(
            clientRecordId = "visit-${exhibition.id}",
            exhibitionId = exhibition.id,
            snapshot = ExhibitionVisitSnapshot.from(exhibition),
            createdAt = Instant.fromEpochMilliseconds(1),
        )

    private fun followedGallery(key: String) =
        FollowedGallery(
            galleryKey = key,
            snapshot =
                FollowedGallerySnapshot(
                    nameKo = key,
                    nameEn = key,
                    cityKo = "서울",
                    cityEn = "Seoul",
                    regionKo = "종로구",
                    regionEn = "Jongno-gu",
                ),
            knownExhibitionIds = emptySet(),
            followedAt = Instant.fromEpochMilliseconds(1),
        )
}

private typealias StateFlowExhibitions = kotlinx.coroutines.flow.StateFlow<ExhibitionListState>

private class RecordingRecommender : ExhibitionRecommender {
    val prepareCalls = mutableListOf<List<Exhibition>>()
    val previousIndexes = mutableListOf<ExhibitionRecommendationIndex?>()
    val returnedIndexes = mutableListOf<ExhibitionRecommendationIndex>()
    val contexts = mutableListOf<RecommendationContext>()
    var createdIndexCount = 0
        private set
    var failingPrepareCalls = 0

    override fun prepare(
        catalogue: List<Exhibition>,
        previous: ExhibitionRecommendationIndex?,
    ): ExhibitionRecommendationIndex {
        prepareCalls += catalogue
        previousIndexes += previous
        if (failingPrepareCalls > 0) {
            failingPrepareCalls -= 1
            error("private recommendation detail")
        }
        val reusable = (previous as? RecordingIndex)?.takeIf { it.catalogue == catalogue }
        val result =
            reusable ?: RecordingIndex(catalogue.toList()).also {
                createdIndexCount += 1
                returnedIndexes += it
            }
        return result
    }

    private inner class RecordingIndex(
        val catalogue: List<Exhibition>,
    ) : ExhibitionRecommendationIndex {
        override fun recommend(context: RecommendationContext): List<ExhibitionRecommendation> {
            contexts += context
            val visitedIds = context.visits.mapTo(mutableSetOf()) { it.exhibitionId }
            return catalogue
                .filterNot { it.id in context.bookmarkedExhibitionIds || it.id in visitedIds }
                .take(context.limit)
                .mapIndexed { index, exhibition ->
                    ExhibitionRecommendation(
                        exhibition = exhibition,
                        scoreBasisPoints = 10_000 - index,
                        evidence = listOf(RecommendationEvidence.Featured),
                    )
                }
        }
    }
}

private class LocalDiscoveryVisitRepository : VisitRepository {
    val visits = MutableStateFlow<List<ExhibitionVisit>>(emptyList())

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        this.visits.value = (this.visits.value + visits).distinctBy { it.exhibitionId }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}

private class RecoveringLocalDiscoveryVisitRepository : VisitRepository {
    private val recoveredVisits = MutableStateFlow<List<ExhibitionVisit>>(emptyList())
    var subscriptionCount = 0
        private set

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        flow {
            subscriptionCount += 1
            if (subscriptionCount == 1) error("private repository detail")
            emitAll(recoveredVisits)
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        recoveredVisits.value = visits
    }

    override suspend fun removeVisit(exhibitionId: String) {
        recoveredVisits.value = recoveredVisits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}

private class FailingLocalDiscoveryVisitRepository : VisitRepository {
    var subscriptionCount = 0
        private set

    override fun observeVisits(): Flow<List<ExhibitionVisit>> =
        flow {
            subscriptionCount += 1
            error("private persistent repository detail")
        }

    override suspend fun addVisits(visits: List<ExhibitionVisit>) = Unit

    override suspend fun removeVisit(exhibitionId: String) = Unit
}

private class LocalDiscoveryFollowedGalleryRepository : FollowedGalleryRepository {
    val followed = MutableStateFlow<List<FollowedGallery>>(emptyList())

    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = followed

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        followed.value = (followed.value + galleries).distinctBy { it.galleryKey }
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        followed.value = followed.value.filterNot { it.galleryKey == galleryKey }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) = Unit
}
