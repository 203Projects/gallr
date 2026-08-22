package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.OAuthProvider
import com.gallr.shared.util.Validators
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInMode {
    SIGN_IN,
    SIGN_UP,
    FORGOT_PASSWORD,
    VERIFICATION_SENT,
    RESET_SENT,
}

enum class SignInError {
    INVALID_EMAIL,
    PASSWORD_TOO_SHORT,
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    EMAIL_ALREADY_REGISTERED,
    RATE_LIMITED,
    SIGNUPS_DISABLED,
    GOOGLE_SIGN_IN_FAILED,
    APPLE_SIGN_IN_FAILED,
    RESEND_FAILED,
    GENERIC,
}

data class SignInUiState(
    val mode: SignInMode = SignInMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val verifiedEmail: String = "",
    val isLoading: Boolean = false,
    val error: SignInError? = null,
)

class SignInViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val log = AppLog.tagged("SignInViewModel")
    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun updateEmail(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun toggleSignInMode() {
        if (_uiState.value.isLoading) return

        _uiState.update { state ->
            state.copy(
                mode =
                    if (state.mode == SignInMode.SIGN_IN) {
                        SignInMode.SIGN_UP
                    } else {
                        SignInMode.SIGN_IN
                    },
                error = null,
            )
        }
    }

    fun showForgotPassword() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(mode = SignInMode.FORGOT_PASSWORD, error = null) }
    }

    fun backToSignIn() {
        if (_uiState.value.isLoading) return
        _uiState.update {
            it.copy(
                mode = SignInMode.SIGN_IN,
                password = "",
                verifiedEmail = "",
                error = null,
            )
        }
    }

    fun clearSensitiveState() {
        _uiState.value = SignInUiState()
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return

        val email = state.email.trim()
        if (!Validators.isValidEmail(email)) {
            _uiState.update { it.copy(error = SignInError.INVALID_EMAIL) }
            return
        }

        if (state.mode == SignInMode.FORGOT_PASSWORD) {
            performAuthOperation(
                operation = "send_password_reset",
                action = { authRepository.resetPassword(email) },
                onSuccess = {
                    it.copy(
                        mode = SignInMode.RESET_SENT,
                        verifiedEmail = email,
                    )
                },
            )
            return
        }

        if (!Validators.isValidPassword(state.password)) {
            _uiState.update { it.copy(error = SignInError.PASSWORD_TOO_SHORT) }
            return
        }

        if (state.mode == SignInMode.SIGN_UP) {
            performAuthOperation(
                operation = "sign_up_with_email",
                action = { authRepository.signUpWithEmail(email, state.password) },
                onSuccess = {
                    it.copy(
                        mode = SignInMode.VERIFICATION_SENT,
                        verifiedEmail = email,
                    )
                },
            )
        } else if (state.mode == SignInMode.SIGN_IN) {
            performAuthOperation(
                operation = "sign_in_with_email",
                action = { authRepository.signInWithEmail(email, state.password) },
            )
        }
    }

    fun signInWithOAuth(provider: OAuthProvider) {
        if (_uiState.value.isLoading) return

        performAuthOperation(
            operation =
                when (provider) {
                    OAuthProvider.GOOGLE -> "sign_in_with_google"
                    OAuthProvider.APPLE -> "sign_in_with_apple"
                },
            failure = {
                if (isSignupDisabledError(it)) {
                    SignInError.SIGNUPS_DISABLED
                } else {
                    when (provider) {
                        OAuthProvider.GOOGLE -> SignInError.GOOGLE_SIGN_IN_FAILED
                        OAuthProvider.APPLE -> SignInError.APPLE_SIGN_IN_FAILED
                    }
                }
            },
            action = { authRepository.signInWithOAuth(provider) },
        )
    }

    fun resend() {
        val state = _uiState.value
        if (state.isLoading || state.verifiedEmail.isBlank()) return

        when (state.mode) {
            SignInMode.RESET_SENT -> {
                performAuthOperation(
                    operation = "resend_password_reset",
                    failure = { SignInError.RESEND_FAILED },
                    action = { authRepository.resetPassword(state.verifiedEmail) },
                )
            }

            SignInMode.VERIFICATION_SENT -> {
                performAuthOperation(
                    operation = "resend_verification",
                    failure = { SignInError.RESEND_FAILED },
                    action = { authRepository.signUpWithEmail(state.verifiedEmail, state.password) },
                )
            }

            else -> {
                return
            }
        }
    }

    private fun performAuthOperation(
        operation: String,
        failure: (Throwable) -> SignInError = ::classifyAuthError,
        action: suspend () -> Unit,
        onSuccess: (SignInUiState) -> SignInUiState = { it },
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runSuspendCatching { action() }
                .onSuccess {
                    _uiState.update { state ->
                        onSuccess(state).copy(isLoading = false)
                    }
                }.onFailure { error ->
                    log.warn(operation, error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = failure(error),
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SignInViewModel(authRepository) }
            }

        private fun classifyAuthError(error: Throwable): SignInError {
            val message = error.message?.lowercase().orEmpty()
            return when {
                isSignupDisabledError(error) -> {
                    SignInError.SIGNUPS_DISABLED
                }

                "invalid login credentials" in message || "invalid_credentials" in message -> {
                    SignInError.INVALID_CREDENTIALS
                }

                "email not confirmed" in message -> {
                    SignInError.EMAIL_NOT_CONFIRMED
                }

                "user already registered" in message || "already registered" in message -> {
                    SignInError.EMAIL_ALREADY_REGISTERED
                }

                "rate limit" in message || "too many requests" in message -> {
                    SignInError.RATE_LIMITED
                }

                else -> {
                    SignInError.GENERIC
                }
            }
        }

        private fun isSignupDisabledError(error: Throwable): Boolean {
            val message = error.message?.lowercase().orEmpty()
            return "signup_disabled" in message || "signups not allowed" in message
        }
    }
}
