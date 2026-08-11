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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PendingThoughtsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failed_refresh_preserves_the_last_successful_review_queue() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(
                    pendingThoughtsResult = Result.success(listOf(thought("pending-1"))),
                )
            val viewModel = PendingThoughtsViewModel(repository)
            advanceUntilIdle()

            repository.pendingThoughtsResult = Result.failure(IllegalStateException("offline"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                listOf("pending-1"),
                viewModel.uiState.value.thoughts
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun failed_review_preserves_content_and_success_removes_only_the_reviewed_item() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(
                    pendingThoughtsResult = Result.success(listOf(thought("pending-1"), thought("pending-2"))),
                    approveResult = Result.failure(IllegalStateException("offline")),
                )
            val viewModel = PendingThoughtsViewModel(repository)
            advanceUntilIdle()

            viewModel.approveThought("pending-1")
            advanceUntilIdle()

            assertEquals(
                listOf("pending-1", "pending-2"),
                viewModel.uiState.value.thoughts
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.mutationFailed)

            repository.approveResult = Result.success(Unit)
            viewModel.approveThought("pending-1")
            advanceUntilIdle()

            assertEquals(
                listOf("pending-2"),
                viewModel.uiState.value.thoughts
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.mutationFailed)
        }
}
