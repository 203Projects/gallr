package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RoutePlanResult
import com.gallr.shared.map.RoutePlanningRequest
import com.gallr.shared.observability.AppLog
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.ExhibitionRecommendationIndex
import com.gallr.shared.recommendation.ExhibitionRecommender
import com.gallr.shared.recommendation.LocalExhibitionRecommender
import com.gallr.shared.recommendation.RecommendationContext
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

sealed interface RecommendationUiState {
    data object Loading : RecommendationUiState

    data class Ready(
        val runId: Long,
        val items: List<ExhibitionRecommendation>,
    ) : RecommendationUiState

    data object Empty : RecommendationUiState

    data object Error : RecommendationUiState
}

sealed interface RouteUiState {
    data object Idle : RouteUiState

    data class Editing(
        val request: RoutePlanningRequest,
    ) : RouteUiState

    data class Planning(
        val request: RoutePlanningRequest,
    ) : RouteUiState

    data class Ready(
        val buildId: Long,
        val request: RoutePlanningRequest,
        val estimate: ExhibitionRouteEstimate,
        val hasStarted: Boolean,
    ) : RouteUiState

    data class Insufficient(
        val request: RoutePlanningRequest,
        val available: Int,
    ) : RouteUiState

    data class Error(
        val request: RoutePlanningRequest,
    ) : RouteUiState
}

/**
 * Memory-only application orchestration for organic recommendations and local route estimates.
 *
 * Catalogue preparation and signal reranking run sequentially on the injected background
 * dispatcher. Route planning also runs there against the latest immutable organic snapshot. The
 * ViewModel never depends on promotion, networking, or persistence beyond observing the user's
 * existing local signals.
 */
class LocalDiscoveryViewModel(
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    private val bookmarkedIds: StateFlow<Set<String>>,
    private val visitRepository: VisitRepository,
    private val followedGalleryRepository: FollowedGalleryRepository,
    val language: StateFlow<AppLanguage>,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val todayProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
    },
    private val recommender: ExhibitionRecommender = LocalExhibitionRecommender(),
    private val routePlanner: NeighborhoodRoutePlanner = NeighborhoodRoutePlanner(),
) : ViewModel() {
    private val log = AppLog.tagged("LocalDiscoveryViewModel")
    private val _recommendationState = MutableStateFlow<RecommendationUiState>(RecommendationUiState.Loading)
    val recommendationState: StateFlow<RecommendationUiState> = _recommendationState.asStateFlow()

    private val _routeState = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val routeState: StateFlow<RouteUiState> = _routeState.asStateFlow()

    private val latestSnapshot = MutableStateFlow<DiscoverySnapshot?>(null)
    private val latestInputs = MutableStateFlow<RecommendationInputs?>(null)
    private val routeBuildRevision = MutableStateFlow(0L)
    private var recommendationIndex: ExhibitionRecommendationIndex? = null
    private var recommendationRunId = 0L
    private var routePlanningJob: Job? = null
    private var recommendationRetryJob: Job? = null
    private var inputObservationJob: Job? = null
    private var activeRouteRequestId: Long? = null
    private val recommendationMutex = Mutex()
    private val routePlanningMutex = Mutex()

    init {
        startInputObservation()
    }

    private fun startInputObservation() {
        if (inputObservationJob?.isActive == true) return
        inputObservationJob =
            viewModelScope.launch(backgroundDispatcher) {
                combine(
                    exhibitionsState,
                    bookmarkedIds,
                    visitRepository.observeVisits(),
                    followedGalleryRepository.observeFollowedGalleries(),
                ) { exhibitions, bookmarks, visits, followedGalleries ->
                    RecommendationInputs(
                        exhibitions = exhibitions,
                        bookmarkedIds = bookmarks.toSet(),
                        visits = visits.toList(),
                        followedGalleries = followedGalleries.toList(),
                    )
                }.retryWhen { error, attempt ->
                    if (error is CancellationException) throw error
                    log.warn("observe_discovery_inputs", error)
                    latestSnapshot.value = null
                    _recommendationState.value = RecommendationUiState.Error
                    if (attempt >= INPUT_RETRY_DELAYS_MILLIS.size.toLong()) {
                        false
                    } else {
                        delay(INPUT_RETRY_DELAYS_MILLIS[attempt.toInt()])
                        true
                    }
                }.catch { error ->
                    if (error is CancellationException) throw error
                    log.warn("observe_discovery_inputs_stopped", error)
                    latestSnapshot.value = null
                    _recommendationState.value = RecommendationUiState.Error
                }.collect(::updateRecommendations)
            }
    }

    fun beginRouteIfNeeded(
        origin: GeoPoint,
        initialMode: RouteCurationMode = RouteCurationMode.NEIGHBORHOOD,
        requestId: Long = 0L,
    ) {
        val current = _routeState.value
        if (activeRouteRequestId == requestId && current.requestOrNull()?.origin == origin) return

        activeRouteRequestId = requestId
        invalidateRoutePlan()
        _routeState.value =
            RouteUiState.Editing(
                RoutePlanningRequest(
                    origin = origin,
                    visitDate = todayProvider(),
                    mode = initialMode,
                    stopCount = DEFAULT_ROUTE_STOP_COUNT,
                    maxRadiusKm = ROUTE_RADIUS_KM,
                    visitMinutesPerStop = VISIT_MINUTES_PER_STOP,
                ),
            )
    }

    fun setRouteMode(mode: RouteCurationMode) {
        val request = _routeState.value.requestOrNull() ?: return
        if (request.mode == mode) return

        invalidateRoutePlan()
        _routeState.value = RouteUiState.Editing(request.copy(mode = mode))
    }

    fun setStopCount(stopCount: Int) {
        require(stopCount in MIN_ROUTE_STOPS..MAX_ROUTE_STOPS) {
            "stopCount must be between $MIN_ROUTE_STOPS and $MAX_ROUTE_STOPS"
        }
        val request = _routeState.value.requestOrNull() ?: return
        if (request.stopCount == stopCount) return

        invalidateRoutePlan()
        _routeState.value = RouteUiState.Editing(request.copy(stopCount = stopCount))
    }

    fun buildRoute() {
        val request = _routeState.value.requestOrNull() ?: return
        val buildId = invalidateRoutePlan()
        _routeState.value = RouteUiState.Planning(request)
        routePlanningJob =
            viewModelScope.launch(backgroundDispatcher) {
                val snapshot = recommendationMutex.withLock { latestSnapshot.value }
                if (snapshot == null) {
                    completeRouteBuild(buildId, RouteUiState.Error(request))
                    return@launch
                }
                try {
                    val result =
                        routePlanningMutex.withLock {
                            routePlanner.plan(
                                exhibitions = snapshot.exhibitions,
                                recommendations = snapshot.recommendations,
                                bookmarkedIds = snapshot.bookmarkedIds,
                                request = request,
                            )
                        }
                    coroutineContext.ensureActive()
                    val state =
                        when (result) {
                            is RoutePlanResult.Success -> {
                                RouteUiState.Ready(
                                    buildId = buildId,
                                    request = request,
                                    estimate = result.route,
                                    hasStarted = false,
                                )
                            }

                            is RoutePlanResult.InsufficientCandidates -> {
                                RouteUiState.Insufficient(
                                    request = request,
                                    available = result.available,
                                )
                            }
                        }
                    completeRouteBuild(buildId, state)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn("plan_route", error)
                    completeRouteBuild(buildId, RouteUiState.Error(request))
                }
            }
    }

    fun retryRecommendations() {
        if (inputObservationJob?.isActive != true) startInputObservation()
        if (recommendationRetryJob?.isActive == true) return
        recommendationRetryJob =
            viewModelScope.launch(backgroundDispatcher) {
                recommendationMutex.withLock {
                    val inputs = latestInputs.value ?: return@withLock
                    applyRecommendationInputs(inputs)
                }
            }
    }

    fun markRouteStarted(): Boolean {
        while (true) {
            val ready = _routeState.value as? RouteUiState.Ready ?: return false
            if (ready.hasStarted) return false
            if (_routeState.compareAndSet(ready, ready.copy(hasStarted = true))) return true
        }
    }

    private suspend fun updateRecommendations(inputs: RecommendationInputs) {
        recommendationMutex.withLock {
            latestInputs.value = inputs
            applyRecommendationInputs(inputs)
        }
    }

    private fun applyRecommendationInputs(inputs: RecommendationInputs) {
        when (val exhibitions = inputs.exhibitions) {
            ExhibitionListState.Loading -> {
                latestSnapshot.value = null
                _recommendationState.value = RecommendationUiState.Loading
            }

            is ExhibitionListState.Error -> {
                latestSnapshot.value = null
                _recommendationState.value = RecommendationUiState.Error
            }

            is ExhibitionListState.Success -> {
                try {
                    val catalogue = exhibitions.exhibitions.toList()
                    val prepared = recommender.prepare(catalogue, recommendationIndex)
                    recommendationIndex = prepared
                    val recommendations =
                        prepared
                            .recommend(
                                RecommendationContext(
                                    bookmarkedExhibitionIds = inputs.bookmarkedIds,
                                    visits = inputs.visits,
                                    followedGalleries = inputs.followedGalleries,
                                    today = todayProvider(),
                                    limit = RECOMMENDATION_LIMIT,
                                ),
                            ).toList()
                    latestSnapshot.value =
                        DiscoverySnapshot(
                            exhibitions = catalogue,
                            recommendations = recommendations,
                            bookmarkedIds = inputs.bookmarkedIds,
                        )
                    _recommendationState.value =
                        if (recommendations.isEmpty()) {
                            RecommendationUiState.Empty
                        } else {
                            recommendationRunId += 1
                            RecommendationUiState.Ready(
                                runId = recommendationRunId,
                                items = recommendations,
                            )
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn("prepare_recommendations", error)
                    latestSnapshot.value = null
                    _recommendationState.value = RecommendationUiState.Error
                }
            }
        }
    }

    private fun invalidateRoutePlan(): Long {
        routePlanningJob?.cancel()
        routePlanningJob = null
        val revision = routeBuildRevision.value + 1
        routeBuildRevision.value = revision
        return revision
    }

    private fun completeRouteBuild(
        buildId: Long,
        state: RouteUiState,
    ) {
        if (routeBuildRevision.value == buildId) _routeState.value = state
    }

    companion object {
        fun factory(
            exhibitionsState: StateFlow<ExhibitionListState>,
            bookmarkedIds: StateFlow<Set<String>>,
            visitRepository: VisitRepository,
            followedGalleryRepository: FollowedGalleryRepository,
            language: StateFlow<AppLanguage>,
            backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
            todayProvider: () -> LocalDate = {
                Clock.System.todayIn(TimeZone.currentSystemDefault())
            },
            recommender: ExhibitionRecommender = LocalExhibitionRecommender(),
            routePlanner: NeighborhoodRoutePlanner = NeighborhoodRoutePlanner(),
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    LocalDiscoveryViewModel(
                        exhibitionsState = exhibitionsState,
                        bookmarkedIds = bookmarkedIds,
                        visitRepository = visitRepository,
                        followedGalleryRepository = followedGalleryRepository,
                        language = language,
                        backgroundDispatcher = backgroundDispatcher,
                        todayProvider = todayProvider,
                        recommender = recommender,
                        routePlanner = routePlanner,
                    )
                }
            }
    }
}

private data class RecommendationInputs(
    val exhibitions: ExhibitionListState,
    val bookmarkedIds: Set<String>,
    val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>,
)

private data class DiscoverySnapshot(
    val exhibitions: List<Exhibition>,
    val recommendations: List<ExhibitionRecommendation>,
    val bookmarkedIds: Set<String>,
)

private fun RouteUiState.requestOrNull(): RoutePlanningRequest? =
    when (this) {
        RouteUiState.Idle -> null
        is RouteUiState.Editing -> request
        is RouteUiState.Planning -> request
        is RouteUiState.Ready -> request
        is RouteUiState.Insufficient -> request
        is RouteUiState.Error -> request
    }

private const val RECOMMENDATION_LIMIT = 6
private const val MIN_ROUTE_STOPS = 2
private const val MAX_ROUTE_STOPS = 5
private const val DEFAULT_ROUTE_STOP_COUNT = 3
private const val ROUTE_RADIUS_KM = 5.0
private const val VISIT_MINUTES_PER_STOP = 45
private val INPUT_RETRY_DELAYS_MILLIS = listOf(1_000L, 4_000L)
