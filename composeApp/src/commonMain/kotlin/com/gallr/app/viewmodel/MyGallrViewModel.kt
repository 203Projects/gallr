package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.MyGallrAccountNudgeRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

enum class MyGallrMode {
    ARCHIVE,
    ADD_VISITS,
    ADD_GALLERIES,
}

enum class MyGallrSection {
    VISITS,
    FOLLOWING,
}

data class GalleryCandidate(
    val galleryKey: String,
    val galleryId: String?,
    val snapshot: FollowedGallerySnapshot,
    val exhibitions: List<Exhibition>,
)

data class GallerySearchResult(
    val candidate: GalleryCandidate,
    val visitedCount: Int,
)

internal fun shouldOfferVisitPrompt(
    exhibition: Exhibition,
    today: kotlinx.datetime.LocalDate,
    isVisited: Boolean,
): Boolean = exhibition.closingDate < today && !isVisited

internal fun isVisitEligible(
    exhibition: Exhibition,
    today: kotlinx.datetime.LocalDate,
): Boolean = exhibition.openingDate <= today

data class FollowedGalleryUi(
    val record: FollowedGallery,
    val currentSnapshot: FollowedGallerySnapshot?,
    val currentExhibitions: List<Exhibition>,
    val unseenExhibitions: List<Exhibition>,
) {
    val snapshot: FollowedGallerySnapshot
        get() = currentSnapshot ?: record.snapshot

    val latestRelevantExhibition: Exhibition?
        get() = unseenExhibitions.firstOrNull() ?: currentExhibitions.firstOrNull()
}

data class MyGallrUiState(
    val visits: List<ExhibitionVisit> = emptyList(),
    val followedGalleryRecords: List<FollowedGallery> = emptyList(),
    val followedGalleries: List<FollowedGalleryUi> = emptyList(),
    val catalogue: List<Exhibition> = emptyList(),
    val availableExhibitions: List<Exhibition> = emptyList(),
    val galleryCandidates: List<GalleryCandidate> = emptyList(),
    val availableGalleryCandidates: List<GalleryCandidate> = emptyList(),
    val searchQuery: String = "",
    val gallerySearchQuery: String = "",
    val selectedExhibitionIds: Set<String> = emptySet(),
    val selectedGalleryKeys: Set<String> = emptySet(),
    val language: AppLanguage = AppLanguage.KO,
    val section: MyGallrSection = MyGallrSection.VISITS,
    val mode: MyGallrMode = MyGallrMode.ARCHIVE,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSavingFollows: Boolean = false,
    val loadFailed: Boolean = false,
    val followingLoadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val followSaveFailed: Boolean = false,
    val isAccountNudgeDismissed: Boolean = false,
    val isAccountNudgeLoaded: Boolean = false,
) {
    val canSave: Boolean
        get() = selectedExhibitionIds.isNotEmpty() && !isSaving

    val canSaveFollows: Boolean
        get() = selectedGalleryKeys.isNotEmpty() && !isSavingFollows

    val totalUnseenExhibitions: Int
        get() = followedGalleries.sumOf { it.unseenExhibitions.size }
}

class MyGallrViewModel(
    private val visitRepository: VisitRepository,
    private val followedGalleryRepository: FollowedGalleryRepository,
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    private val language: StateFlow<AppLanguage>,
    private val accountNudgeRepository: MyGallrAccountNudgeRepository = UndismissedAccountNudgeRepository,
    private val clock: Clock = Clock.System,
    private val recordIdFactory: (Exhibition, Instant) -> String = { exhibition, instant ->
        "${exhibition.id}:${instant.toEpochMilliseconds()}"
    },
) : ViewModel() {
    private val log = AppLog.tagged("MyGallrViewModel")
    private val _uiState = MutableStateFlow(MyGallrUiState())
    val uiState: StateFlow<MyGallrUiState> = _uiState.asStateFlow()

    init {
        observeVisits()
        observeFollowedGalleries()
        observeCatalogue()
        observeLanguage()
        observeAccountNudgeDismissal()
    }

    fun startAddingVisits() {
        updateState {
            it.copy(
                mode = MyGallrMode.ADD_VISITS,
                section = MyGallrSection.VISITS,
                searchQuery = "",
                selectedExhibitionIds = emptySet(),
                saveFailed = false,
            )
        }
    }

    fun cancelAddingVisits() {
        updateState {
            it.copy(
                mode = MyGallrMode.ARCHIVE,
                searchQuery = "",
                selectedExhibitionIds = emptySet(),
                saveFailed = false,
            )
        }
    }

    fun startAddingGalleries() {
        updateState {
            it.copy(
                mode = MyGallrMode.ADD_GALLERIES,
                section = MyGallrSection.FOLLOWING,
                gallerySearchQuery = "",
                selectedGalleryKeys = emptySet(),
                followSaveFailed = false,
            )
        }
    }

    fun cancelAddingGalleries() {
        updateState {
            it.copy(
                mode = MyGallrMode.ARCHIVE,
                gallerySearchQuery = "",
                selectedGalleryKeys = emptySet(),
                followSaveFailed = false,
            )
        }
    }

    fun setSearchQuery(query: String) {
        updateState { it.copy(searchQuery = query, saveFailed = false) }
    }

    fun selectSection(section: MyGallrSection) {
        updateState { it.copy(section = section) }
    }

    fun toggleSelection(exhibitionId: String) {
        val state = _uiState.value
        if (state.visits.any { it.exhibitionId == exhibitionId }) return
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        if (state.catalogue.none { it.id == exhibitionId && isVisitEligible(it, today) }) return

        updateState {
            val selected =
                if (exhibitionId in it.selectedExhibitionIds) {
                    it.selectedExhibitionIds - exhibitionId
                } else {
                    it.selectedExhibitionIds + exhibitionId
                }
            it.copy(selectedExhibitionIds = selected, saveFailed = false)
        }
    }

    fun setGallerySearchQuery(query: String) {
        updateState { it.copy(gallerySearchQuery = query, followSaveFailed = false) }
    }

    fun toggleGallerySelection(galleryKey: String) {
        val state = _uiState.value
        if (state.followedGalleryRecords.any { it.galleryKey == galleryKey }) return
        if (state.galleryCandidates.none { it.galleryKey == galleryKey }) return

        updateState {
            val selected =
                if (galleryKey in it.selectedGalleryKeys) {
                    it.selectedGalleryKeys - galleryKey
                } else {
                    it.selectedGalleryKeys + galleryKey
                }
            it.copy(selectedGalleryKeys = selected, followSaveFailed = false)
        }
    }

    fun saveSelected() {
        val state = _uiState.value
        if (!state.canSave) return

        viewModelScope.launch {
            updateState { it.copy(isSaving = true, saveFailed = false) }
            val createdAt = clock.now()
            val today = createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
            val selected =
                state.catalogue.filter {
                    it.id in state.selectedExhibitionIds && isVisitEligible(it, today)
                }
            if (selected.isEmpty()) {
                updateState {
                    it.copy(
                        selectedExhibitionIds = emptySet(),
                        isSaving = false,
                        saveFailed = false,
                    )
                }
                return@launch
            }
            val visits =
                selected.map { exhibition ->
                    visitFromExhibition(
                        exhibition = exhibition,
                        createdAt = createdAt,
                        clientRecordId = recordIdFactory(exhibition, createdAt),
                    )
                }
            try {
                visitRepository.addVisits(visits)
                updateState {
                    it.copy(
                        mode = MyGallrMode.ARCHIVE,
                        searchQuery = "",
                        selectedExhibitionIds = emptySet(),
                        isSaving = false,
                        saveFailed = false,
                    )
                }
            } catch (error: Exception) {
                log.error("save_visits", error)
                updateState { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    fun removeVisit(exhibitionId: String) {
        viewModelScope.launch {
            try {
                visitRepository.removeVisit(exhibitionId)
            } catch (error: Exception) {
                log.error("remove_visit", error)
            }
        }
    }

    fun saveSelectedGalleries() {
        val state = _uiState.value
        if (!state.canSaveFollows) return

        viewModelScope.launch {
            updateState { it.copy(isSavingFollows = true, followSaveFailed = false) }
            val followedAt = clock.now()
            val galleries =
                state.galleryCandidates
                    .filter { it.galleryKey in state.selectedGalleryKeys }
                    .map { candidate ->
                        FollowedGallery(
                            galleryKey = candidate.galleryKey,
                            snapshot = candidate.snapshot,
                            knownExhibitionIds = candidate.exhibitions.mapTo(mutableSetOf()) { it.id },
                            followedAt = followedAt,
                            galleryId = candidate.galleryId,
                        )
                    }
            try {
                followedGalleryRepository.followGalleries(galleries)
                updateState {
                    it.copy(
                        mode = MyGallrMode.ARCHIVE,
                        gallerySearchQuery = "",
                        selectedGalleryKeys = emptySet(),
                        isSavingFollows = false,
                        followSaveFailed = false,
                    )
                }
            } catch (error: Exception) {
                log.error("follow_galleries", error)
                updateState { it.copy(isSavingFollows = false, followSaveFailed = true) }
            }
        }
    }

    fun unfollowGallery(galleryKey: String) {
        viewModelScope.launch {
            try {
                followedGalleryRepository.unfollowGallery(galleryKey)
            } catch (error: Exception) {
                log.error("unfollow_gallery", error)
            }
        }
    }

    fun acknowledgeGallery(galleryKey: String) {
        val followed = _uiState.value.followedGalleries.firstOrNull { it.record.galleryKey == galleryKey } ?: return
        viewModelScope.launch {
            try {
                followedGalleryRepository.acknowledgeGallery(
                    galleryKey = galleryKey,
                    currentExhibitionIds = followed.currentExhibitions.mapTo(mutableSetOf()) { it.id },
                )
            } catch (error: Exception) {
                log.error("acknowledge_gallery", error)
            }
        }
    }

    fun dismissAccountNudge() {
        viewModelScope.launch {
            try {
                accountNudgeRepository.dismiss()
            } catch (error: Exception) {
                log.error("dismiss_account_nudge", error)
            }
        }
    }

    private fun observeVisits() {
        viewModelScope.launch {
            visitRepository
                .observeVisits()
                .catch { error ->
                    log.error("load_visits", error)
                    updateState { it.copy(isLoading = false, loadFailed = true) }
                }.collect { visits ->
                    updateState {
                        it.copy(
                            visits = visits,
                            isLoading = false,
                            loadFailed = false,
                        )
                    }
                }
        }
    }

    private fun observeFollowedGalleries() {
        viewModelScope.launch {
            followedGalleryRepository
                .observeFollowedGalleries()
                .catch { error ->
                    log.error("load_followed_galleries", error)
                    updateState { it.copy(followingLoadFailed = true) }
                }.collect { galleries ->
                    updateState {
                        it.copy(
                            followedGalleryRecords = galleries,
                            followingLoadFailed = false,
                        )
                    }
                    migrateLegacyGalleryIdentities()
                }
        }
    }

    private fun observeCatalogue() {
        viewModelScope.launch {
            exhibitionsState.collect { state ->
                if (state is ExhibitionListState.Success) {
                    updateState { it.copy(catalogue = state.exhibitions) }
                    migrateLegacyGalleryIdentities()
                }
            }
        }
    }

    private fun observeLanguage() {
        viewModelScope.launch {
            language.collect { appLanguage -> updateState { it.copy(language = appLanguage) } }
        }
    }

    private fun observeAccountNudgeDismissal() {
        viewModelScope.launch {
            accountNudgeRepository
                .observeDismissed()
                .catch { error -> log.error("load_account_nudge", error) }
                .collect { dismissed ->
                    updateState {
                        it.copy(
                            isAccountNudgeDismissed = dismissed,
                            isAccountNudgeLoaded = true,
                        )
                    }
                }
        }
    }

    private fun updateState(transform: (MyGallrUiState) -> MyGallrUiState) {
        _uiState.update { current -> transform(current).withDerivedContent() }
    }

    private suspend fun migrateLegacyGalleryIdentities() {
        val state = _uiState.value
        state.followedGalleryRecords
            .filter { it.galleryId == null }
            .mapNotNull { record ->
                val matches = state.galleryCandidates.filter { it.galleryKey == record.galleryKey }
                val galleryId = matches.singleOrNull()?.galleryId ?: return@mapNotNull null
                record.galleryKey to galleryId
            }.forEach { (galleryKey, galleryId) ->
                try {
                    followedGalleryRepository.assignGalleryId(galleryKey, galleryId)
                } catch (error: Exception) {
                    log.error("migrate_gallery_id", error)
                }
            }
    }

    private fun MyGallrUiState.withDerivedContent(): MyGallrUiState {
        val archivedIds = visits.mapTo(mutableSetOf()) { it.exhibitionId }
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val normalizedQuery = searchQuery.trim().lowercase()
        val available =
            catalogue.filter { exhibition ->
                exhibition.id !in archivedIds &&
                    isVisitEligible(exhibition, today) &&
                    (
                        normalizedQuery.isEmpty() ||
                            exhibition.searchableText().contains(normalizedQuery)
                    )
            }
        val candidates = catalogue.toGalleryCandidates(language)
        val followedKeys = followedGalleryRecords.mapTo(mutableSetOf()) { it.galleryKey }
        val followedIds = followedGalleryRecords.mapNotNullTo(mutableSetOf()) { it.galleryId }
        val normalizedGalleryQuery = gallerySearchQuery.trim().lowercase()
        val availableGalleries =
            candidates.filter { candidate ->
                candidate.galleryKey !in followedKeys &&
                    (candidate.galleryId == null || candidate.galleryId !in followedIds) &&
                    (
                        normalizedGalleryQuery.isEmpty() ||
                            candidate.snapshot.searchableText().contains(normalizedGalleryQuery)
                    )
            }
        val followed =
            followedGalleryRecords.map { record ->
                val candidate =
                    record.galleryId
                        ?.let { id -> candidates.firstOrNull { it.galleryId == id } }
                        ?: candidates.firstOrNull { it.galleryKey == record.galleryKey }
                val current = candidate?.exhibitions.orEmpty()
                FollowedGalleryUi(
                    record = record,
                    currentSnapshot = candidate?.snapshot,
                    currentExhibitions = current,
                    unseenExhibitions = current.filter { it.id !in record.knownExhibitionIds },
                )
            }
        return copy(
            availableExhibitions = available,
            galleryCandidates = candidates,
            availableGalleryCandidates = availableGalleries,
            followedGalleries = followed,
        )
    }

    private fun FollowedGallerySnapshot.searchableText(): String =
        listOf(nameKo, nameEn)
            .joinToString(separator = " ")
            .lowercase()

    companion object {
        fun factory(
            visitRepository: VisitRepository,
            followedGalleryRepository: FollowedGalleryRepository,
            accountNudgeRepository: MyGallrAccountNudgeRepository,
            exhibitionsState: StateFlow<ExhibitionListState>,
            language: StateFlow<AppLanguage>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MyGallrViewModel(
                        visitRepository = visitRepository,
                        followedGalleryRepository = followedGalleryRepository,
                        accountNudgeRepository = accountNudgeRepository,
                        exhibitionsState = exhibitionsState,
                        language = language,
                    )
                }
            }
    }
}

private fun Exhibition.searchableText(): String =
    listOf(nameKo, nameEn, venueNameKo, venueNameEn)
        .joinToString(separator = " ")
        .lowercase()

internal fun List<Exhibition>.toGalleryCandidates(language: AppLanguage): List<GalleryCandidate> =
    filter { it.venueNameKo.isNotBlank() || it.venueNameEn.isNotBlank() }
        .groupBy { exhibition ->
            exhibition.galleryId?.let { "gallery:$it" }
                ?: galleryKey(exhibition.venueNameKo, exhibition.venueNameEn)
        }.map { (_, exhibitions) ->
            val sorted =
                exhibitions.sortedWith(
                    compareByDescending<Exhibition> { it.openingDate }
                        .thenByDescending { it.id },
                )
            val representative = sorted.first()
            val locations =
                sorted
                    .map { listOf(it.cityKo, it.regionKo, it.cityEn, it.regionEn) }
                    .distinct()
            GalleryCandidate(
                galleryKey = galleryKey(representative.venueNameKo, representative.venueNameEn),
                galleryId = exhibitions.mapNotNull { it.galleryId }.distinct().singleOrNull(),
                snapshot =
                    FollowedGallerySnapshot(
                        nameKo = representative.venueNameKo.trim(),
                        nameEn = representative.venueNameEn.trim(),
                        cityKo = if (locations.size == 1) representative.cityKo else "여러 지점",
                        cityEn = if (locations.size == 1) representative.cityEn else "Multiple locations",
                        regionKo = if (locations.size == 1) representative.regionKo else "",
                        regionEn = if (locations.size == 1) representative.regionEn else "",
                    ),
                exhibitions = sorted,
            )
        }.sortedBy { it.snapshot.localizedName(language).lowercase() }

internal fun List<Exhibition>.gallerySearchResults(
    query: String,
    language: AppLanguage,
    visitedExhibitionIds: Set<String>,
): List<GallerySearchResult> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return emptyList()
    return toGalleryCandidates(language)
        .filter { candidate ->
            listOf(candidate.snapshot.nameKo, candidate.snapshot.nameEn)
                .joinToString(" ")
                .lowercase()
                .contains(normalizedQuery)
        }.map { candidate ->
            GallerySearchResult(
                candidate = candidate,
                visitedCount = candidate.exhibitions.count { it.id in visitedExhibitionIds },
            )
        }
}

internal fun MyGallrUiState.shouldShowAccountNudge(authState: AuthState): Boolean =
    authState is AuthState.Anonymous &&
        isAccountNudgeLoaded &&
        !isAccountNudgeDismissed &&
        visits.size + followedGalleryRecords.size >= 3

private data object UndismissedAccountNudgeRepository : MyGallrAccountNudgeRepository {
    override fun observeDismissed() = flowOf(false)

    override suspend fun dismiss() = Unit
}

internal fun visitFromExhibition(
    exhibition: Exhibition,
    createdAt: Instant,
    clientRecordId: String,
): ExhibitionVisit =
    ExhibitionVisit(
        clientRecordId = clientRecordId,
        exhibitionId = exhibition.id,
        snapshot = ExhibitionVisitSnapshot.from(exhibition),
        createdAt = createdAt,
    )
