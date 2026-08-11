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
class MyThoughtsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initial_failure_is_not_exposed_as_a_successful_empty_list() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(
                    userThoughtsResult = Result.failure(IllegalStateException("offline")),
                )

            val viewModel = MyThoughtsViewModel("user-1", repository)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.loadFailed)
            assertFalse(viewModel.uiState.value.hasLoaded)
            assertEquals(emptyList(), viewModel.uiState.value.thoughts)
        }

    @Test
    fun failed_delete_preserves_content_and_a_retry_can_remove_it() =
        runTest(dispatcher) {
            val repository =
                FakeThoughtRepository(
                    userThoughtsResult = Result.success(listOf(thought("thought-1"))),
                    deleteResult = Result.failure(IllegalStateException("offline")),
                )
            val viewModel = MyThoughtsViewModel("user-1", repository)
            advanceUntilIdle()

            viewModel.deleteThought("thought-1")
            advanceUntilIdle()

            assertEquals(
                listOf("thought-1"),
                viewModel.uiState.value.thoughts
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.mutationFailed)

            repository.deleteResult = Result.success(Unit)
            viewModel.deleteThought("thought-1")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.thoughts
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.mutationFailed)
        }
}
