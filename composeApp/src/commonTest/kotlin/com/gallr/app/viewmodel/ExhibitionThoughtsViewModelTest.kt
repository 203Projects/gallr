package com.gallr.app.viewmodel

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExhibitionThoughtsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initial_failure_is_not_exposed_as_successful_empty_content() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(
                    exhibitionThoughtsResult = Result.failure(IllegalStateException("offline")),
                )

            val viewModel = ExhibitionThoughtsViewModel("exhibition-1", "user-1", repository)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loadFailed)
            assertFalse(viewModel.uiState.value.hasLoaded)
            assertEquals(emptyList(), viewModel.uiState.value.thoughts)
            assertNull(viewModel.uiState.value.ownPendingThought)
        }

    @Test
    fun failed_refresh_preserves_the_last_complete_snapshot() =
        runTest(dispatcher) {
            val approvedThought = thought("approved-1")
            val pendingThought = thought("pending-1").copy(isApproved = false)
            val repository =
                FakeThoughtRepository(
                    exhibitionThoughtsResult = Result.success(listOf(approvedThought)),
                    userExhibitionThoughtResult = Result.success(pendingThought),
                )
            val viewModel = ExhibitionThoughtsViewModel("exhibition-1", "user-1", repository)
            advanceUntilIdle()

            repository.userExhibitionThoughtResult = Result.failure(IllegalStateException("offline"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(approvedThought), viewModel.uiState.value.thoughts)
            assertEquals(pendingThought, viewModel.uiState.value.ownPendingThought)
            assertTrue(viewModel.uiState.value.hasUserThought)
            assertTrue(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun failed_delete_preserves_content_and_success_removes_only_the_target() =
        runTest(dispatcher) {
            val ownThought = thought("own-1")
            val otherThought = thought("other-1").copy(userId = "user-2")
            val repository =
                FakeThoughtRepository(
                    exhibitionThoughtsResult = Result.success(listOf(ownThought, otherThought)),
                    deleteResult = Result.failure(IllegalStateException("offline")),
                )
            val viewModel = ExhibitionThoughtsViewModel("exhibition-1", "user-1", repository)
            advanceUntilIdle()

            viewModel.deleteThought("own-1")
            advanceUntilIdle()

            assertEquals(listOf(ownThought, otherThought), viewModel.uiState.value.thoughts)
            assertTrue(viewModel.uiState.value.hasUserThought)
            assertTrue(viewModel.uiState.value.mutationFailed)

            repository.deleteResult = Result.success(Unit)
            viewModel.deleteThought("own-1")
            advanceUntilIdle()

            assertEquals(listOf(otherThought), viewModel.uiState.value.thoughts)
            assertFalse(viewModel.uiState.value.hasUserThought)
            assertFalse(viewModel.uiState.value.mutationFailed)
        }
}
