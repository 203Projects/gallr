package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.repository.AuthRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Owns the app's one [AuthState] flow for as long as the host's ViewModelStore lives.
 *
 * Every ViewModel that takes `authState` is retained across a configuration change (dark mode, font scale, locale,
 * multi-window) while the composition that created it is rebuilt. A flow created in the composition would be replaced
 * on recreation and the retained ViewModels would keep watching the old one, never seeing a later sign-in or sign-out.
 * Held here, the flow is the same object before and after recreation, so the session reaches every watcher.
 */
class AuthSessionViewModel(
    authRepository: AuthRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> =
        authRepository
            .observeAuthState()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { AuthSessionViewModel(authRepository) }
            }
    }
}
