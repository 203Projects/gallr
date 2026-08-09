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
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditProfileError {
    NAME_REQUIRED,
    IMAGE_READ_FAILED,
    IMAGE_PROCESSING_FAILED,
    AVATAR_UPLOAD_FAILED,
    SAVE_FAILED,
}

data class EditProfileUiState(
    val displayName: String,
    val avatarUrl: String?,
    val isSaving: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val saveSucceeded: Boolean = false,
    val error: EditProfileError? = null,
)

class EditProfileViewModel(
    private val user: GallrUser,
    private val profile: Profile?,
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val log = AppLog.tagged("EditProfileViewModel")
    private val _uiState =
        MutableStateFlow(
            EditProfileUiState(
                displayName = initialDisplayName(user, profile),
                avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: user.avatarUrl,
            ),
        )
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    fun updateDisplayName(displayName: String) {
        _uiState.update { it.copy(displayName = displayName, error = null) }
    }

    fun showError(error: EditProfileError) {
        _uiState.update { it.copy(error = error) }
    }

    fun uploadAvatar(imageBytes: ByteArray) {
        val state = _uiState.value
        if (state.isUploadingAvatar || state.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, error = null) }
            runSuspendCatching { profileRepository.uploadAvatar(user.id, imageBytes) }
                .onSuccess { avatarUrl ->
                    _uiState.update {
                        it.copy(
                            avatarUrl = avatarUrl,
                            isUploadingAvatar = false,
                        )
                    }
                }.onFailure { error ->
                    log.warn("upload_avatar", error)
                    _uiState.update {
                        it.copy(
                            isUploadingAvatar = false,
                            error = EditProfileError.AVATAR_UPLOAD_FAILED,
                        )
                    }
                }
        }
    }

    fun saveProfile() {
        val state = _uiState.value
        if (state.isSaving || state.isUploadingAvatar) return

        val displayName = state.displayName.trim()
        if (displayName.isBlank()) {
            _uiState.update { it.copy(error = EditProfileError.NAME_REQUIRED) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            runSuspendCatching {
                profileRepository.updateProfile(
                    userId = user.id,
                    displayName = displayName,
                    bio = profile?.bio.orEmpty(),
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        displayName = displayName,
                        isSaving = false,
                        saveSucceeded = true,
                    )
                }
            }.onFailure { error ->
                log.warn("save_profile", error)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = EditProfileError.SAVE_FAILED,
                    )
                }
            }
        }
    }

    fun consumeSaveSuccess() {
        _uiState.update { it.copy(saveSucceeded = false) }
    }

    companion object {
        fun factory(
            user: GallrUser,
            profile: Profile?,
            profileRepository: ProfileRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EditProfileViewModel(
                        user = user,
                        profile = profile,
                        profileRepository = profileRepository,
                    )
                }
            }

        private fun initialDisplayName(
            user: GallrUser,
            profile: Profile?,
        ): String =
            profile?.displayName?.takeIf { it.isNotBlank() }
                ?: user.displayName.takeIf { it.isNotBlank() }.orEmpty()
    }
}
