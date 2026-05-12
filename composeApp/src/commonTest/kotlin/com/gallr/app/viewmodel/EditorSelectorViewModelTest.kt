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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSelectorViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val today = LocalDate(2026, 5, 12)

    private fun editor(
        id: String,
        isActive: Boolean = true,
        activeFrom: LocalDate = LocalDate(2026, 1, 1),
        activeTo: LocalDate? = null,
    ) = Editor(
        id = id,
        nameKo = id, nameEn = id,
        titleKo = id, titleEn = id,
        bioKo = id, bioEn = id,
        isActive = isActive,
        activeFrom = activeFrom,
        activeTo = activeTo,
    )

    private fun exhibition(id: String, editorId: String?) = Exhibition(
        id = id,
        nameKo = id, nameEn = id,
        venueNameKo = "V", venueNameEn = "V",
        cityKo = "C", cityEn = "C",
        regionKo = "R", regionEn = "R",
        openingDate = LocalDate(2026, 5, 1),
        closingDate = LocalDate(2026, 7, 1),
        isFeatured = false,
        latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "",
        addressKo = "", addressEn = "",
        coverImageUrl = null,
        editorId = editorId,
    )

    /**
     * Build a VM and subscribe to its state flow so that WhileSubscribed
     * starts the upstream `combine`. Returns the VM after advancing the
     * test dispatcher; callers read `vm.state.value`.
     */
    private fun TestScope.buildAndSubscribe(
        repo: FakeEditorRepository,
        allExhibitions: ExhibitionListState = ExhibitionListState.Loading,
    ): EditorSelectorViewModel {
        val vm = EditorSelectorViewModel(
            editorRepository = repo,
            allExhibitionsFlow = MutableStateFlow(allExhibitions),
            language = MutableStateFlow(AppLanguage.EN),
            todayProvider = { today },
        )
        // Keep a live subscriber so WhileSubscribed activates upstream.
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `loadEditors success splits editors by isCurrentlyActive`() = runTest(UnconfinedTestDispatcher()) {
        val active1 = editor("alice", isActive = true)
        val pastInactive = editor("bob", isActive = false)
        val pastDated = editor("carol", isActive = true, activeTo = LocalDate(2026, 4, 1))
        val repo = FakeEditorRepository(
            allEditorsResult = Result.success(listOf(active1, pastInactive, pastDated)),
        )

        val vm = buildAndSubscribe(repo)

        val state = vm.state.value
        assertTrue(state is EditorSelectorState.Success)
        assertEquals(listOf("alice"), state.active.map { it.id })
        assertEquals(setOf("bob", "carol"), state.past.map { it.id }.toSet())
    }

    @Test
    fun `loadEditors failure produces Error state`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(
            allEditorsResult = Result.failure(RuntimeException("network down")),
        )

        val vm = buildAndSubscribe(repo)

        val state = vm.state.value
        assertTrue(state is EditorSelectorState.Error)
        assertEquals("network down", state.message)
    }

    @Test
    fun `exhibition counts join correctly against allExhibitions`() = runTest(UnconfinedTestDispatcher()) {
        val alice = editor("alice")
        val bob = editor("bob")
        val exhibitions = listOf(
            exhibition("e1", editorId = "alice"),
            exhibition("e2", editorId = "alice"),
            exhibition("e3", editorId = "bob"),
            exhibition("e4", editorId = null),
        )
        val repo = FakeEditorRepository(
            allEditorsResult = Result.success(listOf(alice, bob)),
        )

        val vm = buildAndSubscribe(repo, ExhibitionListState.Success(exhibitions))

        val state = vm.state.value
        assertTrue(state is EditorSelectorState.Success)
        assertEquals(2, state.exhibitionCounts["alice"])
        assertEquals(1, state.exhibitionCounts["bob"])
    }

    @Test
    fun `exhibition counts use allExhibitions not filtered list - no filter leak`() = runTest(UnconfinedTestDispatcher()) {
        // Regression guard for the bug caught by the final code review:
        // EditorSelectorViewModel must source from allExhibitions (full set),
        // not from filteredExhibitions (filtered by user's current city/region).
        val alice = editor("alice")
        val exhibitions = listOf(
            exhibition("seoul-1", editorId = "alice"),
            exhibition("seoul-2", editorId = "alice"),
            exhibition("busan-1", editorId = "alice"),  // different city — must still count
        )
        val repo = FakeEditorRepository(allEditorsResult = Result.success(listOf(alice)))

        val vm = buildAndSubscribe(repo, ExhibitionListState.Success(exhibitions))

        val state = vm.state.value
        assertTrue(state is EditorSelectorState.Success)
        assertEquals(3, state.exhibitionCounts["alice"])
    }

    @Test
    fun `loadEditors is called on init`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(allEditorsResult = Result.success(emptyList()))

        buildAndSubscribe(repo)

        assertEquals(1, repo.getAllEditorsCallCount)
    }

    @Test
    fun `empty editor list produces Success with empty active and past`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeEditorRepository(allEditorsResult = Result.success(emptyList()))

        val vm = buildAndSubscribe(repo)

        val state = vm.state.value
        assertTrue(state is EditorSelectorState.Success)
        assertTrue(state.active.isEmpty())
        assertTrue(state.past.isEmpty())
        assertTrue(state.exhibitionCounts.isEmpty())
    }
}
