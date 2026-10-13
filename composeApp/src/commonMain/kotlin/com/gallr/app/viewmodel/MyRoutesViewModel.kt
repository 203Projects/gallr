package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.PersonalRouteDraftRepository
import com.gallr.shared.repository.PersonalRouteRepository
import com.gallr.shared.route.PersonalRouteDraft
import com.gallr.shared.route.PersonalRouteFailure
import com.gallr.shared.route.PersonalRouteSummary
import com.gallr.shared.route.RouteListingState
import com.gallr.shared.route.routeFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** The unsaved draft, listed first as "작성 중". */
data class DraftRouteRow(
    val name: String,
    val stopCount: Int,
)

/** The owner's saved routes, which exist only for a signed-in account. */
sealed interface SavedRoutesState {
    data object SignedOut : SavedRoutesState

    data object Loading : SavedRoutesState

    data object Error : SavedRoutesState

    data class Loaded(
        val routes: List<PersonalRouteSummary>,
    ) : SavedRoutesState
}

/** [DELETE_REFUSED_REVOKED] explains a delete the server refused because staff took the route down; no retry. */
enum class MyRoutesError { OPEN_FAILED, SHARE_FAILED, DELETE_FAILED, DELETE_REFUSED_REVOKED }

/** The consent dialog before a listing request (DD17); [alsoPublishes] when the route's link is not public yet. */
data class ListingConsent(
    val route: PersonalRouteSummary,
    val alsoPublishes: Boolean,
    val isEditor: Boolean,
    val authorName: String?,
)

/** The snackbar after a listing call (DD12, DD14). */
sealed interface ListingMessage {
    data class Requested(
        val isEditor: Boolean,
    ) : ListingMessage

    /** The call failed; "다시 시도" repeats the same call. */
    data object Failed : ListingMessage

    /** The server refused the call for the route's current state: the list is read again and nothing is retried. */
    data class Refused(
        val reason: ListingRefusal,
    ) : ListingMessage
}

/**
 * Why the server refused a listing call; the row the author acted on no longer matched the route. [GONE] is a route
 * deleted from another device, or no longer this account's, so its row leaves the list.
 */
enum class ListingRefusal { STATE_CHANGED, NOT_PUBLISHED, REVOKED, GONE }

/** Where an open was asked for: a 내 동선 row, or the author's own route in its public preview (DD22). */
enum class OpenOrigin { LIST, PREVIEW }

/** The open the replace-draft dialog is asking about; its failure is reported back to [origin]. */
data class PendingOpen(
    val route: PersonalRouteSummary,
    val origin: OpenOrigin,
)

/**
 * What the 내 동선 list shows (spec 089 US4, DR-D10). [openComposer] and [sharePayload] ask the screen to act once;
 * [confirmOpen] and [confirmDelete] hold the route a dialog is asking about. [routeCount] is the MY tab's count:
 * the saved routes plus a new draft, counting a saved route with unsaved edits once.
 *
 * [previewOpenFailed] is the route whose open from its public preview failed (DD22); the preview shows it, so it
 * never appears in this list.
 */
data class MyRoutesUiState(
    val draftRow: DraftRouteRow? = null,
    val routeCount: Int = 0,
    val saved: SavedRoutesState = SavedRoutesState.Loading,
    val confirmOpen: PendingOpen? = null,
    val confirmDelete: PersonalRouteSummary? = null,
    val confirmNewRoute: Boolean = false,
    val sharePayload: RouteSharePayload? = null,
    val openComposer: Boolean = false,
    val signInRequested: Boolean = false,
    val error: MyRoutesError? = null,
    val previewOpenFailed: String? = null,
    val confirmListing: ListingConsent? = null,
    /** Routes whose listing call is running; their listing menu items are disabled. */
    val listingBusy: Set<String> = emptySet(),
    val listingMessage: ListingMessage? = null,
)

/**
 * The 내 동선 list: the device's unsaved draft and the account's saved routes, with open, share and delete.
 * Opening a saved route replaces the draft, after a confirm when the draft has unsaved stops. Sharing the route
 * that is open in the draft saves its edits first; any other route is published if needed and shared as saved.
 */
class MyRoutesViewModel(
    private val draftRepository: PersonalRouteDraftRepository,
    private val routeRepository: PersonalRouteRepository,
    private val authState: StateFlow<AuthState>,
    private val shareOrchestrator: RouteShareOrchestrator,
    private val analytics: RouteAnalytics = RouteAnalytics.None,
) : ViewModel() {
    private val saved = MutableStateFlow<SavedRoutesState>(SavedRoutesState.Loading)
    private val dialogs = MutableStateFlow(MyRoutesUiState())
    private var failedListing: ListingCall? = null
    private var quietRead: Job? = null

    /** Set when a re-read is asked for while [quietRead] runs; that read is then followed by one more. */
    private var readAgainRequested = false

    /**
     * Counts the opens asked for and the previews left. A preview open's failure is shown only while its number is
     * still the latest: once the preview reported itself left ([previewLeft]) or another open was asked for, nobody
     * is waiting for that answer, and a late failure would otherwise greet the route's next preview. The number is
     * claimed when the open is asked for, before any read suspends, so a leave during the draft read counts too.
     */
    private var openGeneration = 0

    /** The number of the open the replace-draft dialog is asking about, carried to [confirmOpen]. */
    private var pendingOpenGeneration = 0

    val state: StateFlow<MyRoutesUiState> =
        combine(draftRepository.draft, saved, dialogs) { draft, savedRoutes, local ->
            local.copy(
                draftRow = draft.unsavedRow(),
                routeCount = routeCount(draft, savedRoutes),
                saved = savedRoutes,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = MyRoutesUiState(),
        )

    init {
        viewModelScope.launch {
            authState
                .map { it is AuthState.Authenticated }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        // A save in the composer changes the server's list, so read it again once the draft is acknowledged.
        viewModelScope.launch {
            draftRepository.draft
                .map { draft -> draft.savedRevision?.let { draft.route.id to it } }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .collect { refresh() }
        }
    }

    fun refresh() {
        // A quiet read still running would deliver rows older than this one's, or another account's, after it.
        quietRead?.cancel()
        if (authState.value !is AuthState.Authenticated) {
            saved.value = SavedRoutesState.SignedOut
            return
        }
        saved.value = SavedRoutesState.Loading
        viewModelScope.launch {
            val result =
                routeRepository
                    .listMine()
                    .fold(onSuccess = { SavedRoutesState.Loaded(it) }, onFailure = { SavedRoutesState.Error })
            if (authState.value is AuthState.Authenticated) saved.value = result
        }
    }

    /**
     * The list was shown again: read it once more so a staff decision or an edit elsewhere appears (DD13). Rows
     * already on screen stay until the new ones arrive; a failed read keeps them. Called once per showing of the
     * route sheet or the MY tab section; a read already running answers a repeated call.
     */
    fun sectionShown() {
        if (authState.value !is AuthState.Authenticated) return
        when (saved.value) {
            SavedRoutesState.Loading -> Unit
            is SavedRoutesState.Loaded -> readAgainKeepingRows()
            SavedRoutesState.Error, SavedRoutesState.SignedOut -> refresh()
        }
    }

    /**
     * Reads the list again without dropping the rows on screen. A read already running answers the call, unless the
     * caller learned the rows changed ([afterRunningRead]: the server refused a call for a stale row). That read may
     * predate the change and deliver the stale row, so one more read follows it.
     */
    private fun readAgainKeepingRows(afterRunningRead: Boolean = false) {
        if (quietRead?.isActive == true) {
            if (afterRunningRead) readAgainRequested = true
            return
        }
        quietRead =
            viewModelScope.launch {
                do {
                    readAgainRequested = false
                    routeRepository.listMine().onSuccess { rows ->
                        if (authState.value is AuthState.Authenticated) saved.value = SavedRoutesState.Loaded(rows)
                    }
                } while (readAgainRequested)
            }
    }

    fun open(route: PersonalRouteSummary) {
        openFrom(route, OpenOrigin.LIST)
    }

    /**
     * Opens the reader's own listed route from its public preview (DD22) through the same path as a 내 동선 row,
     * including the replace-draft confirm. Only the id is needed to load it; the row is used when already listed.
     */
    fun openOwn(routeId: String) {
        dialogs.update { it.copy(previewOpenFailed = null) }
        val listed = currentRow(routeId)
        openFrom(
            listed ?: PersonalRouteSummary(
                id = routeId,
                name = "",
                stopCount = 0,
                isPublished = true,
                isRevoked = false,
                updatedAt = Instant.DISTANT_PAST,
            ),
            OpenOrigin.PREVIEW,
        )
    }

    /** The preview showed the failure (or its 다시 시도 was taken); the flag is cleared so it is not shown twice. */
    fun dismissPreviewOpenFailure() {
        dialogs.update { it.copy(previewOpenFailed = null) }
    }

    /** The preview was left: nobody waits for an open still running, so its failure must not greet the next preview. */
    fun previewLeft() {
        openGeneration += 1
        dialogs.update { it.copy(previewOpenFailed = null) }
    }

    private fun openFrom(
        route: PersonalRouteSummary,
        origin: OpenOrigin,
    ) {
        // Claimed before the draft read suspends, so a preview left meanwhile outranks this open's failure.
        val generation = ++openGeneration
        viewModelScope.launch {
            val draft = draftRepository.draft.first()
            when {
                draft.route.id == route.id -> {
                    dialogs.update { it.copy(openComposer = true) }
                }

                draft.hasUnsavedStops -> {
                    pendingOpenGeneration = generation
                    dialogs.update { it.copy(confirmOpen = PendingOpen(route, origin)) }
                }

                else -> {
                    load(route, origin, generation)
                }
            }
        }
    }

    /** Starts an empty draft, after a confirm when the current draft has unsaved stops. */
    fun startNewRoute() {
        viewModelScope.launch {
            if (draftRepository.draft.first().hasUnsavedStops) {
                dialogs.update { it.copy(confirmNewRoute = true) }
            } else {
                newDraft()
            }
        }
    }

    fun confirmNewRoute() {
        dialogs.update { it.copy(confirmNewRoute = false) }
        viewModelScope.launch { newDraft() }
    }

    /** Declines a replacement and returns to the draft as it is. */
    fun keepEditing() {
        dialogs.update { it.copy(confirmNewRoute = false, confirmOpen = null, openComposer = true) }
    }

    fun confirmOpen() {
        val pending = dialogs.value.confirmOpen ?: return
        dialogs.update { it.copy(confirmOpen = null) }
        viewModelScope.launch { load(pending.route, pending.origin, pendingOpenGeneration) }
    }

    fun share(route: PersonalRouteSummary) {
        viewModelScope.launch {
            val draft = draftRepository.draft.first()
            val outcome =
                if (draft.route.id == route.id) {
                    shareOrchestrator.share(authState.value)
                } else {
                    shareOrchestrator.shareSaved(route.id, authState.value)
                }
            when (outcome) {
                is RouteActionOutcome.ReadyToShare -> {
                    // Sharing publishes the route, so its row reads 링크 공개 from now on.
                    updateRow(route.id) { it.copy(isPublished = true) }
                    dialogs.update { it.copy(sharePayload = outcome.payload) }
                }

                RouteActionOutcome.SignInRequired -> {
                    dialogs.update { it.copy(signInRequested = true) }
                }

                else -> {
                    dialogs.update { it.copy(error = MyRoutesError.SHARE_FAILED) }
                }
            }
        }
    }

    fun delete(route: PersonalRouteSummary) {
        dialogs.update { it.copy(confirmDelete = route) }
    }

    fun confirmDelete() {
        val route = dialogs.value.confirmDelete ?: return
        dialogs.update { it.copy(confirmDelete = null) }
        viewModelScope.launch {
            routeRepository
                .delete(route.id)
                .onSuccess {
                    forgetRoute(route.id)
                    refresh()
                }.onFailure { error -> deleteFailed(route.id, error.routeFailure()) }
        }
    }

    /**
     * A route deleted from another device, or no longer this account's, is as gone as one deleted here: its row
     * leaves quietly, with no error. A route staff took down cannot be deleted; its row is read again so it says so
     * and stops offering 삭제. Anything else is a failure to retry.
     */
    private suspend fun deleteFailed(
        routeId: String,
        failure: PersonalRouteFailure,
    ) {
        when (failure) {
            PersonalRouteFailure.NotFound, PersonalRouteFailure.NotOwner -> {
                forgetRoute(routeId)
                dropRow(routeId)
                readAgainKeepingRows(afterRunningRead = true)
            }

            PersonalRouteFailure.Revoked -> {
                readAgainKeepingRows(afterRunningRead = true)
                dialogs.update { it.copy(error = MyRoutesError.DELETE_REFUSED_REVOKED) }
            }

            else -> {
                dialogs.update { it.copy(error = MyRoutesError.DELETE_FAILED) }
            }
        }
    }

    /** The draft keeps its stops but no longer points at a route that is gone. */
    private suspend fun forgetRoute(routeId: String) {
        val draft = draftRepository.draft.first()
        if (draft.route.id == routeId) draftRepository.detach()
    }

    /** Takes a route that is gone off the list at once, so it leaves even when the list cannot be read again. */
    private fun dropRow(routeId: String) {
        saved.update { current ->
            if (current is SavedRoutesState.Loaded) {
                SavedRoutesState.Loaded(current.routes.filterNot { it.id == routeId })
            } else {
                current
            }
        }
    }

    fun dismissDialogs() {
        dialogs.update {
            it.copy(confirmOpen = null, confirmDelete = null, confirmNewRoute = false, confirmListing = null)
        }
    }

    /** Asks for consent before listing (DD17); nothing is sent until [confirmListing]. */
    fun requestListing(route: PersonalRouteSummary) {
        val author = (authState.value as? AuthState.Authenticated)?.user?.displayName
        val consent =
            ListingConsent(
                route = route,
                alsoPublishes = !route.isPublished,
                isEditor = route.authorIsEditor,
                authorName = author?.trim()?.takeIf(String::isNotEmpty),
            )
        dialogs.update { it.copy(confirmListing = consent) }
    }

    fun confirmListing() {
        val consent = dialogs.value.confirmListing ?: return
        dialogs.update { it.copy(confirmListing = null) }
        runListing(ListingCall(consent.route, ListingCall.Kind.REQUEST))
    }

    /** Withdraws a request or takes an approved route off the list, without a confirm. */
    fun withdrawListing(route: PersonalRouteSummary) {
        runListing(ListingCall(route, ListingCall.Kind.WITHDRAW))
    }

    /** Repeats the listing call that just failed. */
    fun retryListing() {
        val call = failedListing ?: return
        dialogs.update { it.copy(listingMessage = null) }
        runListing(call)
    }

    fun dismissListingMessage() {
        dialogs.update { it.copy(listingMessage = null) }
    }

    private fun runListing(call: ListingCall) {
        val id = call.route.id
        if (id in dialogs.value.listingBusy) return
        dialogs.update { it.copy(listingBusy = it.listingBusy + id) }
        viewModelScope.launch {
            val result =
                when (call.kind) {
                    ListingCall.Kind.REQUEST -> requestAfterPublishing(call.route)
                    ListingCall.Kind.WITHDRAW -> routeRepository.withdrawListing(id)
                }
            result
                .onSuccess { row ->
                    failedListing = null
                    replaceRow(row)
                    val message =
                        when (call.kind) {
                            ListingCall.Kind.REQUEST -> {
                                ListingMessage.Requested(isEditor = row.listingState == RouteListingState.Approved)
                            }

                            ListingCall.Kind.WITHDRAW -> {
                                null
                            }
                        }
                    dialogs.update { it.copy(listingBusy = it.listingBusy - id, listingMessage = message) }
                }.onFailure { error ->
                    val refusal = error.routeFailure().listingRefusal()
                    if (refusal == null) {
                        failedListing = call
                    } else {
                        // The row the author acted on was stale: show the server's state (a route that is gone leaves
                        // the list), with nothing to retry.
                        failedListing = null
                        if (refusal == ListingRefusal.GONE) dropRow(id)
                        readAgainKeepingRows(afterRunningRead = true)
                    }
                    val message = refusal?.let(ListingMessage::Refused) ?: ListingMessage.Failed
                    dialogs.update { it.copy(listingBusy = it.listingBusy - id, listingMessage = message) }
                }
        }
    }

    private suspend fun requestAfterPublishing(route: PersonalRouteSummary): Result<PersonalRouteSummary> {
        publishIfNeeded(route).onFailure { return Result.failure(it) }
        return routeRepository.requestListing(route.id)
    }

    /**
     * "공개하고 목록에 올리기" publishes first, once: a retry reads the row as it is now. The row and, when it is the
     * open one, the draft learn the route is public, and the first publish is counted (E-D6).
     */
    private suspend fun publishIfNeeded(route: PersonalRouteSummary): Result<Unit> {
        val current = currentRow(route.id) ?: route
        if (current.isPublished) return Result.success(Unit)
        return routeRepository.publish(route.id).map { published ->
            updateRow(route.id) { it.copy(isPublished = true) }
            val draft = draftRepository.draft.first()
            if (draft.route.id == route.id) draftRepository.markPublished(draft.draftId, published)
            analytics.published(published.stops.size)
        }
    }

    private fun currentRow(routeId: String): PersonalRouteSummary? =
        (saved.value as? SavedRoutesState.Loaded)?.routes?.firstOrNull { it.id == routeId }

    private fun replaceRow(row: PersonalRouteSummary) {
        updateRow(row.id) { row }
    }

    private fun updateRow(
        routeId: String,
        change: (PersonalRouteSummary) -> PersonalRouteSummary,
    ) {
        val loaded = saved.value as? SavedRoutesState.Loaded ?: return
        saved.value = SavedRoutesState.Loaded(loaded.routes.map { if (it.id == routeId) change(it) else it })
    }

    private data class ListingCall(
        val route: PersonalRouteSummary,
        val kind: Kind,
    ) {
        enum class Kind { REQUEST, WITHDRAW }
    }

    fun dismissError() {
        dialogs.update { it.copy(error = null) }
    }

    fun onComposerOpened() {
        dialogs.update { it.copy(openComposer = false) }
    }

    fun onShareSheetShown() {
        val payload = dialogs.value.sharePayload ?: return
        dialogs.update { it.copy(sharePayload = null) }
        viewModelScope.launch { shareOrchestrator.shareOpened(payload) }
    }

    fun onSignInRequestHandled() {
        dialogs.update { it.copy(signInRequested = false) }
    }

    private suspend fun newDraft() {
        draftRepository.clear()
        dialogs.update { it.copy(openComposer = true) }
    }

    private suspend fun load(
        route: PersonalRouteSummary,
        origin: OpenOrigin,
        generation: Int,
    ) {
        val account = (authState.value as? AuthState.Authenticated)?.user?.id
        routeRepository
            .loadMine(route.id)
            .onSuccess { loaded ->
                draftRepository.replace(loaded, ownerAccountId = account)
                dialogs.update { it.copy(openComposer = true) }
            }.onFailure { reportOpenFailure(route.id, origin, generation) }
    }

    /** A list open's failure always shows on the list; a preview open's only while that open is still the latest. */
    private fun reportOpenFailure(
        routeId: String,
        origin: OpenOrigin,
        generation: Int,
    ) {
        when (origin) {
            OpenOrigin.LIST -> {
                dialogs.update { it.copy(error = MyRoutesError.OPEN_FAILED) }
            }

            OpenOrigin.PREVIEW -> {
                if (generation == openGeneration) dialogs.update { it.copy(previewOpenFailed = routeId) }
            }
        }
    }

    companion object {
        fun factory(
            draftRepository: PersonalRouteDraftRepository,
            routeRepository: PersonalRouteRepository,
            authState: StateFlow<AuthState>,
            analytics: RouteAnalytics = RouteAnalytics.None,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MyRoutesViewModel(
                        draftRepository = draftRepository,
                        routeRepository = routeRepository,
                        authState = authState,
                        shareOrchestrator =
                            RouteShareOrchestrator(
                                draftRepository = draftRepository,
                                routeRepository = routeRepository,
                                analytics = analytics,
                            ),
                        analytics = analytics,
                    )
                }
            }
    }
}

private fun PersonalRouteFailure.listingRefusal(): ListingRefusal? =
    when (this) {
        PersonalRouteFailure.ListingInvalidTransition -> ListingRefusal.STATE_CHANGED
        PersonalRouteFailure.ListingRequiresPublished -> ListingRefusal.NOT_PUBLISHED
        PersonalRouteFailure.Revoked -> ListingRefusal.REVOKED
        PersonalRouteFailure.NotFound, PersonalRouteFailure.NotOwner -> ListingRefusal.GONE
        else -> null
    }

private fun PersonalRouteDraft.unsavedRow(): DraftRouteRow? =
    if (hasUnsavedStops) DraftRouteRow(route.name, route.stops.size) else null

private fun routeCount(
    draft: PersonalRouteDraft,
    saved: SavedRoutesState,
): Int {
    val savedRoutes = (saved as? SavedRoutesState.Loaded)?.routes.orEmpty()
    val savedIds = savedRoutes.mapTo(mutableSetOf()) { it.id }
    val draftIsNewRoute = draft.hasUnsavedStops && draft.route.id !in savedIds
    return savedIds.size + if (draftIsNewRoute) 1 else 0
}

private const val STOP_TIMEOUT_MILLIS = 5_000L
