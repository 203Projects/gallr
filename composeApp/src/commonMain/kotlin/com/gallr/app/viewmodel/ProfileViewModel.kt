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
import com.gallr.shared.repository.PublicNoteRepository
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: Profile? = null,
    /** The author's own 감상 in the account archive, whatever their review status (spec 103 US2). */
    val publicNoteCount: Int = 0,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
)

private data class ProfileLoad(
    val profile: Profile? = null,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
)

class ProfileViewModel(
    private val user: GallrUser,
    private val profileRepository: ProfileRepository,
    publicNoteRepository: PublicNoteRepository,
) : ViewModel() {
    private val log = AppLog.tagged("ProfileViewModel")
    private val load = MutableStateFlow(ProfileLoad())

    private val publicNoteCount =
        publicNoteRepository
            .observeOwnNotes()
            .map { it.size }
            .catch { error ->
                log.warn("load_public_note_count", error)
                emit(0)
            }

    val uiState: StateFlow<ProfileUiState> =
        combine(load, publicNoteCount) { load, noteCount ->
            ProfileUiState(
                profile = load.profile,
                publicNoteCount = noteCount,
                isLoading = load.isLoading,
                hasLoaded = load.hasLoaded,
                loadFailed = load.loadFailed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return

        loadJob =
            viewModelScope.launch {
                load.update { it.copy(isLoading = true, loadFailed = false) }
                runSuspendCatching { loadProfile() }
                    .onSuccess { profile ->
                        load.update {
                            it.copy(profile = profile, isLoading = false, hasLoaded = true, loadFailed = false)
                        }
                    }.onFailure { error ->
                        log.error("load_profile_summary", error)
                        load.update { it.copy(isLoading = false, loadFailed = true) }
                    }
            }
    }

    private suspend fun loadProfile(): Profile? {
        require(user.id.isNotBlank()) { "Authenticated user ID is missing" }
        return profileRepository.getProfile(user.id)
    }

    companion object {
        fun factory(
            user: GallrUser,
            profileRepository: ProfileRepository,
            publicNoteRepository: PublicNoteRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ProfileViewModel(
                        user = user,
                        profileRepository = profileRepository,
                        publicNoteRepository = publicNoteRepository,
                    )
                }
            }
    }
}
