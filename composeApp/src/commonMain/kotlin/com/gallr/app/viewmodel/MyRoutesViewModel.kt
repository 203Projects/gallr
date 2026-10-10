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

enum class MyRoutesError { OPEN_FAILED, SHARE_FAILED, DELETE_FAILED }

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

/** Why the server refused a listing call; the row the author acted on no longer matched the route. */
enum class ListingRefusal { STATE_CHANGED, NOT_PUBLISHED, REVOKED }

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
    val confirmOpen: PersonalRouteSummary? = null,
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

    /** Where the open in progress was asked for; its failure is reported there. */
    private var openOrigin = OpenOrigin.LIST

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
        if (authState.value !is AuthState.Authenticated) {
            saved.value = SavedRoutesState.SignedOut
            return
        }
        saved.value = SavedRoutesState.Loading
        viewModelScope.launch {
            saved.value =
                routeRepository
                    .listMine()
                    .fold(onSuccess = { SavedRoutesState.Loaded(it) }, onFailure = { SavedRoutesState.Error })
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

    private fun readAgainKeepingRows() {
        if (quietRead?.isActive == true) return
        quietRead =
            viewModelScope.launch {
                routeRepository.listMine().onSuccess { saved.value = SavedRoutesState.Loaded(it) }
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

    fun dismissPreviewOpenFailure() {
        dialogs.update { it.copy(previewOpenFailed = null) }
    }

    private fun openFrom(
        route: PersonalRouteSummary,
        origin: OpenOrigin,
    ) {
        openOrigin = origin
        viewModelScope.launch {
            val draft = draftRepository.draft.first()
            when {
                draft.route.id == route.id -> dialogs.update { it.copy(openComposer = true) }
                draft.hasUnsavedStops -> dialogs.update { it.copy(confirmOpen = route) }
                else -> load(route)
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
        val route = dialogs.value.confirmOpen ?: return
        dialogs.update { it.copy(confirmOpen = null) }
        viewModelScope.launch { load(route) }
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
                    // The draft keeps its stops but no longer points at the deleted route.
                    val draft = draftRepository.draft.first()
                    if (draft.route.id == route.id) draftRepository.detach()
                    refresh()
                }.onFailure { dialogs.update { it.copy(error = MyRoutesError.DELETE_FAILED) } }
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
                        // The row the author acted on was stale: show the server's state, with nothing to retry.
                        failedListing = null
                        readAgainKeepingRows()
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

    private enum class OpenOrigin { LIST, PREVIEW }

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

    private suspend fun load(route: PersonalRouteSummary) {
        val account = (authState.value as? AuthState.Authenticated)?.user?.id
        routeRepository
            .loadMine(route.id)
            .onSuccess { loaded ->
                draftRepository.replace(loaded, ownerAccountId = account)
                dialogs.update { it.copy(openComposer = true) }
            }.onFailure { reportOpenFailure(route.id) }
    }

    private fun reportOpenFailure(routeId: String) {
        dialogs.update {
            when (openOrigin) {
                OpenOrigin.LIST -> it.copy(error = MyRoutesError.OPEN_FAILED)
                OpenOrigin.PREVIEW -> it.copy(previewOpenFailed = routeId)
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
