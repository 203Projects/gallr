package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.ProfileRepository
import com.gallr.shared.repository.ThoughtRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: Profile? = null,
    val thoughtExhibitionIds: Set<String> = emptySet(),
    val thoughtCount: Int = 0,
    val pendingCount: Int = 0,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
)

private data class ProfileSnapshot(
    val profile: Profile?,
    val thoughtExhibitionIds: Set<String>,
    val thoughtCount: Int,
    val pendingCount: Int,
)

class ProfileViewModel(
    private val user: GallrUser,
    private val profileRepository: ProfileRepository,
    private val thoughtRepository: ThoughtRepository,
) : ViewModel() {
    private val log = AppLog.tagged("ProfileViewModel")
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return

        loadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, loadFailed = false) }
                runSuspendCatching { loadSnapshot() }
                    .onSuccess { snapshot ->
                        _uiState.update {
                            it.copy(
                                profile = snapshot.profile,
                                thoughtExhibitionIds = snapshot.thoughtExhibitionIds,
                                thoughtCount = snapshot.thoughtCount,
                                pendingCount = snapshot.pendingCount,
                                isLoading = false,
                                hasLoaded = true,
                                loadFailed = false,
                            )
                        }
                    }.onFailure { error ->
                        log.error("load_profile_summary", error)
                        _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                    }
            }
    }

    private suspend fun loadSnapshot(): ProfileSnapshot {
        require(user.id.isNotBlank()) { "Authenticated user ID is missing" }

        val profile = profileRepository.getProfile(user.id)
        val thoughts = thoughtRepository.getUserThoughts(user.id)
        val pendingCount =
            if (profile?.isAdmin == true) {
                thoughtRepository.getPendingThoughts().size
            } else {
                0
            }
        return ProfileSnapshot(
            profile = profile,
            thoughtExhibitionIds = thoughts.mapTo(mutableSetOf()) { it.exhibitionId },
            thoughtCount = thoughts.size,
            pendingCount = pendingCount,
        )
    }

    companion object {
        fun factory(
            user: GallrUser,
            profileRepository: ProfileRepository,
            thoughtRepository: ThoughtRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ProfileViewModel(
                        user = user,
                        profileRepository = profileRepository,
                        thoughtRepository = thoughtRepository,
                    )
                }
            }
    }
}
