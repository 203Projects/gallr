package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorDetailViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 6, 23)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun editor(id: String) =
        Editor(
            id = id,
            nameKo = id,
            nameEn = id,
            titleKo = id,
            titleEn = id,
            bioKo = id,
            bioEn = id,
            isActive = true,
            activeFrom = LocalDate(2026, 1, 1),
            activeTo = null,
        )

    private fun exhibition(
        id: String,
        editorId: String?,
        openingDate: LocalDate = LocalDate(2026, 5, 1),
        closingDate: LocalDate = LocalDate(2099, 7, 1),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "V",
        venueNameEn = "V",
        cityKo = "C",
        cityEn = "C",
        regionKo = "R",
        regionEn = "R",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
    )

    private var reloadExhibitionsCalls = 0

    /**
     * Build a VM and keep a subscriber on its state so WhileSubscribed
     * activates the upstream `combine`. Returns after advancing the dispatcher.
     */
    private fun TestScope.buildAndSubscribe(
        editorId: String,
        repo: FakeEditorRepository,
        allExhibitions: ExhibitionListState = ExhibitionListState.Success(emptyList()),
    ): EditorDetailViewModel = buildAndSubscribe(editorId, repo, MutableStateFlow(allExhibitions))

    private fun TestScope.buildAndSubscribe(
        editorId: String,
        repo: FakeEditorRepository,
        allExhibitions: MutableStateFlow<ExhibitionListState>,
    ): EditorDetailViewModel {
        val vm =
            EditorDetailViewModel(
                editorId = editorId,
                editorRepository = repo,
                allExhibitionsFlow = allExhibitions,
                language = MutableStateFlow(AppLanguage.EN),
                reloadExhibitions = { reloadExhibitionsCalls += 1 },
                todayProvider = { today },
            )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun EditorDetailViewModel.content() = assertIs<EditorDetailUiState.Content>(state.value)

    private fun EditorDetailViewModel.exhibitionIds(): List<String> = content().exhibitions.map { it.id }

    @Test
    fun `loadEditor success shows the editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val target = editor("alice")
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(target)),
                )

            val vm = buildAndSubscribe("alice", repo)

            assertEquals(target, vm.content().editor)
            assertEquals(listOf("alice"), repo.getEditorByIdCalls)
        }

    @Test
    fun `loadEditor failure is an error, not an empty list, and retry recovers`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.failure(RuntimeException("404"))),
                )
            val allExhibitions =
                listOf(exhibition("e1", editorId = "alice"))

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))
            assertEquals(EditorDetailUiState.Error, vm.state.value)

            repo.editorByIdResults = mapOf("alice" to Result.success(editor("alice")))
            vm.retry()
            advanceUntilIdle()

            assertEquals(listOf("e1"), vm.exhibitionIds())
            assertEquals(0, reloadExhibitionsCalls)
        }

    @Test
    fun `a catalogue failure is an error and retry reloads the catalogue`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )
            val catalogue = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Error("network"))

            val vm = buildAndSubscribe("alice", repo, catalogue)
            assertEquals(EditorDetailUiState.Error, vm.state.value)

            vm.retry()
            assertEquals(1, reloadExhibitionsCalls)

            catalogue.value = ExhibitionListState.Success(listOf(exhibition("e1", editorId = "alice")))
            assertEquals(listOf("e1"), vm.exhibitionIds())
        }

    @Test
    fun `the page is loading while the catalogue loads`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Loading)

            assertEquals(EditorDetailUiState.Loading, vm.state.value)
        }

    @Test
    fun `loadEditor returning null shows the list without an editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(null)),
                )

            val vm = buildAndSubscribe("alice", repo)

            assertNull(vm.content().editor)
        }

    @Test
    fun `exhibitions flow filters allExhibitions by editorId match`() =
        runTest(UnconfinedTestDispatcher()) {
            val allExhibitions =
                listOf(
                    exhibition("e1", editorId = "alice"),
                    exhibition("e2", editorId = "alice"),
                    exhibition("e3", editorId = "bob"),
                    exhibition("e4", editorId = null),
                )
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

            val filtered = vm.exhibitionIds()
            assertEquals(2, filtered.size)
            assertEquals(setOf("e1", "e2"), filtered.toSet())
        }

    @Test
    fun `exhibitions flow hides expired exhibitions for the editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val allExhibitions =
                listOf(
                    exhibition("active", editorId = "alice", closingDate = LocalDate(2099, 7, 1)),
                    exhibition("expired", editorId = "alice", closingDate = LocalDate(2020, 1, 1)),
                    exhibition("other", editorId = "bob", closingDate = LocalDate(2099, 7, 1)),
                )
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

            assertEquals(listOf("active"), vm.exhibitionIds())
        }

    @Test
    fun `exhibitions flow hides far-future upcoming exhibitions for the editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val allExhibitions =
                listOf(
                    exhibition(
                        "visible-upcoming",
                        editorId = "alice",
                        openingDate = LocalDate(2026, 7, 7),
                        closingDate = LocalDate(2026, 8, 1),
                    ),
                    exhibition(
                        "hidden-upcoming",
                        editorId = "alice",
                        openingDate = LocalDate(2026, 7, 8),
                        closingDate = LocalDate(2026, 8, 1),
                    ),
                )
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

            assertEquals(listOf("visible-upcoming"), vm.exhibitionIds())
        }

    @Test
    fun `exhibitions flow returns empty when no exhibitions match the editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val allExhibitions =
                listOf(
                    exhibition("e1", editorId = "bob"),
                    exhibition("e2", editorId = null),
                )
            val repo =
                FakeEditorRepository(
                    editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
                )

            val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

            assertTrue(vm.exhibitionIds().isEmpty())
        }
}
