package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.AuthRepository
import com.gallr.shared.repository.OAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The auth flow must outlive the composition: after a configuration change the host keeps its ViewModelStore and
 * rebuilds the UI, and the ViewModels retained in that store must still see a sign-in that happens afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRecreatedOwnerGetsTheSameAuthFlow() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val repository = FakeAuthRepository()

            val beforeRecreation = authSession(store, repository)
            val afterRecreation = authSession(store, repository)

            assertSame(beforeRecreation, afterRecreation)
            assertSame(beforeRecreation.authState, afterRecreation.authState)
            store.clear()
        }

    @Test
    fun aSignInAfterRecreationReachesAWatcherOfTheOriginalFlow() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val repository = FakeAuthRepository()
            val retainedWatcher = authSession(store, repository).authState
            val seen = mutableListOf<AuthState>()
            val watching = backgroundScope.launch { retainedWatcher.collect { seen += it } }

            authSession(store, repository)
            val signedIn = AuthState.Authenticated(GallrUser("account-1", "hanshin", null))
            repository.state.value = signedIn

            assertEquals(signedIn, retainedWatcher.value)
            assertEquals(listOf(AuthState.Anonymous, signedIn), seen)
            watching.cancel()
            store.clear()
        }

    private fun authSession(
        store: ViewModelStore,
        repository: AuthRepository,
    ): AuthSessionViewModel =
        ViewModelProvider.create(store, AuthSessionViewModel.factory(repository))[AuthSessionViewModel::class]

    private class FakeAuthRepository : AuthRepository {
        val state = MutableStateFlow<AuthState>(AuthState.Anonymous)

        override fun observeAuthState(): Flow<AuthState> = state

        override suspend fun signUpWithEmail(
            email: String,
            password: String,
        ) = error("not used here")

        override suspend fun signInWithEmail(
            email: String,
            password: String,
        ) = error("not used here")

        override suspend fun resetPassword(email: String) = error("not used here")

        override suspend fun signInWithOAuth(provider: OAuthProvider) = error("not used here")

        override suspend fun signOut() = error("not used here")

        override suspend fun deleteAccount() = error("not used here")
    }
}
