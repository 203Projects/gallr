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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val user = GallrUser(id = "user-1", displayName = "Auth Name", avatarUrl = "auth-avatar")
    private val profile =
        Profile(
            id = "user-1",
            displayName = "Profile Name",
            avatarUrl = "profile-avatar",
            bio = "Bio",
            isAdmin = false,
        )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initializes_editable_values_from_the_profile() {
        val viewModel = EditProfileViewModel(user, profile, FakeProfileRepository())

        assertEquals("Profile Name", viewModel.uiState.value.displayName)
        assertEquals("profile-avatar", viewModel.uiState.value.avatarUrl)
    }

    @Test
    fun blank_name_is_rejected_without_calling_the_repository() =
        runTest(dispatcher) {
            val repository = FakeProfileRepository()
            val viewModel = EditProfileViewModel(user, profile, repository)

            viewModel.updateDisplayName("   ")
            viewModel.saveProfile()
            advanceUntilIdle()

            assertEquals(EditProfileError.NAME_REQUIRED, viewModel.uiState.value.error)
            assertTrue(repository.updates.isEmpty())
        }

    @Test
    fun failed_save_preserves_input_and_exposes_a_stable_error() =
        runTest(dispatcher) {
            val repository =
                FakeProfileRepository(
                    updateResult = Result.failure(IllegalStateException("backend detail")),
                )
            val viewModel = EditProfileViewModel(user, profile, repository)

            viewModel.updateDisplayName("  New Name  ")
            viewModel.saveProfile()
            advanceUntilIdle()

            assertEquals("  New Name  ", viewModel.uiState.value.displayName)
            assertEquals(EditProfileError.SAVE_FAILED, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isSaving)
            assertFalse(viewModel.uiState.value.saveSucceeded)

            repository.updateResult = Result.success(Unit)
            viewModel.saveProfile()
            advanceUntilIdle()

            assertEquals(ProfileUpdate("user-1", "New Name", "Bio"), repository.updates.single())
            assertTrue(viewModel.uiState.value.saveSucceeded)
            assertEquals(null, viewModel.uiState.value.error)
        }

    @Test
    fun avatar_failure_preserves_the_previous_image_and_retry_updates_it() =
        runTest(dispatcher) {
            val imageBytes = byteArrayOf(1, 2, 3)
            val repository =
                FakeProfileRepository(
                    avatarResult = Result.failure(IllegalStateException("storage detail")),
                )
            val viewModel = EditProfileViewModel(user, profile, repository)

            viewModel.uploadAvatar(imageBytes)
            advanceUntilIdle()

            assertEquals("profile-avatar", viewModel.uiState.value.avatarUrl)
            assertEquals(EditProfileError.AVATAR_UPLOAD_FAILED, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isUploadingAvatar)

            repository.avatarResult = Result.success("new-avatar")
            viewModel.uploadAvatar(imageBytes)
            advanceUntilIdle()

            assertEquals("new-avatar", viewModel.uiState.value.avatarUrl)
            assertEquals(null, viewModel.uiState.value.error)
            assertContentEquals(imageBytes, repository.avatarUploads.single().second)
        }
}
