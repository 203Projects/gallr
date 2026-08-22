package com.gallr.app.viewmodel

import com.gallr.shared.repository.OAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalid_input_is_rejected_before_repository_access() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = SignInViewModel(repository)

            viewModel.updateEmail("not-an-email")
            viewModel.updatePassword("short")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(SignInError.INVALID_EMAIL, viewModel.uiState.value.error)
            assertTrue(repository.signIns.isEmpty())

            viewModel.updateEmail("person@example.com")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(SignInError.PASSWORD_TOO_SHORT, viewModel.uiState.value.error)
            assertTrue(repository.signIns.isEmpty())
        }

    @Test
    fun sign_up_trims_email_and_transitions_to_verification() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = SignInViewModel(repository)

            viewModel.toggleSignInMode()
            viewModel.updateEmail("  person@example.com  ")
            viewModel.updatePassword("password-123")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(listOf("person@example.com" to "password-123"), repository.signUps)
            assertEquals(SignInMode.VERIFICATION_SENT, viewModel.uiState.value.mode)
            assertEquals("person@example.com", viewModel.uiState.value.verifiedEmail)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun backend_details_are_mapped_to_stable_errors() =
        runTest(dispatcher) {
            val repository =
                FakeAuthRepository(
                    signInResult = Result.failure(IllegalStateException("invalid login credentials: trace-123")),
                )
            val viewModel = SignInViewModel(repository)

            viewModel.updateEmail("person@example.com")
            viewModel.updatePassword("password-123")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(SignInError.INVALID_CREDENTIALS, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)

            repository.signInResult = Result.failure(IllegalStateException("private backend detail"))
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(SignInError.GENERIC, viewModel.uiState.value.error)
        }

    @Test
    fun signup_disabled_is_distinct_for_email_and_oauth() =
        runTest(dispatcher) {
            val repository =
                FakeAuthRepository(
                    signUpResult =
                        Result.failure(
                            IllegalStateException("422: Signups not allowed for this instance"),
                        ),
                )
            val viewModel = SignInViewModel(repository)

            viewModel.toggleSignInMode()
            viewModel.updateEmail("person@example.com")
            viewModel.updatePassword("password-123")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(SignInError.SIGNUPS_DISABLED, viewModel.uiState.value.error)

            repository.oauthResult =
                Result.failure(IllegalStateException("signup_disabled"))
            viewModel.signInWithOAuth(OAuthProvider.GOOGLE)
            advanceUntilIdle()

            assertEquals(SignInError.SIGNUPS_DISABLED, viewModel.uiState.value.error)
        }

    @Test
    fun reset_and_oauth_actions_use_the_repository_boundary() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = SignInViewModel(repository)

            viewModel.showForgotPassword()
            viewModel.updateEmail("person@example.com")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(listOf("person@example.com"), repository.passwordResets)
            assertEquals(SignInMode.RESET_SENT, viewModel.uiState.value.mode)

            viewModel.backToSignIn()
            viewModel.signInWithOAuth(OAuthProvider.GOOGLE)
            advanceUntilIdle()

            assertEquals(listOf(OAuthProvider.GOOGLE), repository.oauthProviders)
        }

    @Test
    fun failed_resend_stays_on_the_confirmation_screen() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = SignInViewModel(repository)
            viewModel.showForgotPassword()
            viewModel.updateEmail("person@example.com")
            viewModel.submit()
            advanceUntilIdle()

            repository.resetResult = Result.failure(IllegalStateException("backend detail"))
            viewModel.resend()
            advanceUntilIdle()

            assertEquals(SignInMode.RESET_SENT, viewModel.uiState.value.mode)
            assertEquals(SignInError.RESEND_FAILED, viewModel.uiState.value.error)
        }

    @Test
    fun authentication_transition_can_clear_sensitive_form_state() {
        val viewModel = SignInViewModel(FakeAuthRepository())
        viewModel.updateEmail("person@example.com")
        viewModel.updatePassword("password-123")

        viewModel.clearSensitiveState()

        assertEquals("", viewModel.uiState.value.email)
        assertEquals("", viewModel.uiState.value.password)
        assertEquals(SignInMode.SIGN_IN, viewModel.uiState.value.mode)
    }
}
