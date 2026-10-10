package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.app.ui.tabs.map.LocationPermissionStatus
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.map.LocalApproximateRouteLegEstimator
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.AppendResult
import com.gallr.shared.route.MIN_ROUTE_STOPS
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PersonalRouteStop
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteEvaluator
import com.gallr.shared.route.RouteListingState
import com.gallr.shared.route.RouteSaveProblem
import com.gallr.shared.route.UndoResult
import com.gallr.shared.route.UndoToken
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** A one-off message the composer shows in its snackbar. */
sealed interface ComposerMessage {
    /** A stop was removed; [token] restores it until the draft changes again. */
    data class Removed(
        val token: UndoToken,
    ) : ComposerMessage

    /** An undo could not restore the stop (RO4). */
    data class UndoFailed(
        val result: UndoResult,
    ) : ComposerMessage
}

/** Whether the server holds what the author sees; null until the route has been saved once (DR-D7). */
enum class ComposerSaveStatus { SAVED, UNSAVED }

/**
 * What the route composer shows (spec 089 US1, US2). [evaluation] is null while the draft is empty or the catalogue
 * has not loaded, since stops cannot be judged without it. [sharePayload] asks the screen to open the share sheet
 * once; [signInRequested] asks the app to show sign-in once.
 */
data class RouteComposerUiState(
    /** Identifies the draft on screen; it changes when another draft replaces it. */
    val draftId: String = "",
    val name: String = "",
    val stops: List<PersonalRouteStop> = emptyList(),
    val evaluation: PersonalRouteEvaluation? = null,
    val today: LocalDate,
    val hasRemoteRoute: Boolean = false,
    val isPublished: Boolean = false,
    val isSaved: Boolean = false,
    val nameProblem: RouteSaveProblem? = null,
    val showStartFromLocation: Boolean = false,
    val usesDeviceOrigin: Boolean = false,
    val message: ComposerMessage? = null,
    val authorName: String? = null,
    val isSaving: Boolean = false,
    val actionError: RouteActionError? = null,
    val showNameError: Boolean = false,
    val shareReady: Boolean = false,
    val sharePayload: RouteSharePayload? = null,
    val signInRequested: Boolean = false,
    /** A non-editor's listed or requested route with unsaved changes: saving sends it back to review (DD15). */
    val listingWarning: Boolean = false,
    /** The draft is a just-copied listed route, not yet edited or saved (DD9). */
    val copiedNote: Boolean = false,
) {
    val canSave: Boolean get() = stops.size >= MIN_ROUTE_STOPS && !isSaving

    val canShare: Boolean get() = canSave

    /** Starting another draft would discard stops that were never saved, so the author must confirm. */
    val replacingNeedsConfirmation: Boolean get() = stops.isNotEmpty() && !isSaved

    val saveStatus: ComposerSaveStatus?
        get() =
            when {
                !hasRemoteRoute -> null
                isSaved -> ComposerSaveStatus.SAVED
                else -> ComposerSaveStatus.UNSAVED
            }

    /** Stops the last save refused: no longer listed, or without a location (E-D18, RO5). */
    val blockedStopIds: Set<String>
        get() =
            when (val error = actionError) {
                is RouteActionError.BlockedStops -> error.exhibitionIds.toSet()
                is RouteActionError.MissingLocation -> error.exhibitionIds.toSet()
                else -> emptySet()
            }
}

/**
 * Composes the device's route draft. Every edit goes through [PersonalRouteDraftRepository], and the state follows
 * the observed draft, so a stop added from another screen appears here without a reload (RR3). The route is
 * evaluated in the author's order on [backgroundDispatcher] against the catalogue, with [clock] in Seoul time.
 *
 * 저장 and 공유 run through [RouteShareOrchestrator]. A save or share interrupted by sign-in resumes on the next
 * signed-in state, including after an app restart, because this ViewModel watches [authState] for as long as it
 * lives (RR1, RO2).
 *
 * Location is never requested here: the screen reports the permission status and any device location through
 * [updateLocation], and a device location is used only while permission is granted (FR-008, RO6).
 */
class PersonalRouteComposerViewModel(
    private val draftRepository: PersonalRouteDraftRepository,
    exhibitionsState: StateFlow<ExhibitionListState>,
    val language: StateFlow<AppLanguage>,
    private val authState: StateFlow<AuthState>,
    private val shareOrchestrator: RouteShareOrchestrator,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
    private val legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
    private val analytics: RouteAnalytics = RouteAnalytics.None,
    /** Reads the open route's public-list row; null leaves the listing warning off (spec 089 US7). */
    private val routeRepository: PersonalRouteRepository? = null,
) : ViewModel() {
    private val log = AppLog.tagged("PersonalRouteComposer")
    private val location = MutableStateFlow(LocationInput())
    private val message = MutableStateFlow<ComposerMessage?>(null)
    private val action = MutableStateFlow(ActionState())
    private val actionMutex = Mutex()
    private val removal = Mutex()
    private val listing = MutableStateFlow<PersonalRouteSummary?>(null)

    val state: StateFlow<RouteComposerUiState> =
        combine(
            combine(draftRepository.draft, exhibitionsState, location, ::Triple),
            message,
            action,
            authState,
            listing,
        ) { (draft, exhibitions, place), shown, actionState, auth, listed ->
            uiState(draft, exhibitions, place, shown, actionState, auth).copy(
                listingWarning = listed.warnsBeforeSaving(draft),
            )
        }.flowOn(backgroundDispatcher)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = RouteComposerUiState(today = clock.todayIn(ROUTE_TIME_ZONE)),
            )

    init {
        viewModelScope.launch {
            authState.collect { auth ->
                if (auth is AuthState.Authenticated) runAction { shareOrchestrator.resumePending(auth) }
            }
        }
        // Read the row again when the route becomes unsaved or sign-in becomes ready, so the warning does not
        // depend on a read made before sign-in was ready, or before staff decided.
        viewModelScope.launch {
            combine(draftRepository.draft, authState) { draft, auth ->
                ListingReadKey(
                    routeId = draft.route.id.takeIf { draft.hasRemoteRoute },
                    isSaved = draft.isSaved,
                    signedIn = auth is AuthState.Authenticated,
                )
            }.distinctUntilChanged()
                .collect { key -> refreshListing(key.routeId) }
        }
    }

    /** Reads the open route's row once; a failure leaves the warning off rather than guessing. */
    private suspend fun refreshListing(routeId: String?) {
        val repository = routeRepository
        listing.value =
            if (routeId == null || repository == null) {
                null
            } else {
                repository.listMine().getOrNull()?.firstOrNull { it.id == routeId }
            }
    }

    fun save() {
        viewModelScope.launch { runAction { shareOrchestrator.save(authState.value) } }
    }

    fun share() {
        viewModelScope.launch { runAction { shareOrchestrator.share(authState.value) } }
    }

    /** Removes the stops the server refused, then saves again (E-D18). */
    fun removeBlockedStopsAndSave() {
        viewModelScope.launch {
            val blocked = state.value.blockedStopIds
            action.update { it.copy(error = null) }
            val draft = draftRepository.draft.first()
            val stops = draft.route.stops
            val refusedPositions = stops.indices.filter { stops[it].exhibitionId in blocked }
            // Remove from the end so earlier positions stay valid.
            refusedPositions.asReversed().forEach { position -> draftRepository.remove(position) }
            runAction { shareOrchestrator.save(authState.value) }
        }
    }

    fun onShareSheetShown() {
        val payload = action.value.sharePayload ?: return
        action.update { it.copy(sharePayload = null) }
        viewModelScope.launch { shareOrchestrator.shareOpened(payload) }
    }

    fun onSignInRequestHandled() {
        action.update { it.copy(signInRequested = false) }
    }

    /** Shows "복사했어요 · 저장하면 내 동선에 남아요" while [draftId] stays as copied (DD9). */
    fun noteCopied(draftId: String) {
        viewModelScope.launch {
            val draft = draftRepository.draft.first()
            if (draft.draftId == draftId) action.update { it.copy(copiedDraft = draft.draftId to draft.revision) }
        }
    }

    /** The author left sign-in without signing in; the waiting action is dropped. */
    fun cancelSignIn() {
        viewModelScope.launch { shareOrchestrator.cancelPending() }
    }

    /** Adds picked exhibitions in the order picked; the repository refuses duplicates and an eleventh stop. */
    fun append(exhibitions: List<Exhibition>) {
        viewModelScope.launch {
            val before = draftRepository.draft.first()
            val wasEmpty = before.route.stops.isEmpty()
            val added = exhibitions.map { draftRepository.append(it) }.any { it is AppendResult.Added }
            if (wasEmpty && added) analytics.draftStarted()
        }
    }

    /** Starts a new draft from a planner route's stops in its order, for the author to edit (FR-003). */
    fun startFromPlanner(exhibitions: List<Exhibition>) {
        viewModelScope.launch {
            val stops = exhibitions.mapNotNull(Exhibition::toRouteStop)
            draftRepository.seed(stops)
            if (stops.isNotEmpty()) analytics.draftStarted()
        }
    }

    fun rename(name: String) {
        viewModelScope.launch { draftRepository.rename(name) }
    }

    /** Commits one reorder; the screen calls this once when a drag is dropped, not while it moves (RR3). */
    fun move(
        from: Int,
        to: Int,
    ) {
        if (from == to) return
        viewModelScope.launch { draftRepository.move(from, to) }
    }

    /** Removes run one at a time, so a second tap for a stop the first tap already removed changes nothing. */
    fun remove(position: Int) {
        viewModelScope.launch {
            removal.withLock {
                val draft = draftRepository.draft.first()
                if (position !in draft.route.stops.indices) return@withLock
                message.value = ComposerMessage.Removed(draftRepository.remove(position))
            }
        }
    }

    /** Restores the most recently removed stop when the removal message is still showing. */
    fun undo() {
        val removed = message.value as? ComposerMessage.Removed ?: return
        viewModelScope.launch {
            val result = draftRepository.undo(removed.token)
            message.value =
                when (result) {
                    UndoResult.Full, UndoResult.Duplicate -> ComposerMessage.UndoFailed(result)
                    UndoResult.Restored, UndoResult.Expired -> null
                }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /** Reports the platform permission and, when known, the device location; the latter is ignored unless granted. */
    fun updateLocation(
        status: LocationPermissionStatus,
        origin: GeoPoint?,
    ) {
        location.value = LocationInput(status, origin)
    }

    private suspend fun runAction(block: suspend () -> RouteActionOutcome?) {
        if (!actionMutex.tryLock()) return
        try {
            action.update {
                it.copy(
                    isSaving = true,
                    error = null,
                    showNameError = false,
                    shareReadyDraft = null,
                    sharePayload = null,
                )
            }
            val outcome =
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.warn("route_action_failed", error)
                    RouteActionOutcome.Failed(RouteActionError.SaveFailed)
                }
            val draft = draftRepository.draft.first()
            action.update { applied(it.copy(isSaving = false), outcome, draft) }
            // A save can send a listed route back to review, so read its row again.
            if (outcome is RouteActionOutcome.Saved || outcome is RouteActionOutcome.ReadyToShare) {
                refreshListing(draft.route.id.takeIf { draft.hasRemoteRoute })
            }
        } finally {
            actionMutex.unlock()
        }
    }

    private fun applied(
        current: ActionState,
        outcome: RouteActionOutcome?,
        draft: PersonalRouteDraft,
    ): ActionState =
        when (outcome) {
            null, is RouteActionOutcome.Saved -> {
                current
            }

            RouteActionOutcome.SignInRequired -> {
                current.copy(signInRequested = true)
            }

            is RouteActionOutcome.ReadyToShare -> {
                if (outcome.resumed) {
                    current.copy(shareReadyDraft = draft.draftId to draft.revision)
                } else {
                    current.copy(sharePayload = outcome.payload)
                }
            }

            is RouteActionOutcome.Failed -> {
                val error = outcome.error
                if (error is RouteActionError.Invalid) {
                    current.copy(showNameError = error.problem.isNameProblem())
                } else {
                    current.copy(error = error)
                }
            }
        }

    private fun uiState(
        draft: PersonalRouteDraft,
        exhibitions: ExhibitionListState,
        place: LocationInput,
        shown: ComposerMessage?,
        actionState: ActionState,
        auth: AuthState,
    ): RouteComposerUiState {
        val now = clock.now()
        val origin = place.origin.takeIf { place.status == LocationPermissionStatus.GRANTED }
        val catalogue = (exhibitions as? ExhibitionListState.Success)?.exhibitions
        val evaluation =
            catalogue?.let { listed ->
                RouteEvaluator.evaluate(
                    stops = draft.route.stops,
                    exhibitionsById = listed.associateBy { it.id },
                    origin = origin,
                    now = now,
                    zone = ROUTE_TIME_ZONE,
                    legEstimator = legEstimator,
                )
            }
        return RouteComposerUiState(
            draftId = draft.draftId,
            name = draft.route.name,
            stops = draft.route.stops,
            evaluation = evaluation,
            today = clock.todayIn(ROUTE_TIME_ZONE),
            hasRemoteRoute = draft.hasRemoteRoute,
            isPublished = draft.route.isPublished,
            isSaved = draft.isSaved,
            nameProblem =
                draft.route.saveProblem()?.takeIf { it.isNameProblem() },
            showStartFromLocation = place.status == LocationPermissionStatus.CAN_ASK,
            usesDeviceOrigin = origin != null,
            message = shown,
            authorName = (auth as? AuthState.Authenticated)?.user?.displayName,
            isSaving = actionState.isSaving,
            actionError = actionState.error,
            showNameError = actionState.showNameError,
            shareReady = actionState.shareReadyDraft == (draft.draftId to draft.revision),
            sharePayload = actionState.sharePayload,
            signInRequested = actionState.signInRequested,
            copiedNote =
                actionState.copiedDraft == (draft.draftId to draft.revision) && !draft.isSaved,
        )
    }

    /** Save and share progress and their one-off results. */
    private data class ActionState(
        val isSaving: Boolean = false,
        val error: RouteActionError? = null,
        val showNameError: Boolean = false,
        /** The draft version a resumed share published; "공유 준비됐어요" shows only while the draft is still it. */
        val shareReadyDraft: Pair<String, Long>? = null,
        val sharePayload: RouteSharePayload? = null,
        val signInRequested: Boolean = false,
        /** The copied draft's id and revision; the copied note shows only while the draft is still that version. */
        val copiedDraft: Pair<String, Long>? = null,
    )

    /** What decides when the open route's public-list row is read again. */
    private data class ListingReadKey(
        val routeId: String?,
        val isSaved: Boolean,
        val signedIn: Boolean,
    )

    /** Location as last reported by the screen; null [status] means it has not reported yet. */
    private data class LocationInput(
        val status: LocationPermissionStatus? = null,
        val origin: GeoPoint? = null,
    )

    companion object {
        fun factory(
            draftRepository: PersonalRouteDraftRepository,
            routeRepository: PersonalRouteRepository,
            exhibitionsState: StateFlow<ExhibitionListState>,
            language: StateFlow<AppLanguage>,
            authState: StateFlow<AuthState>,
            analytics: RouteAnalytics = RouteAnalytics.None,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PersonalRouteComposerViewModel(
                        draftRepository = draftRepository,
                        exhibitionsState = exhibitionsState,
                        language = language,
                        authState = authState,
                        shareOrchestrator =
                            RouteShareOrchestrator(
                                draftRepository = draftRepository,
                                routeRepository = routeRepository,
                                analytics = analytics,
                            ),
                        analytics = analytics,
                        routeRepository = routeRepository,
                    )
                }
            }
    }
}

private fun PersonalRouteSummary?.warnsBeforeSaving(draft: PersonalRouteDraft): Boolean =
    this != null &&
        id == draft.route.id &&
        draft.hasRemoteRoute &&
        !draft.isSaved &&
        !authorIsEditor &&
        (listingState == RouteListingState.Requested || listingState == RouteListingState.Approved)

private fun RouteSaveProblem.isNameProblem(): Boolean =
    this == RouteSaveProblem.NAME_EMPTY || this == RouteSaveProblem.NAME_TOO_LONG

/** Routes are planned in Seoul time whatever the device's zone (spec 089). */
val ROUTE_TIME_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

private const val STOP_TIMEOUT_MILLIS = 5_000L
