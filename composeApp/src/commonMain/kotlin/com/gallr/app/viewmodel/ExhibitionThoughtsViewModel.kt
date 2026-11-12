package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Thought
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.HiddenThoughtAuthorRepository
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.repository.ThoughtNoLongerPublicException
import com.gallr.shared.repository.ThoughtReportAlreadySentException
import com.gallr.shared.repository.ThoughtReportSignInRequiredException
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.thoughts.HiddenAuthor
import com.gallr.shared.thoughts.PublicThought
import com.gallr.shared.thoughts.ThoughtReportReason
import com.gallr.shared.thoughts.visibleThoughts
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The 감상 section of an exhibition page (spec 103 US4). [thoughts] are already without the authors this device hides;
 * [currentUserId] tells the reader's own cards apart. [reportTarget] is the 감상 whose report sheet is open.
 * [ownPendingThought], [hasUserThought], [mutatingThoughtId] and [mutationFailed] belong to the write side, which the
 * publish slice (spec 103 US3) replaces.
 */
data class ExhibitionThoughtsUiState(
    val thoughts: List<PublicThought> = emptyList(),
    val currentUserId: String? = null,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val reportTarget: PublicThought? = null,
    val reportBusy: Boolean = false,
    /** A one-off request for the page to open sign-in; cleared by [ExhibitionThoughtsViewModel.onSignInRequestHandled]. */
    val signInRequested: Boolean = false,
    val message: ThoughtsMessage? = null,
    val ownPendingThought: Thought? = null,
    val hasUserThought: Boolean = false,
    val mutatingThoughtId: String? = null,
    val mutationFailed: Boolean = false,
)

/** What the page says in a snackbar after an action; shown once, then [ExhibitionThoughtsViewModel.messageShown]. */
sealed interface ThoughtsMessage {
    data object Reported : ThoughtsMessage

    data object AlreadyReported : ThoughtsMessage

    data object ReportFailed : ThoughtsMessage

    /** Reporting while the session refresh is pending: the request would go out without the account. */
    data object CheckConnection : ThoughtsMessage

    /** Carries the person so the snackbar's 되돌리기 can bring them back. */
    data class AuthorHidden(
        val userId: String,
        val displayName: String,
    ) : ThoughtsMessage

    data object HideFailed : ThoughtsMessage

    data object UnpublishFailed : ThoughtsMessage
}

/** Who reads the page: the account (null for a guest) and whether its session is still being refreshed. */
private data class ThoughtsReader(
    val userId: String?,
    val refreshPending: Boolean,
    val canReport: Boolean,
)

private fun AuthState.toReader(): ThoughtsReader =
    when (this) {
        is AuthState.Authenticated -> ThoughtsReader(user.id, isSessionRefreshPending, canReport = true)

        AuthState.Anonymous -> ThoughtsReader(userId = null, refreshPending = false, canReport = false)

        // The session is not known yet; reading works without it, a report waits like a pending refresh.
        AuthState.Loading -> ThoughtsReader(userId = null, refreshPending = true, canReport = false)
    }

/**
 * Reads 감상 through `list_exhibition_thoughts`, which leaves out what this reader has an open report on, and drops
 * the authors this device hides (FR-023, FR-024). While the session refresh is pending, requests go out without the
 * account, so the list is not read again (an anonymous read would show the reporter what they reported) and a report
 * asks the reader to check the connection instead of opening sign-in. A guest's report asks for sign-in and opens the
 * sheet once the account arrives through [onAuthStateChanged].
 */
class ExhibitionThoughtsViewModel(
    private val exhibitionId: String,
    authState: AuthState,
    private val thoughtRepository: ThoughtRepository,
    private val hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository,
    private val publicNoteRepository: PublicNoteRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val log = AppLog.tagged("Thoughts")
    private var reader = authState.toReader()
    private val _uiState = MutableStateFlow(ExhibitionThoughtsUiState(currentUserId = reader.userId))
    val uiState: StateFlow<ExhibitionThoughtsUiState> = _uiState.asStateFlow()

    /** The list as the server returned it; [uiState] shows it without hidden authors. */
    private var loadedThoughts: List<PublicThought> = emptyList()
    private var hiddenAuthorIds: Set<String> = emptySet()

    /** The 감상 a guest chose to report; its sheet opens after sign-in. */
    private var pendingReportThoughtId: String? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            hiddenThoughtAuthorRepository.hiddenAuthors.collect { authors ->
                hiddenAuthorIds = authors.mapTo(mutableSetOf(), HiddenAuthor::userId)
                publishThoughts()
            }
        }
        load()
    }

    /** Reads the list again; skipped while a refresh is pending once something is shown. */
    fun refresh() {
        if (reader.refreshPending && _uiState.value.hasLoaded) return
        load()
    }

    /** The page's current account; a change of account or the end of a pending refresh reads the list again. */
    fun onAuthStateChanged(authState: AuthState) {
        val next = authState.toReader()
        if (next == reader) return
        reader = next
        _uiState.update { it.copy(currentUserId = next.userId) }
        if (next.refreshPending) return
        loadJob?.cancel()
        load()
    }

    fun startReport(thought: PublicThought) {
        if (thought.authorId == reader.userId || thought.reportedByMe) return
        when {
            reader.refreshPending -> {
                _uiState.update { it.copy(message = ThoughtsMessage.CheckConnection) }
            }

            !reader.canReport -> {
                pendingReportThoughtId = thought.id
                _uiState.update { it.copy(signInRequested = true) }
            }

            else -> {
                pendingReportThoughtId = null
                _uiState.update { it.copy(reportTarget = thought) }
            }
        }
    }

    fun closeReport() {
        if (_uiState.value.reportBusy) return
        _uiState.update { it.copy(reportTarget = null) }
    }

    fun report(reason: ThoughtReportReason) {
        val target = _uiState.value.reportTarget ?: return
        if (_uiState.value.reportBusy) return
        _uiState.update { it.copy(reportBusy = true) }
        viewModelScope.launch {
            val result = runSuspendCatching { thoughtRepository.report(target.id, reason) }
            _uiState.update { it.copy(reportBusy = false, reportTarget = null) }
            result
                .onSuccess {
                    // The server now leaves it out for this reader only; take it off at once, then read again.
                    loadedThoughts = loadedThoughts.filterNot { it.id == target.id }
                    publishThoughts()
                    showMessage(ThoughtsMessage.Reported)
                    load()
                }.onFailure { error -> onReportFailed(target, error) }
        }
    }

    fun onSignInRequestHandled() {
        _uiState.update { it.copy(signInRequested = false) }
    }

    fun hideAuthor(thought: PublicThought) {
        if (thought.authorId == reader.userId) return
        val author = HiddenAuthor(thought.authorId, thought.authorDisplayName, clock.now())
        viewModelScope.launch {
            // The store logs its own write failure (hidden_author_write_failed).
            runSuspendCatching { hiddenThoughtAuthorRepository.hide(author) }
                .onSuccess { showMessage(ThoughtsMessage.AuthorHidden(author.userId, author.displayName)) }
                .onFailure { showMessage(ThoughtsMessage.HideFailed) }
        }
    }

    /** The snackbar's 되돌리기. */
    fun undoHideAuthor(userId: String) {
        viewModelScope.launch {
            runSuspendCatching { hiddenThoughtAuthorRepository.unhide(userId) }
                .onFailure { showMessage(ThoughtsMessage.HideFailed) }
        }
    }

    /** 공개 중단 on the reader's own card: the 감상 leaves the page and its note stays in the diary (FR-025). */
    fun unpublishOwn(thought: PublicThought) {
        if (thought.authorId != reader.userId) return
        viewModelScope.launch {
            runSuspendCatching { publicNoteRepository.unpublish(exhibitionId) }
                .onSuccess {
                    loadedThoughts = loadedThoughts.filterNot { it.id == thought.id }
                    publishThoughts()
                }.onFailure { error ->
                    log.warn("thought_unpublish_failed", error)
                    showMessage(ThoughtsMessage.UnpublishFailed)
                }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    /** The author's own pending 감상 (write side, until spec 103 T104). */
    fun deleteThought(thoughtId: String) {
        if (_uiState.value.isLoading || _uiState.value.mutatingThoughtId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(mutatingThoughtId = thoughtId, mutationFailed = false) }
            runSuspendCatching { thoughtRepository.deleteThought(thoughtId) }
                .onSuccess {
                    loadedThoughts = loadedThoughts.filterNot { it.id == thoughtId }
                    _uiState.update { state ->
                        state.copy(
                            ownPendingThought = state.ownPendingThought?.takeUnless { it.id == thoughtId },
                            mutatingThoughtId = null,
                        )
                    }
                    publishThoughts()
                }.onFailure { error ->
                    log.warn("delete_thought", error)
                    _uiState.update { it.copy(mutatingThoughtId = null, mutationFailed = true) }
                }
        }
    }

    /** Write side, until spec 103 T104 replaces the composer with the diary entry sheet. */
    suspend fun submitThought(content: String) {
        thoughtRepository.submitThought(exhibitionId, content)
        refresh()
    }

    private fun onReportFailed(
        target: PublicThought,
        error: Throwable,
    ) {
        when (error) {
            is ThoughtReportAlreadySentException -> {
                showMessage(ThoughtsMessage.AlreadyReported)
                load()
            }

            is ThoughtReportSignInRequiredException -> {
                if (reader.refreshPending) {
                    showMessage(ThoughtsMessage.CheckConnection)
                } else {
                    pendingReportThoughtId = target.id
                    _uiState.update { it.copy(signInRequested = true) }
                }
            }

            is ThoughtNoLongerPublicException -> {
                showMessage(ThoughtsMessage.ReportFailed)
                load()
            }

            else -> {
                log.warn("thought_report_failed", error)
                showMessage(ThoughtsMessage.ReportFailed)
            }
        }
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        val loadingFor = reader
        loadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, loadFailed = false) }
                runSuspendCatching { loadSnapshot(loadingFor.userId) }
                    .onSuccess { (thoughts, ownPending) ->
                        loadedThoughts = thoughts
                        _uiState.update {
                            it.copy(isLoading = false, hasLoaded = true, ownPendingThought = ownPending)
                        }
                        publishThoughts()
                        openPendingReport()
                    }.onFailure { error ->
                        log.warn("thought_list_failed", error)
                        _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                    }
            }
    }

    private suspend fun loadSnapshot(userId: String?): Pair<List<PublicThought>, Thought?> {
        require(exhibitionId.isNotBlank()) { "Exhibition ID is missing" }
        val thoughts = thoughtRepository.exhibitionThoughts(exhibitionId)
        // The author's own pending 감상 is the write side's, read as before until spec 103 T104.
        val ownPending =
            userId
                ?.let { thoughtRepository.getUserThoughtForExhibition(exhibitionId) }
                ?.takeUnless { it.isApproved }
        return thoughts to ownPending
    }

    /** After sign-in, the sheet for the 감상 the guest chose, if it is still on the page and can be reported. */
    private fun openPendingReport() {
        val thoughtId = pendingReportThoughtId ?: return
        if (!reader.canReport || reader.refreshPending) return
        pendingReportThoughtId = null
        val target =
            _uiState.value.thoughts
                .firstOrNull { it.id == thoughtId }
                ?.takeUnless { it.authorId == reader.userId || it.reportedByMe }
                ?: return
        _uiState.update { it.copy(reportTarget = target) }
    }

    private fun publishThoughts() {
        val userId = reader.userId
        _uiState.update { state ->
            state.copy(
                thoughts = visibleThoughts(loadedThoughts, hiddenAuthorIds, userId),
                hasUserThought =
                    userId != null &&
                        (loadedThoughts.any { it.authorId == userId } || state.ownPendingThought != null),
            )
        }
    }

    private fun showMessage(message: ThoughtsMessage) {
        _uiState.update { it.copy(message = message) }
    }

    companion object {
        fun factory(
            exhibitionId: String,
            authState: AuthState,
            thoughtRepository: ThoughtRepository,
            hiddenThoughtAuthorRepository: HiddenThoughtAuthorRepository,
            publicNoteRepository: PublicNoteRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExhibitionThoughtsViewModel(
                        exhibitionId = exhibitionId,
                        authState = authState,
                        thoughtRepository = thoughtRepository,
                        hiddenThoughtAuthorRepository = hiddenThoughtAuthorRepository,
                        publicNoteRepository = publicNoteRepository,
                    )
                }
            }
    }
}
