package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.GalleryAlertRegistrationRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class GalleryDetailUiState(
    val galleryKey: String,
    val snapshot: FollowedGallerySnapshot,
    val exhibitions: List<Exhibition>,
    val visitedExhibitions: List<ExhibitionVisit> = emptyList(),
    val followedGallery: FollowedGallery? = null,
    val isSaving: Boolean = false,
    val showAlertRationale: Boolean = false,
    val permissionDenied: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val isFollowing: Boolean
        get() = followedGallery != null

    val newExhibitionAlertsEnabled: Boolean
        get() = followedGallery?.newExhibitionAlertsEnabled == true

    val currentExhibition: Exhibition?
        get() = exhibitions.firstOrNull()
}

class GalleryDetailViewModel(
    private val representative: Exhibition,
    private val exhibitionsState: StateFlow<ExhibitionListState>,
    private val followedGalleryRepository: FollowedGalleryRepository,
    private val notificationScheduler: NotificationScheduler,
    private val galleryAlertRegistrationRepository: GalleryAlertRegistrationRepository,
    private val remotePushAddressProvider: RemotePushAddressProvider,
    private val visitRepository: VisitRepository,
    private val locale: String,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val log = AppLog.tagged("GalleryDetailViewModel")
    private val key = galleryKey(representative.venueNameKo, representative.venueNameEn)
    private val _uiState =
        MutableStateFlow(
            GalleryDetailUiState(
                galleryKey = key,
                snapshot = representative.toFollowedGallerySnapshot(),
                exhibitions = listOf(representative),
            ),
        )
    val uiState: StateFlow<GalleryDetailUiState> = _uiState.asStateFlow()

    init {
        observeCatalogue()
        observeFollowing()
        observeVisits()
    }

    fun toggleFollow() {
        if (_uiState.value.isSaving) return
        if (_uiState.value.isFollowing) {
            viewModelScope.launch {
                _uiState.update { it.copy(isSaving = true, saveFailed = false) }
                runCatching { followedGalleryRepository.unfollowGallery(key) }
                    .onFailure { error ->
                        log.error("unfollow_gallery_from_detail", error)
                        _uiState.update { it.copy(saveFailed = true) }
                    }
                _uiState.update { it.copy(isSaving = false) }
            }
        } else {
            follow(showRationaleAfter = false)
        }
    }

    fun openAlertRationale() {
        if (_uiState.value.newExhibitionAlertsEnabled) {
            disableAlerts()
        } else if (_uiState.value.isFollowing) {
            _uiState.update {
                it.copy(
                    showAlertRationale = true,
                    permissionDenied = false,
                    saveFailed = false,
                )
            }
        } else {
            follow(showRationaleAfter = true)
        }
    }

    fun dismissAlertRationale() {
        _uiState.update { it.copy(showAlertRationale = false, permissionDenied = false) }
    }

    fun enableAlerts() {
        val state = _uiState.value
        if (!state.isFollowing || state.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, permissionDenied = false, saveFailed = false) }
            val granted =
                runCatching { notificationScheduler.requestPermission() }
                    .onFailure { log.error("request_gallery_alert_permission", it) }
                    .getOrDefault(false)
            if (!granted) {
                _uiState.update { it.copy(isSaving = false, permissionDenied = true) }
                return@launch
            }
            runCatching {
                val galleryId =
                    checkNotNull(representative.galleryId) {
                        "Stable gallery identity is required for remote alerts"
                    }
                val address =
                    checkNotNull(remotePushAddressProvider.currentAddress()) {
                        "Remote notification address is unavailable"
                    }
                galleryAlertRegistrationRepository
                    .enableGallery(
                        galleryId = galleryId,
                        address = address,
                        locale = locale,
                    ).getOrThrow()
                followedGalleryRepository.setNewExhibitionAlertsEnabled(key, true)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        showAlertRationale = false,
                        permissionDenied = false,
                    )
                }
            }.onFailure { error ->
                log.error("enable_gallery_alerts", error)
                _uiState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    private fun disableAlerts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveFailed = false) }
            runCatching {
                representative.galleryId?.let { galleryId ->
                    galleryAlertRegistrationRepository
                        .disableGallery(
                            galleryId = galleryId,
                            platform = remotePushAddressProvider.platform,
                            locale = locale,
                        ).getOrThrow()
                }
                followedGalleryRepository.setNewExhibitionAlertsEnabled(key, false)
            }.onFailure { error ->
                log.error("disable_gallery_alerts", error)
                _uiState.update { it.copy(saveFailed = true) }
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun follow(showRationaleAfter: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveFailed = false) }
            val state = _uiState.value
            val followed =
                FollowedGallery(
                    galleryKey = key,
                    galleryId = representative.galleryId,
                    snapshot = state.snapshot,
                    knownExhibitionIds = state.exhibitions.mapTo(mutableSetOf()) { it.id },
                    followedAt = clock.now(),
                )
            runCatching { followedGalleryRepository.followGallery(followed) }
                .onSuccess {
                    if (showRationaleAfter) {
                        _uiState.update { it.copy(showAlertRationale = true) }
                    }
                }.onFailure { error ->
                    log.error("follow_gallery_from_detail", error)
                    _uiState.update { it.copy(saveFailed = true) }
                }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun observeCatalogue() {
        viewModelScope.launch {
            exhibitionsState.collect { state ->
                val catalogue = (state as? ExhibitionListState.Success)?.exhibitions.orEmpty()
                val galleryExhibitions =
                    catalogue
                        .filter { exhibition -> representative.belongsToSameGallery(exhibition) }
                        .sortedWith(
                            compareByDescending<Exhibition> { it.openingDate }
                                .thenByDescending { it.id },
                        ).ifEmpty { listOf(representative) }
                val latest = galleryExhibitions.first()
                _uiState.update {
                    it.copy(
                        snapshot = latest.toFollowedGallerySnapshot(),
                        exhibitions = galleryExhibitions,
                    )
                }
            }
        }
    }

    private fun observeFollowing() {
        viewModelScope.launch {
            followedGalleryRepository
                .observeFollowedGalleries()
                .catch { error ->
                    log.error("observe_gallery_detail_follow", error)
                    _uiState.update { it.copy(saveFailed = true) }
                }.collect { galleries ->
                    val followed =
                        representative.galleryId
                            ?.let { id -> galleries.firstOrNull { it.galleryId == id } }
                            ?: galleries.firstOrNull { it.galleryKey == key }
                    _uiState.update { it.copy(followedGallery = followed) }
                }
        }
    }

    private fun observeVisits() {
        viewModelScope.launch {
            visitRepository
                .observeVisits()
                .catch { error ->
                    log.error("observe_gallery_detail_visits", error)
                }.collect { visits ->
                    val matching =
                        visits
                            .filter { visit ->
                                if (representative.galleryId != null && visit.snapshot.galleryId != null) {
                                    representative.galleryId == visit.snapshot.galleryId
                                } else {
                                    galleryKey(
                                        visit.snapshot.venueNameKo,
                                        visit.snapshot.venueNameEn,
                                    ) == key
                                }
                            }.sortedByDescending { it.createdAt }
                    _uiState.update { it.copy(visitedExhibitions = matching) }
                }
        }
    }

    private fun Exhibition.belongsToSameGallery(other: Exhibition): Boolean =
        if (galleryId != null && other.galleryId != null) {
            galleryId == other.galleryId
        } else {
            galleryKey(venueNameKo, venueNameEn) == galleryKey(other.venueNameKo, other.venueNameEn)
        }

    private fun Exhibition.toFollowedGallerySnapshot() =
        FollowedGallerySnapshot(
            nameKo = venueNameKo.trim(),
            nameEn = venueNameEn.trim(),
            cityKo = cityKo.trim(),
            cityEn = cityEn.trim(),
            regionKo = regionKo.trim(),
            regionEn = regionEn.trim(),
        )

    companion object {
        fun factory(
            representative: Exhibition,
            exhibitionsState: StateFlow<ExhibitionListState>,
            followedGalleryRepository: FollowedGalleryRepository,
            notificationScheduler: NotificationScheduler,
            galleryAlertRegistrationRepository: GalleryAlertRegistrationRepository,
            remotePushAddressProvider: RemotePushAddressProvider,
            visitRepository: VisitRepository,
            locale: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    GalleryDetailViewModel(
                        representative = representative,
                        exhibitionsState = exhibitionsState,
                        followedGalleryRepository = followedGalleryRepository,
                        notificationScheduler = notificationScheduler,
                        galleryAlertRegistrationRepository = galleryAlertRegistrationRepository,
                        remotePushAddressProvider = remotePushAddressProvider,
                        visitRepository = visitRepository,
                        locale = locale,
                    )
                }
            }
    }
}
