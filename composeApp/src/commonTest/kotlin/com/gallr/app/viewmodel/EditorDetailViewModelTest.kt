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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun editor(id: String) = Editor(
        id = id,
        nameKo = id, nameEn = id,
        titleKo = id, titleEn = id,
        bioKo = id, bioEn = id,
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
        nameKo = id, nameEn = id,
        venueNameKo = "V", venueNameEn = "V",
        cityKo = "C", cityEn = "C",
        regionKo = "R", regionEn = "R",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "",
        addressKo = "", addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
    )

    /**
     * Build a VM and subscribe to both state flows so WhileSubscribed
     * activates the upstream `map`. Returns after advancing the dispatcher.
     */
    private fun TestScope.buildAndSubscribe(
        editorId: String,
        repo: FakeEditorRepository,
        allExhibitions: ExhibitionListState = ExhibitionListState.Loading,
    ): EditorDetailViewModel {
        val vm = EditorDetailViewModel(
            editorId = editorId,
            editorRepository = repo,
            allExhibitionsFlow = MutableStateFlow(allExhibitions),
            language = MutableStateFlow(AppLanguage.EN),
            todayProvider = { today },
        )
        backgroundScope.launch { vm.exhibitions.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `loadEditor success sets editor flow`() = runTest(UnconfinedTestDispatcher()) {
        val target = editor("alice")
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(target)),
        )

        val vm = buildAndSubscribe("alice", repo)

        assertEquals(target, vm.editor.value)
        assertEquals(listOf("alice"), repo.getEditorByIdCalls)
    }

    @Test
    fun `loadEditor failure leaves editor flow null and does not crash`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.failure(RuntimeException("404"))),
        )

        val vm = buildAndSubscribe("alice", repo)

        assertNull(vm.editor.value)
    }

    @Test
    fun `loadEditor returning null leaves editor flow null`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(null)),
        )

        val vm = buildAndSubscribe("alice", repo)

        assertNull(vm.editor.value)
    }

    @Test
    fun `exhibitions flow filters allExhibitions by editorId match`() = runTest(UnconfinedTestDispatcher()) {
        val allExhibitions = listOf(
            exhibition("e1", editorId = "alice"),
            exhibition("e2", editorId = "alice"),
            exhibition("e3", editorId = "bob"),
            exhibition("e4", editorId = null),
        )
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
        )

        val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

        val filtered = vm.exhibitions.value
        assertEquals(2, filtered.size)
        assertEquals(setOf("e1", "e2"), filtered.map { it.id }.toSet())
    }

    @Test
    fun `exhibitions flow hides expired exhibitions for the editor`() = runTest(UnconfinedTestDispatcher()) {
        val allExhibitions = listOf(
            exhibition("active", editorId = "alice", closingDate = LocalDate(2099, 7, 1)),
            exhibition("expired", editorId = "alice", closingDate = LocalDate(2020, 1, 1)),
            exhibition("other", editorId = "bob", closingDate = LocalDate(2099, 7, 1)),
        )
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
        )

        val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

        assertEquals(listOf("active"), vm.exhibitions.value.map { it.id })
    }

    @Test
    fun `exhibitions flow hides far-future upcoming exhibitions for the editor`() = runTest(UnconfinedTestDispatcher()) {
        val allExhibitions = listOf(
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
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
        )

        val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

        assertEquals(listOf("visible-upcoming"), vm.exhibitions.value.map { it.id })
    }

    @Test
    fun `exhibitions flow returns empty when no exhibitions match the editor`() = runTest(UnconfinedTestDispatcher()) {
        val allExhibitions = listOf(
            exhibition("e1", editorId = "bob"),
            exhibition("e2", editorId = null),
        )
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
        )

        val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Success(allExhibitions))

        assertTrue(vm.exhibitions.value.isEmpty())
    }

    @Test
    fun `exhibitions flow returns empty during Loading state`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(
            editorByIdResults = mapOf("alice" to Result.success(editor("alice"))),
        )

        val vm = buildAndSubscribe("alice", repo, ExhibitionListState.Loading)

        assertTrue(vm.exhibitions.value.isEmpty())
    }
}
