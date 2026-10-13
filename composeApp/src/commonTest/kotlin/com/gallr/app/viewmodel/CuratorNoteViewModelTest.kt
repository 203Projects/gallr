package com.gallr.app.viewmodel

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.ExhibitionImage
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
    fun theExhibitionsPicturesAreReadBesideTheNote() =
        runTest(dispatcher) {
            val pictures = listOf(image("one"), image("two"))
            val viewModel = CuratorNoteViewModel("a", NotesOf(mapOf("a" to note("a")), images = mapOf("a" to pictures)))
            backgroundScope.launch { viewModel.state.collect {} }
            backgroundScope.launch { viewModel.images.collect {} }

            assertEquals(CuratorNoteUiState.Ready(note("a")), viewModel.state.value)
            assertEquals(pictures, viewModel.images.value)
        }

    @Test
    fun anExhibitionWithoutANoteStillHasItsPictures() =
        runTest(dispatcher) {
            val pictures = listOf(image("one"))
            val viewModel = CuratorNoteViewModel("b", NotesOf(emptyMap(), images = mapOf("b" to pictures)))
            backgroundScope.launch { viewModel.state.collect {} }
            backgroundScope.launch { viewModel.images.collect {} }

            assertEquals(CuratorNoteUiState.Ready(null), viewModel.state.value)
            assertEquals(pictures, viewModel.images.value)
        }

    @Test
    fun picturesThatCannotBeReadLeaveTheNoteAsItIs() =
        runTest(dispatcher) {
            val viewModel = CuratorNoteViewModel("a", NotesOf(mapOf("a" to note("a")), imagesFail = true))
            backgroundScope.launch { viewModel.state.collect {} }
            backgroundScope.launch { viewModel.images.collect {} }

            assertEquals(CuratorNoteUiState.Ready(note("a")), viewModel.state.value)
            assertEquals(emptyList(), viewModel.images.value)
        }

    @Test
    fun aNoteThatCannotBeReadLeavesThePictures() =
        runTest(dispatcher) {
            val repository = NotesOf(emptyMap(), images = mapOf("a" to listOf(image("one"))), noteFails = true)
            val viewModel = CuratorNoteViewModel("a", repository)
            backgroundScope.launch { viewModel.state.collect {} }
            backgroundScope.launch { viewModel.images.collect {} }

            assertIs<CuratorNoteUiState.Error>(viewModel.state.value)
            assertEquals(listOf(image("one")), viewModel.images.value)
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
        private val images: Map<String, List<ExhibitionImage>> = emptyMap(),
        private val imagesFail: Boolean = false,
        private val noteFails: Boolean = false,
    ) : CuratorNoteRepository {
        override suspend fun getPublishedNotes(): Result<List<CuratorNote>> = Result.success(notes.values.toList())

        override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> =
            if (noteFails) Result.failure(IllegalStateException("offline")) else Result.success(notes[exhibitionId])

        override suspend fun getGalleryImages(exhibitionId: String): Result<List<ExhibitionImage>> {
            if (imagesFail) return Result.failure(IllegalStateException("offline"))
            return Result.success(images[exhibitionId].orEmpty())
        }
    }

    private object Failing : CuratorNoteRepository {
        override suspend fun getPublishedNotes(): Result<List<CuratorNote>> =
            Result.failure(IllegalStateException("offline"))

        override suspend fun getNote(exhibitionId: String): Result<CuratorNote?> =
            Result.failure(IllegalStateException("offline"))

        override suspend fun getGalleryImages(exhibitionId: String): Result<List<ExhibitionImage>> =
            Result.failure(IllegalStateException("offline"))
    }

    private fun image(name: String) =
        ExhibitionImage(
            url = "https://cdn.example.invalid/$name.jpg",
            altKo = "",
            altEn = "",
            credit = "",
            width = null,
            height = null,
        )

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
