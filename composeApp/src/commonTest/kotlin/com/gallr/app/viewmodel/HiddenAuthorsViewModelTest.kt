package com.gallr.app.viewmodel

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

/** Spec 103 FR-024: Settings → 숨긴 사용자 lists the hidden people and brings them back, for guests too. */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenAuthorsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun listsTheHiddenPeopleByNameInTheOrderTheyWereHidden() =
        runTest(dispatcher) {
            val repository =
                FakeHiddenThoughtAuthorRepository(listOf(hiddenAuthor("a", "민지"), hiddenAuthor("b", "")))
            val viewModel = HiddenAuthorsViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), viewModel.state.value.authors.map { it.userId })
            assertEquals(listOf("민지", ""), viewModel.state.value.authors.map { it.displayName })
            assertTrue(viewModel.state.value.hasLoaded)
        }

    @Test
    fun unhideRemovesOnlyThatPerson() =
        runTest(dispatcher) {
            val repository = FakeHiddenThoughtAuthorRepository(listOf(hiddenAuthor("a"), hiddenAuthor("b")))
            val viewModel = HiddenAuthorsViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.unhide("a")
            advanceUntilIdle()

            assertEquals(listOf("b"), viewModel.state.value.authors.map { it.userId })
            assertEquals(listOf("b"), repository.authors.value.map { it.userId })
        }

    @Test
    fun aFailedUnhideKeepsThePersonAndSaysSoUntilTheNextTry() =
        runTest(dispatcher) {
            val repository = FakeHiddenThoughtAuthorRepository(listOf(hiddenAuthor("a")))
            val viewModel = HiddenAuthorsViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }

            repository.failure = IllegalStateException("disk")
            viewModel.unhide("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), viewModel.state.value.authors.map { it.userId })
            assertTrue(viewModel.state.value.unhideFailed)

            repository.failure = null
            viewModel.unhide("a")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.unhideFailed)
            assertEquals(emptyList(), viewModel.state.value.authors)
        }
}
