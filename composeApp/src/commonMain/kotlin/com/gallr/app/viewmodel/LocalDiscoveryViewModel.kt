package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.ExhibitionRouteEstimate
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RoutePlanResult
import com.gallr.shared.map.RoutePlanningRequest
import com.gallr.shared.map.RouteWaypoint
import com.gallr.shared.map.RoutedLegEstimator
import com.gallr.shared.map.WalkingLegs
import com.gallr.shared.observability.AppLog
import com.gallr.shared.recommendation.ExhibitionRecommendation
import com.gallr.shared.recommendation.ExhibitionRecommendationIndex
import com.gallr.shared.recommendation.ExhibitionRecommender
import com.gallr.shared.recommendation.LocalExhibitionRecommender
import com.gallr.shared.recommendation.RecommendationContext
import com.gallr.shared.recommendation.RouteRelevance
import com.gallr.shared.recommendation.RouteRelevanceContext
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.TastePreferenceRepository
import com.gallr.shared.repository.VisitRepository
import com.gallr.shared.repository.WalkingRouteRepository
import com.gallr.shared.taste.LiftSignals
import com.gallr.shared.taste.TasteDismissal
import com.gallr.shared.taste.TastePickState
import com.gallr.shared.taste.liftedDismissals
import com.gallr.shared.taste.resolveArtTerms
import com.gallr.shared.taste.tasteTerms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** Counts of the local history a recommendation run was built from; shown, never transmitted. */
data class RecommendationBasis(
    val savedCount: Int,
    val visitedCount: Int,
    val followedCount: Int,
    /** Terms and exhibitions picked in the taste sheet (spec 101). */
    val pickedCount: Int = 0,
) {
    val isEmpty: Boolean get() = savedCount == 0 && visitedCount == 0 && followedCount == 0 && pickedCount == 0
}

sealed interface RecommendationUiState {
    data object Loading : RecommendationUiState

    data class Ready(
        val runId: Long,
        val items: List<ExhibitionRecommendation>,
        val basis: RecommendationBasis,
        /**
         * The taste tags above the list: terms picked in the taste sheet and terms that recur across saved, visited
         * and picked exhibitions, without the terms the visitor asked to see less of.
         */
        val tasteTerms: List<ArtTerm> = emptyList(),
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
        val closedCount: Int = 0,
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
    /** The visitor's saves as read from the store; a placeholder before the first read would read as no history. */
    private val bookmarkedIds: Flow<Set<String>>,
    private val visitRepository: VisitRepository,
    private val followedGalleryRepository: FollowedGalleryRepository,
    val language: StateFlow<AppLanguage>,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val todayProvider: () -> LocalDate = {
        Clock.System.todayIn(TimeZone.currentSystemDefault())
    },
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val recommender: ExhibitionRecommender = LocalExhibitionRecommender(),
    private val routePlanner: NeighborhoodRoutePlanner = NeighborhoodRoutePlanner(),
    /** Walking paths between the planned stops (spec 093); null keeps every leg an estimate. */
    private val walkingRoutes: WalkingRouteRepository? = null,
    /** The visitor's taste picks and 덜 보기 dismissals (spec 101); null ranks as if both were empty. */
    private val tastePreferences: TastePreferenceRepository? = null,
    /**
     * Whose history the device holds and whether it has arrived. Until an account's history first arrives the
     * device's lists say nothing about the visitor, so [hasHistory] stays unknown and nothing lifts a hide; after
     * that it stays settled through later syncs of the same account, and a new account starts over.
     */
    accountHistory: Flow<AccountHistory> = flowOf(AccountHistory.GUEST),
) : ViewModel() {
    private val log = AppLog.tagged("LocalDiscoveryViewModel")
    private val _recommendationState = MutableStateFlow<RecommendationUiState>(RecommendationUiState.Loading)
    val recommendationState: StateFlow<RecommendationUiState> = _recommendationState.asStateFlow()

    private val signalsHaveHistory = MutableStateFlow<Boolean?>(null)

    /** The current account and whether its history has arrived at least once (see the constructor). */
    private val accountSettle: StateFlow<AccountSettle?> =
        accountHistory
            .scan(null as AccountSettle?) { previous, history ->
                val sameAccount = previous != null && previous.accountId == history.accountId
                AccountSettle(
                    history.accountId,
                    settled = history.settled || (sameAccount && previous?.settled == true),
                    delivered = history.delivered || (sameAccount && previous?.delivered == true),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val settled: StateFlow<Boolean> =
        accountSettle
            .map { it?.settled == true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val delivered: StateFlow<Boolean> =
        accountSettle
            .map { it?.delivered == true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the visitor has saved, visited or followed anything; null until the signals are read and the
     * account's own history has arrived (spec 101 FR-001).
     */
    val hasHistory: StateFlow<Boolean?> =
        combine(signalsHaveHistory, settled) { history, isSettled -> history.takeIf { isSettled } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _tasteState = MutableStateFlow(TastePickState.EMPTY)

    /** The visitor's picks, skip and dismissals as last read. */
    val tasteState: StateFlow<TastePickState> = _tasteState.asStateFlow()

    private val _routeState = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val routeState: StateFlow<RouteUiState> = _routeState.asStateFlow()

    private val latestSnapshot = MutableStateFlow<DiscoverySnapshot?>(null)
    private val latestInputs = MutableStateFlow<RecommendationInputs?>(null)
    private val routeBuildRevision = MutableStateFlow(0L)
    private var recommendationIndex: ExhibitionRecommendationIndex? = null
    private var recommendationRunId = 0L

    /** True while saves, visits or follows have changed under a list that is still showing its earlier ranking. */
    private var hasHeldSignals = false

    /** Saves, visits and follows as last seen, so only a new one lifts a hide (spec 101 US3). */
    private var liftBaseline: LiftSignals? = null

    /**
     * Set when the account's history settles or its archive arrives: the next loaded inputs move the baseline
     * without lifting, because the arrival may reach the inputs a moment after the signal that announced it.
     */
    private var absorbNextLift = false

    /** Hides undone with 되돌리기; the card comes back at once even when a refresh had already removed it. */
    private val pendingUndoIds = mutableSetOf<String>()

    /** Taste writes in call order, so a 되돌리기 never lands before the hide it undoes. */
    private val tasteWrites = Channel<TasteWrite>(Channel.UNLIMITED)
    private var routePlanningJob: Job? = null
    private var recommendationRetryJob: Job? = null
    private var inputObservationJob: Job? = null
    private var activeRouteRequestId: Long? = null
    private val recommendationMutex = Mutex()
    private val routePlanningMutex = Mutex()

    /** The route as planned, before walking paths retimed it; kept per build so a late path never mixes builds. */
    private var plannedRoute: Pair<Long, ExhibitionRouteEstimate>? = null

    init {
        startTasteWrites()
        // While the account's history is still arriving, its saves and follows say nothing new; once it has
        // arrived they are the starting point, so what was already there never lifts a hide.
        viewModelScope.launch(backgroundDispatcher) {
            var previous: AccountSettle? = null
            accountSettle.collect { current ->
                recommendationMutex.withLock {
                    val sameAccount = previous?.accountId == current?.accountId
                    val newlySettled = current?.settled == true && !(sameAccount && previous?.settled == true)
                    val newlyDelivered = current?.delivered == true && !(sameAccount && previous?.delivered == true)
                    when {
                        current?.settled != true -> {
                            liftBaseline = null
                            absorbNextLift = false
                        }

                        newlySettled || newlyDelivered -> {
                            liftBaseline = latestInputs.value?.let(::liftSignalsOf)
                            absorbNextLift = true
                        }
                    }
                }
                previous = current
            }
        }
        startInputObservation()
        updateTaste("expire_taste_dismissals") { expire(todayProvider()) }
        walkingRoutes?.let { walking ->
            viewModelScope.launch(backgroundDispatcher) {
                walking.legs.collect { legs -> retimeShownRoute(legs) }
            }
        }
    }

    /** Times the shown route again with the walking paths known so far; its stops never change (spec 093). */
    private fun retimeShownRoute(legs: WalkingLegs) {
        val ready = _routeState.value as? RouteUiState.Ready ?: return
        val (buildId, planned) = plannedRoute ?: return
        if (buildId != ready.buildId) return
        val walked = routePlanner.retime(planned, ready.request, RoutedLegEstimator(legs))
        _routeState.compareAndSet(ready, ready.copy(estimate = walked))
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
                    tastePreferences?.state ?: flowOf(TastePickState.EMPTY),
                ) { exhibitions, bookmarks, visits, followedGalleries, taste ->
                    RecommendationInputs(
                        exhibitions = exhibitions,
                        bookmarkedIds = bookmarks.toSet(),
                        visits = visits.toList(),
                        followedGalleries = followedGalleries.toList(),
                        taste = taste,
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
        val request = _routeState.value.requestOrNull()?.stampedNow() ?: return
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
                                bookmarkedIds = snapshot.bookmarkedIds,
                                request = request,
                                forYouRelevance = snapshot.forYouRelevance(request),
                            )
                        }
                    coroutineContext.ensureActive()
                    val state =
                        when (result) {
                            is RoutePlanResult.Success -> {
                                plannedRoute = buildId to result.route
                                val legs = walkingRoutes?.legs?.value
                                RouteUiState.Ready(
                                    buildId = buildId,
                                    request = request,
                                    estimate =
                                        legs?.let { routePlanner.retime(result.route, request, RoutedLegEstimator(it)) }
                                            ?: result.route,
                                    hasStarted = false,
                                )
                            }

                            is RoutePlanResult.InsufficientCandidates -> {
                                RouteUiState.Insufficient(
                                    request = request,
                                    available = result.available,
                                    closedCount = result.closedCount,
                                )
                            }
                        }
                    completeRouteBuild(buildId, state)
                    if (result is RoutePlanResult.Success) resolveWalkingLegs(result.route)
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

    /**
     * Applies saves, visits and follows that arrived while a list was showing. A list on screen is not
     * re-ranked under the reader: saving a card would otherwise remove it and reshuffle the rest. Call
     * this when the reader comes to the list afresh (tab selected, list opened, pull to refresh).
     */
    fun refreshRecommendations() {
        updateTaste("expire_taste_dismissals") { expire(todayProvider()) }
        viewModelScope.launch(backgroundDispatcher) {
            recommendationMutex.withLock {
                if (!hasHeldSignals) return@withLock
                val inputs = latestInputs.value ?: return@withLock
                applyRecommendationInputs(inputs)
            }
        }
    }

    /**
     * 덜 보기: hides [exhibition] until it closes and counts its reviewed terms and artists against similar
     * cards (spec 101 FR-012). The list on screen keeps the card until the next refresh, so it can be undone.
     */
    fun dismiss(exhibition: Exhibition) {
        val record =
            TasteDismissal(
                exhibitionId = exhibition.id,
                closingDate = exhibition.closingDate,
                termIds = exhibition.artTerms.mapTo(mutableSetOf(), ArtTerm::id),
                artistIds = exhibition.artists.mapTo(mutableSetOf(), ExhibitionArtist::id),
            )
        updateTaste("dismiss_exhibition") { dismiss(record) }
    }

    /** The snackbar's 되돌리기. */
    fun undoDismissal(exhibitionId: String) {
        updateTaste("undo_dismissal") {
            // Only a standing hide needs the list back; undoing one already lifted would leave a stale entry.
            if (exhibitionId in state.first().dismissals.hiddenExhibitionIds) {
                recommendationMutex.withLock { pendingUndoIds += exhibitionId }
            }
            removeDismissals(setOf(exhibitionId))
        }
    }

    /** Queues a taste write; [tasteWrites] applies them one at a time in call order. */
    private fun updateTaste(
        operation: String,
        change: suspend TastePreferenceRepository.() -> Unit,
    ) {
        if (tastePreferences == null) return
        tasteWrites.trySend(TasteWrite(operation, change))
    }

    private fun startTasteWrites() {
        val repository = tastePreferences ?: return
        viewModelScope.launch(backgroundDispatcher) {
            for (write in tasteWrites) {
                try {
                    repository.(write.change)()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn(write.operation, error)
                }
            }
        }
    }

    /**
     * A save, a visit or a follow of its gallery made after the hide says the visitor wants the show after all,
     * so the hide goes ([liftedDismissals]). Signals that were already there do not count: hiding a card from a
     * followed gallery would otherwise be undone by the same update that records it. Nothing lifts while the
     * account's history is still arriving (sign-in, a cold start). After a failed sync the baseline follows every
     * change until the archive arrives, since what lands may be the account's own follows, so a follow made
     * offline lifts nothing; and what the arrival brings a moment after its signal is not new ([absorbNextLift]).
     * The baseline moves only over a loaded catalogue, so a signal that arrives while the list cannot be judged
     * is still new when it can. Caller holds [recommendationMutex].
     */
    private fun liftDismissals(inputs: RecommendationInputs) {
        if (!settled.value) {
            liftBaseline = null
            return
        }
        val now = liftSignalsOf(inputs) ?: return
        val before = liftBaseline
        liftBaseline = now
        val absorb = absorbNextLift || !delivered.value
        absorbNextLift = false
        if (before == null || absorb) return
        val lifted = liftedDismissals(before, now, inputs.taste.dismissals.hiddenExhibitionIds)
        if (lifted.isNotEmpty()) updateTaste("lift_dismissals") { removeDismissals(lifted) }
    }

    fun markRouteStarted(): Boolean {
        while (true) {
            val ready = _routeState.value as? RouteUiState.Ready ?: return false
            if (ready.hasStarted) return false
            if (_routeState.compareAndSet(ready, ready.copy(hasStarted = true))) return true
        }
    }

    private suspend fun updateRecommendations(inputs: RecommendationInputs) {
        // The taste first: a reader that sees "no history" must already see this skip or pick.
        _tasteState.value = inputs.taste
        signalsHaveHistory.value =
            inputs.bookmarkedIds.isNotEmpty() || inputs.visits.isNotEmpty() || inputs.followedGalleries.isNotEmpty()
        recommendationMutex.withLock {
            liftDismissals(inputs)
            val shown = latestInputs.value
            latestInputs.value = inputs
            val snapshot = latestSnapshot.value
            // A new pick or an undo is the visitor asking for the list, so it applies at once; saves and hides wait.
            val undone = pendingUndoIds.filterTo(mutableSetOf()) { it !in inputs.taste.dismissals.hiddenExhibitionIds }
            pendingUndoIds -= undone
            val shownIds =
                (_recommendationState.value as? RecommendationUiState.Ready)
                    ?.items
                    ?.mapTo(mutableSetOf()) { it.exhibition.id }
                    .orEmpty()
            val undoNeedsList = undone.any { it !in shownIds }
            val onlySignalsChanged =
                _recommendationState.value is RecommendationUiState.Ready &&
                    snapshot != null &&
                    shown?.exhibitions == inputs.exhibitions &&
                    shown.taste.seed == inputs.taste.seed &&
                    !undoNeedsList
            if (onlySignalsChanged) {
                // Routes and other readers of the snapshot follow the change at once; the list waits.
                latestSnapshot.value =
                    snapshot.copy(
                        bookmarkedIds = inputs.bookmarkedIds,
                        visits = inputs.visits,
                        followedGalleries = inputs.followedGalleries,
                    )
                hasHeldSignals = true
            } else {
                applyRecommendationInputs(inputs)
            }
        }
    }

    private fun applyRecommendationInputs(inputs: RecommendationInputs) {
        hasHeldSignals = false
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
                                    tasteSeed = inputs.taste.seed,
                                    dismissals = inputs.taste.dismissals,
                                ),
                            ).toList()
                    latestSnapshot.value =
                        DiscoverySnapshot(
                            exhibitions = catalogue,
                            index = prepared,
                            bookmarkedIds = inputs.bookmarkedIds,
                            visits = inputs.visits,
                            followedGalleries = inputs.followedGalleries,
                        )
                    _recommendationState.value =
                        if (recommendations.isEmpty()) {
                            RecommendationUiState.Empty
                        } else {
                            recommendationRunId += 1
                            RecommendationUiState.Ready(
                                runId = recommendationRunId,
                                items = recommendations,
                                basis =
                                    RecommendationBasis(
                                        savedCount = inputs.bookmarkedIds.size,
                                        visitedCount = inputs.visits.size,
                                        followedCount = inputs.followedGalleries.size,
                                        pickedCount =
                                            inputs.taste.seed.liveSize(catalogue.mapTo(mutableSetOf(), Exhibition::id)),
                                    ),
                                tasteTerms =
                                    tasteTerms(
                                        anchors = catalogue.tasteAnchors(inputs),
                                        pickedTerms = resolveArtTerms(inputs.taste.seed.termIds, catalogue),
                                        excluded = inputs.taste.dismissals.dismissedTermIds,
                                    ),
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

    /** Venue hours are Korea time, so each build is stamped with the current Korea date and time. */
    private fun RoutePlanningRequest.stampedNow(): RoutePlanningRequest {
        val now = nowProvider().toLocalDateTime(VENUE_TIME_ZONE)
        return copy(visitDate = now.date, startTime = now.time)
    }

    /** Asks for the walking paths between the planned venues; the visitor's start is never sent (spec 093 FR-004). */
    private suspend fun resolveWalkingLegs(route: ExhibitionRouteEstimate) {
        val walking = walkingRoutes ?: return
        val stops =
            route.stops.mapNotNull { exhibition ->
                val latitude = exhibition.latitude ?: return@mapNotNull null
                val longitude = exhibition.longitude ?: return@mapNotNull null
                runCatching { RouteWaypoint(exhibition.id, GeoPoint(latitude, longitude)) }.getOrNull()
            }
        walking.resolve(stops)
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
            bookmarkedIds: Flow<Set<String>>,
            visitRepository: VisitRepository,
            followedGalleryRepository: FollowedGalleryRepository,
            language: StateFlow<AppLanguage>,
            backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
            todayProvider: () -> LocalDate = {
                Clock.System.todayIn(TimeZone.currentSystemDefault())
            },
            nowProvider: () -> Instant = { Clock.System.now() },
            recommender: ExhibitionRecommender = LocalExhibitionRecommender(),
            routePlanner: NeighborhoodRoutePlanner = NeighborhoodRoutePlanner(),
            walkingRoutes: WalkingRouteRepository? = null,
            tastePreferences: TastePreferenceRepository? = null,
            accountHistory: Flow<AccountHistory> = flowOf(AccountHistory.GUEST),
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
                        nowProvider = nowProvider,
                        recommender = recommender,
                        routePlanner = routePlanner,
                        walkingRoutes = walkingRoutes,
                        tastePreferences = tastePreferences,
                        accountHistory = accountHistory,
                    )
                }
            }
    }
}

private class TasteWrite(
    val operation: String,
    val change: suspend TastePreferenceRepository.() -> Unit,
)

/** An account and whether its history has settled, and its archive arrived, since it became the device's account. */
private data class AccountSettle(
    val accountId: String?,
    val settled: Boolean,
    val delivered: Boolean,
)

/** Null while the catalogue is loading or failed: a list that cannot be judged moves no baseline. */
private fun liftSignalsOf(inputs: RecommendationInputs): LiftSignals? {
    val catalogue = (inputs.exhibitions as? ExhibitionListState.Success)?.exhibitions ?: return null
    return LiftSignals(
        catalogue = catalogue,
        bookmarkedIds = inputs.bookmarkedIds,
        visitedIds = inputs.visits.mapTo(mutableSetOf(), ExhibitionVisit::exhibitionId),
        follows = inputs.followedGalleries.associate { it.galleryKey to it.galleryId },
    )
}

private data class RecommendationInputs(
    val exhibitions: ExhibitionListState,
    val bookmarkedIds: Set<String>,
    val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>,
    val taste: TastePickState = TastePickState.EMPTY,
)

private data class DiscoverySnapshot(
    val exhibitions: List<Exhibition>,
    val index: ExhibitionRecommendationIndex,
    val bookmarkedIds: Set<String>,
    val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>,
) {
    /** For You ranks the whole eligible pool from the route origin; other modes need no ranking. */
    fun forYouRelevance(request: RoutePlanningRequest): List<RouteRelevance> {
        if (request.mode != RouteCurationMode.FOR_YOU) return emptyList()
        return index.rankRouteCandidates(
            RouteRelevanceContext(
                bookmarkedExhibitionIds = bookmarkedIds,
                visits = visits,
                followedGalleries = followedGalleries,
                origin = request.origin,
                maxDistanceKm = request.maxRadiusKm,
                today = request.visitDate,
            ),
        )
    }
}

private fun RouteUiState.requestOrNull(): RoutePlanningRequest? =
    when (this) {
        RouteUiState.Idle -> null
        is RouteUiState.Editing -> request
        is RouteUiState.Planning -> request
        is RouteUiState.Ready -> request
        is RouteUiState.Insufficient -> request
        is RouteUiState.Error -> request
    }

/**
 * The exhibitions the visitor saved, visited or picked in the taste sheet that are still in the catalogue;
 * their text describes the taste.
 */
private fun List<Exhibition>.tasteAnchors(inputs: RecommendationInputs): List<Exhibition> {
    val visitedIds = inputs.visits.mapTo(mutableSetOf()) { it.exhibitionId }
    val pickedIds = inputs.taste.seed.exhibitionIds
    return filter { it.id in inputs.bookmarkedIds || it.id in visitedIds || it.id in pickedIds }
}

private val VENUE_TIME_ZONE = TimeZone.of("Asia/Seoul")
private const val RECOMMENDATION_LIMIT = 6
private const val MIN_ROUTE_STOPS = 2
private const val MAX_ROUTE_STOPS = 5
private const val DEFAULT_ROUTE_STOP_COUNT = 3
private const val ROUTE_RADIUS_KM = 5.0
private const val VISIT_MINUTES_PER_STOP = 45
private val INPUT_RETRY_DELAYS_MILLIS = listOf(1_000L, 4_000L)
