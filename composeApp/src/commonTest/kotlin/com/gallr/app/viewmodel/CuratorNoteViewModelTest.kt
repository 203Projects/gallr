package com.gallr.app.viewmodel

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.repository.CuratorNoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CuratorNoteViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theNoteOfTheExhibitionIsReady() =
        runTest(dispatcher) {
            val viewModel = CuratorNoteViewModel("a", NotesOf(mapOf("a" to note("a"))))
            backgroundScope.launch { viewModel.state.collect {} }

            assertEquals(CuratorNoteUiState.Ready(note("a")), viewModel.state.value)
        }

    @Test
    fun anExhibitionWithoutANoteIsReadyWithNothing() =
        runTest(dispatcher) {
            val viewModel = CuratorNoteViewModel("b", NotesOf(mapOf("a" to note("a"))))
            backgroundScope.launch { viewModel.state.collect {} }

            assertEquals(CuratorNoteUiState.Ready(null), viewModel.state.value)
        }

    @Test
    fun aFailedReadIsAnErrorNotAPlaceholder() =
        runTest(dispatcher) {
            val viewModel = CuratorNoteViewModel("a", Failing)
            backgroundScope.launch { viewModel.state.collect {} }

            assertIs<CuratorNoteUiState.Error>(viewModel.state.value)
        }

    private class NotesOf(
        private val notes: Map<String, CuratorNote>,
    ) : CuratorNoteRepository {
        override suspend fun getPublishedNotes(): Result<List<CuratorNote>> = Result.success(notes.values.toList())

        override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> = Result.success(notes[exhibitionId])
    }

    private object Failing : CuratorNoteRepository {
        override suspend fun getPublishedNotes(): Result<List<CuratorNote>> =
            Result.failure(IllegalStateException("offline"))

        override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> =
            Result.failure(IllegalStateException("offline"))
    }

    private fun note(exhibitionId: String) =
        CuratorNote(
            exhibitionId = exhibitionId,
            versionNumber = 1,
            hookKo = "훅",
            hookEn = "",
            insightsKo = listOf("하나", "둘"),
            insightsEn = emptyList(),
            tipKo = "팁",
            tipEn = "",
            approvedAt = Instant.parse("2026-10-11T03:00:00Z"),
        )
}
