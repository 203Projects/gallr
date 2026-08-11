package com.gallr.app.viewmodel

import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
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
class ProfileViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val user = GallrUser(id = "user-1", displayName = "User", avatarUrl = null)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_profile_thought_summary_and_admin_count_as_one_state() =
        runTest(dispatcher) {
            val profile = Profile("user-1", "User", null, "", isAdmin = true)
            val profileRepository = FakeProfileRepository(profileResult = Result.success(profile))
            val thoughtRepository =
                FakeThoughtRepository(
                    userThoughtsResult =
                        Result.success(
                            listOf(thought("thought-1", "exhibition-1"), thought("thought-2", "exhibition-2")),
                        ),
                    pendingThoughtsResult = Result.success(listOf(thought("pending-1"))),
                )

            val viewModel = ProfileViewModel(user, profileRepository, thoughtRepository)
            advanceUntilIdle()

            assertEquals(profile, viewModel.uiState.value.profile)
            assertEquals(setOf("exhibition-1", "exhibition-2"), viewModel.uiState.value.thoughtExhibitionIds)
            assertEquals(2, viewModel.uiState.value.thoughtCount)
            assertEquals(1, viewModel.uiState.value.pendingCount)
            assertTrue(viewModel.uiState.value.hasLoaded)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun failed_refresh_preserves_previous_content_until_retry_succeeds() =
        runTest(dispatcher) {
            val originalProfile = Profile("user-1", "Original", null, "", isAdmin = false)
            val updatedProfile = originalProfile.copy(displayName = "Updated")
            val profileRepository = FakeProfileRepository(profileResult = Result.success(originalProfile))
            val thoughtRepository =
                FakeThoughtRepository(
                    userThoughtsResult = Result.success(listOf(thought("thought-1"))),
                )
            val viewModel = ProfileViewModel(user, profileRepository, thoughtRepository)
            advanceUntilIdle()

            thoughtRepository.userThoughtsResult = Result.failure(IllegalStateException("offline"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(originalProfile, viewModel.uiState.value.profile)
            assertEquals(1, viewModel.uiState.value.thoughtCount)
            assertTrue(viewModel.uiState.value.loadFailed)

            profileRepository.profileResult = Result.success(updatedProfile)
            thoughtRepository.userThoughtsResult = Result.success(listOf(thought("thought-1"), thought("thought-2")))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(updatedProfile, viewModel.uiState.value.profile)
            assertEquals(2, viewModel.uiState.value.thoughtCount)
            assertFalse(viewModel.uiState.value.loadFailed)
        }
}
