package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.map.LocalApproximateRouteLegEstimator
import com.gallr.shared.map.RouteLegEstimator
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.CopyIntoDraftResult
import com.gallr.shared.route.PendingKind
import com.gallr.shared.route.PersonalRoute
import com.gallr.shared.route.PersonalRouteEvaluation
import com.gallr.shared.route.PersonalRouteException
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PublicRouteStops
import com.gallr.shared.route.PublicRouteSummary
import com.gallr.shared.route.RouteEvaluator
import com.gallr.shared.route.RouteReportReason
import com.gallr.shared.route.routeFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The 추천 동선 list as the server ranked it (spec 089 US9, DD7, R8). */
sealed interface PublicRoutesListState {
    data object Loading : PublicRoutesListState

    /** No eligible route: the section is not shown. */
    data object Hidden : PublicRoutesListState

    data object Error : PublicRoutesListState

    data class Loaded(
        val rows: List<PublicRouteSummary>,
    ) : PublicRoutesListState
}

/** Why the preview cannot show or act on a route. */
enum class PublicRouteNotice { NO_LONGER_LISTED, LOAD_FAILED }

/** A snackbar after a copy or report; the failures offer 다시 시도, which repeats the same action (DD12). */
enum class PublicRouteMessage { COPY_FAILED, DRAFT_CHANGED, REPORTED, REPORT_FAILED }

/**
 * A listed route opened read-only (DD1). [laterDay] is set when every stop is open together only from a later day;
 * the stops are then judged for that day (DD21). [isOwn] when the signed-in reader is its author (DD22).
 *
 * [copiedDraftId] and [signInRequested] are one-off requests for the app: open the composer on the copied draft, or
 * show sign-in. [confirmReplace] asks before a copy replaces unsaved stops.
 */
data class PublicRoutePreview(
    val summary: PublicRouteSummary,
    val route: PersonalRoute? = null,
    val loading: Boolean = true,
    val evaluation: PersonalRouteEvaluation? = null,
    val laterDay: LocalDate? = null,
    val isOwn: Boolean = false,
    val notice: PublicRouteNotice? = null,
    val copyBusy: Boolean = false,
    val confirmReplace: Boolean = false,
    val copiedDraftId: String? = null,
    val signInRequested: Boolean = false,
    val message: PublicRouteMessage? = null,
    val reportSheet: Boolean = false,
    val reportBusy: Boolean = false,
    val reported: Boolean = false,
)

data class PublicRoutesUiState(
    val list: PublicRoutesListState = PublicRoutesListState.Loading,
    val expanded: Boolean = false,
    val preview: PublicRoutePreview? = null,
    val today: LocalDate? = null,
) {
    private val rows: List<PublicRouteSummary> get() = (list as? PublicRoutesListState.Loaded)?.rows.orEmpty()

    /** The top three, or all fetched rows once expanded (DD2). */
    val visibleRows: List<PublicRouteSummary> get() = if (expanded) rows else rows.take(COLLAPSED_ROWS)

    val canExpand: Boolean get() = rows.size > COLLAPSED_ROWS
}

/**
 * Readers browse 추천 동선 in the Map route sheet and open a read-only preview (spec 089 US9). The server decides
 * which routes are shown and in what order; this ViewModel shows them as received and judges a preview's stops with
 * the same [RouteEvaluator] as the composer, on [backgroundDispatcher].
 *
 * Copying (US10) counts the copy on the server before the draft changes, and applies it only to the draft the reader
 * confirmed, so a failure never touches the draft and a retry is not counted twice (R9, R13). A signed-out copy is
 * stored as a [PendingKind.COPY] and continues here on the next signed-in state (DD18).
 */
class PublicRoutesViewModel(
    private val routeRepository: PersonalRouteRepository,
    private val draftRepository: PersonalRouteDraftRepository,
    exhibitionsState: StateFlow<ExhibitionListState>,
    private val authState: StateFlow<AuthState>,
    private val clock: Clock = Clock.System,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val legEstimator: RouteLegEstimator = LocalApproximateRouteLegEstimator(),
    private val analytics: RouteAnalytics = RouteAnalytics.None,
) : ViewModel() {
    private val log = AppLog.tagged("PublicRoutes")
    private val list = MutableStateFlow<PublicRoutesListState>(PublicRoutesListState.Loading)
    private val expanded = MutableStateFlow(false)
    private val preview = MutableStateFlow<PublicRoutePreview?>(null)

    /** The draft version the reader agreed to replace, while the confirm is shown. */
    private var confirmedDraft: Pair<String, Long>? = null

    /** Set as soon as a copy is tapped, so repeated taps before the busy state shows make one call. */
    private var copyStarted = false
    private var lastReportReason: RouteReportReason? = null
    private var quietRead: Job? = null

    /** A showing of the sheet not yet counted; it counts once rows are on screen, so a slow read still counts. */
    private var viewPending = false

    val state: StateFlow<PublicRoutesUiState> =
        combine(list, expanded, preview, exhibitionsState, authState) { rows, open, opened, exhibitions, auth ->
            PublicRoutesUiState(
                list = rows,
                expanded = open,
                preview = opened?.let { evaluated(it, exhibitions, auth) },
                today = clock.todayIn(ROUTE_TIME_ZONE),
            )
        }.flowOn(backgroundDispatcher)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = PublicRoutesUiState(),
            )

    init {
        retry()
        viewModelScope.launch {
            authState.collect { auth -> if (auth is AuthState.Authenticated) resumeCopy() }
        }
    }

    /** Loads (or reloads) the ranked list. */
    fun retry() {
        list.value = PublicRoutesListState.Loading
        viewModelScope.launch {
            list.value = loadList()
            countPendingView()
        }
    }

    /**
     * The route sheet opened, once per showing: read the ranking again, keeping the rows on screen until the new
     * ones arrive, so a route approved since the last read appears; a failed read keeps what is shown. The showing
     * is counted once, with the rows then on screen (P12); expanding in place is not a new showing, and a read
     * already running answers a repeated call.
     */
    fun sheetShown() {
        viewPending = true
        // The first load is still running; it counts the showing when it lands.
        if (list.value == PublicRoutesListState.Loading) return
        if (quietRead?.isActive == true) return
        quietRead =
            viewModelScope.launch {
                routeRepository.listPublic().onSuccess { rows -> list.value = rows.asListState() }
                countPendingView()
            }
    }

    private suspend fun countPendingView() {
        if (!viewPending) return
        val rowsShown = PublicRoutesUiState(list = list.value, expanded = expanded.value).visibleRows.size
        if (rowsShown == 0) return
        viewPending = false
        analytics.publicRoutesViewed(rowsShown)
    }

    fun toggleExpanded() {
        expanded.update { !it }
    }

    fun openPreview(summary: PublicRouteSummary) {
        preview.value = PublicRoutePreview(summary = summary)
        viewModelScope.launch { loadStops(summary) }
    }

    /**
     * Reads the opened route's stops into the preview. Returns what was read, or null once the preview shows why it
     * cannot act: the route left the list (and the list is read again) or the read failed.
     */
    private suspend fun loadStops(summary: PublicRouteSummary): PublicRouteStops? {
        val loaded =
            routeRepository.loadPublicStops(summary.id).getOrElse { error ->
                log.warn("public_route_load_failed", error)
                updateOpened(summary.id) { it.copy(loading = false, notice = PublicRouteNotice.LOAD_FAILED) }
                return null
            }
        if (loaded == null) {
            updateOpened(summary.id) { it.copy(loading = false, notice = PublicRouteNotice.NO_LONGER_LISTED) }
            list.value = loadList()
            return null
        }
        updateOpened(summary.id) { it.copy(route = loaded.route, loading = false, isOwn = loaded.isMine) }
        return loaded
    }

    fun closePreview() {
        preview.value = null
        confirmedDraft = null
    }

    /** "내 동선으로 복사": sign-in first, then the replace confirm when the draft has unsaved stops (DD18). */
    fun copy() {
        val opened = preview.value ?: return
        if (copyStarted || opened.copyBusy || opened.route == null || isOwnRoute(opened)) return
        copyStarted = true
        viewModelScope.launch {
            if (authState.value !is AuthState.Authenticated) {
                draftRepository.setPending(PendingKind.COPY, routeId = opened.summary.id)
                updateOpened(opened.summary.id) { it.copy(signInRequested = true) }
                copyStarted = false
                return@launch
            }
            val draft = draftRepository.draft.first()
            if (draft.hasUnsavedStops) {
                confirmedDraft = draft.draftId to draft.revision
                updateOpened(opened.summary.id) { it.copy(confirmReplace = true) }
                copyStarted = false
            } else {
                runCopy(opened.summary, draft.draftId, draft.revision)
            }
        }
    }

    fun confirmReplace() {
        val opened = preview.value ?: return
        val (draftId, revision) = confirmedDraft ?: return
        confirmedDraft = null
        updateOpened(opened.summary.id) { it.copy(confirmReplace = false) }
        copyStarted = true
        viewModelScope.launch { runCopy(opened.summary, draftId, revision) }
    }

    fun cancelReplace() {
        confirmedDraft = null
        preview.update { it?.copy(confirmReplace = false) }
    }

    fun onCopyOpened() {
        preview.update { it?.copy(copiedDraftId = null) }
    }

    fun onSignInRequestHandled() {
        preview.update { it?.copy(signInRequested = false) }
    }

    /** "신고": signed-out readers are asked to sign in first (DD10). */
    fun startReport() {
        if (authState.value !is AuthState.Authenticated) {
            preview.update { it?.copy(signInRequested = true) }
            return
        }
        preview.update { it?.copy(reportSheet = true) }
    }

    fun closeReport() {
        preview.update { it?.copy(reportSheet = false) }
    }

    /**
     * Sends the report. Only an unexpected failure offers 다시 시도: a report already on file reads as reported, a
     * route that left the list shows the notice and reloads the list, and a route the server knows the reader
     * wrote switches the preview to the owner's view, which has no 신고 (DD22).
     */
    fun report(reason: RouteReportReason) {
        val opened = preview.value ?: return
        if (opened.reportBusy || opened.reported) return
        lastReportReason = reason
        updateOpened(opened.summary.id) { it.copy(reportBusy = true, message = null) }
        viewModelScope.launch {
            val error = routeRepository.report(opened.summary.id, reason).exceptionOrNull()
            val settled = { change: (PublicRoutePreview) -> PublicRoutePreview ->
                updateOpened(opened.summary.id) { change(it.copy(reportBusy = false, reportSheet = false)) }
            }
            if (error == null) {
                settled { it.copy(reported = true, message = PublicRouteMessage.REPORTED) }
                return@launch
            }
            when (error.routeFailure()) {
                PersonalRouteFailure.ReportExists -> {
                    settled { it.copy(reported = true) }
                }

                PersonalRouteFailure.ReportOwnRoute -> {
                    settled { it.copy(isOwn = true) }
                }

                PersonalRouteFailure.NotListed -> {
                    settled { it.copy(notice = PublicRouteNotice.NO_LONGER_LISTED) }
                    list.value = loadList()
                }

                else -> {
                    log.warn("route_report_failed", error)
                    settled { it.copy(message = PublicRouteMessage.REPORT_FAILED) }
                }
            }
        }
    }

    /** 다시 시도 on a failure snackbar repeats the same action. */
    fun retryMessage() {
        val shown = preview.value?.message ?: return
        dismissMessage()
        when (shown) {
            PublicRouteMessage.COPY_FAILED, PublicRouteMessage.DRAFT_CHANGED -> copy()
            PublicRouteMessage.REPORT_FAILED -> lastReportReason?.let(::report)
            PublicRouteMessage.REPORTED -> Unit
        }
    }

    fun dismissMessage() {
        preview.update { it?.copy(message = null) }
    }

    private suspend fun runCopy(
        summary: PublicRouteSummary,
        draftId: String,
        revision: Long,
    ) {
        updateOpened(summary.id) { it.copy(copyBusy = true, message = null) }
        routeRepository
            .copyPublic(summary.id)
            .onSuccess { route ->
                val applied = draftRepository.copyIntoDraft(route.name, route.stops, draftId, revision)
                updateOpened(summary.id) {
                    when (applied) {
                        is CopyIntoDraftResult.Applied -> {
                            it.copy(copyBusy = false, copiedDraftId = applied.draftId)
                        }

                        CopyIntoDraftResult.DraftChanged -> {
                            it.copy(copyBusy = false, message = PublicRouteMessage.DRAFT_CHANGED)
                        }
                    }
                }
            }.onFailure { error ->
                if ((error as? PersonalRouteException)?.failure == PersonalRouteFailure.NotListed) {
                    updateOpened(summary.id) { it.copy(copyBusy = false, notice = PublicRouteNotice.NO_LONGER_LISTED) }
                    list.value = loadList()
                } else {
                    log.warn("public_route_copy_failed", error)
                    updateOpened(summary.id) { it.copy(copyBusy = false, message = PublicRouteMessage.COPY_FAILED) }
                }
            }
        copyStarted = false
    }

    /**
     * Continues a copy that waited for sign-in, in the preview it was tapped in; a stale request is dropped. The
     * signed-out read could not tell the reader's own route apart, so the stops are read again first, and an own
     * route shows "내 동선에서 열기" instead of being copied (DD22).
     */
    private suspend fun resumeCopy() {
        val draft = draftRepository.draft.first()
        val pending = draft.pendingAction?.takeIf { it.kind == PendingKind.COPY } ?: return
        draftRepository.clearPending()
        val opened = preview.value ?: return
        if (pending.routeId != opened.summary.id || !pending.isRunnableFor(draft, clock.now())) return
        val refreshed = loadStops(opened.summary) ?: return
        if (!refreshed.isMine) copy()
    }

    private fun updateOpened(
        routeId: String,
        change: (PublicRoutePreview) -> PublicRoutePreview,
    ) {
        preview.update { current -> current?.takeIf { it.summary.id == routeId }?.let(change) ?: current }
    }

    private fun isOwnRoute(opened: PublicRoutePreview): Boolean = opened.isOwn

    private suspend fun loadList(): PublicRoutesListState =
        routeRepository.listPublic().fold(
            onSuccess = { rows -> rows.asListState() },
            onFailure = { error ->
                log.warn("public_routes_load_failed", error)
                PublicRoutesListState.Error
            },
        )

    private fun List<PublicRouteSummary>.asListState(): PublicRoutesListState =
        if (isEmpty()) PublicRoutesListState.Hidden else PublicRoutesListState.Loaded(this)

    private fun signedInAccount(): String? = (authState.value as? AuthState.Authenticated)?.user?.id

    /** Judges the stops for today, or for the first day every stop is open when that is later (DD21). */
    private fun evaluated(
        opened: PublicRoutePreview,
        exhibitions: ExhibitionListState,
        auth: AuthState,
    ): PublicRoutePreview {
        val today = clock.todayIn(ROUTE_TIME_ZONE)
        val laterDay = opened.summary.firstSharedDay.takeIf { it > today }
        val route = opened.route ?: return opened.copy(laterDay = laterDay)
        val catalogue = (exhibitions as? ExhibitionListState.Success)?.exhibitions
        val reference = laterDay?.atStartOfDayIn(ROUTE_TIME_ZONE) ?: clock.now()
        val evaluation =
            catalogue?.let { listed ->
                RouteEvaluator.evaluate(
                    stops = route.stops,
                    exhibitionsById = listed.associateBy { it.id },
                    origin = null,
                    now = reference,
                    zone = ROUTE_TIME_ZONE,
                    legEstimator = legEstimator,
                )
            }
        return opened.copy(laterDay = laterDay, evaluation = evaluation)
    }

    companion object {
        fun factory(
            routeRepository: PersonalRouteRepository,
            draftRepository: PersonalRouteDraftRepository,
            exhibitionsState: StateFlow<ExhibitionListState>,
            authState: StateFlow<AuthState>,
            analytics: RouteAnalytics = RouteAnalytics.None,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PublicRoutesViewModel(
                        routeRepository = routeRepository,
                        draftRepository = draftRepository,
                        exhibitionsState = exhibitionsState,
                        authState = authState,
                        analytics = analytics,
                    )
                }
            }
    }
}

private const val COLLAPSED_ROWS = 3
private const val STOP_TIMEOUT_MILLIS = 5_000L
