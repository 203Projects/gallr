package com.gallr.app.viewmodel

import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.data.model.Profile
import com.gallr.shared.diary.OwnPublicNote
import com.gallr.shared.diary.PublicNoteModeration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val user = GallrUser(id = "user-1", displayName = "User", avatarUrl = null)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Spec 103 US2: the 감상 count is the author's own public notes from the account archive, whatever their review
     * status; the old per-user and pending-queue reads are gone, and a staff account sees no queue (US5 scenario 8).
     */
    @Test
    fun theNoteCountIsEveryOwnPublicNoteOfAnyStatusFromTheArchive() =
        runTest(dispatcher) {
            val profile = Profile("user-1", "User", null, "", isAdmin = true)
            val notes =
                FakePublicNoteRepository(
                    listOf(
                        note("a", PublicNoteModeration.PENDING),
                        note("b", PublicNoteModeration.APPROVED),
                        note("c", PublicNoteModeration.REJECTED),
                    ),
                )

            val viewModel = ProfileViewModel(user, FakeProfileRepository(Result.success(profile)), notes)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(profile, viewModel.uiState.value.profile)
            assertEquals(3, viewModel.uiState.value.publicNoteCount)
            assertTrue(viewModel.uiState.value.hasLoaded)

            notes.notes.value = notes.notes.value.drop(1)
            assertEquals(2, viewModel.uiState.value.publicNoteCount)
        }

    @Test
    fun failed_refresh_preserves_previous_content_until_retry_succeeds() =
        runTest(dispatcher) {
            val originalProfile = Profile("user-1", "Original", null, "", isAdmin = false)
            val updatedProfile = originalProfile.copy(displayName = "Updated")
            val profileRepository = FakeProfileRepository(profileResult = Result.success(originalProfile))
            val viewModel =
                ProfileViewModel(user, profileRepository, FakePublicNoteRepository(listOf(note("a"))))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            profileRepository.profileResult = Result.failure(IllegalStateException("offline"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(originalProfile, viewModel.uiState.value.profile)
            assertEquals(1, viewModel.uiState.value.publicNoteCount)
            assertTrue(viewModel.uiState.value.loadFailed)

            profileRepository.profileResult = Result.success(updatedProfile)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(updatedProfile, viewModel.uiState.value.profile)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    private fun note(
        id: String,
        moderation: PublicNoteModeration = PublicNoteModeration.APPROVED,
    ) = OwnPublicNote(
        exhibitionId = "exhibition-$id",
        thoughtId = "thought-$id",
        content = "감상 $id",
        moderation = moderation,
        rejectionReason = null,
        requestedAt = Instant.parse("2026-11-01T00:00:00Z"),
        approvedAt = null,
        approvalSeen = true,
    )
}
