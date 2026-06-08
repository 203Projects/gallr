package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelActiveEventsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun ev(id: String, start: String, end: String) = Event(
        id = id, nameKo = id, nameEn = id, descriptionKo = "", descriptionEn = "",
        locationLabelKo = "x", locationLabelEn = "x",
        startDate = LocalDate.parse(start), endDate = LocalDate.parse(end),
        brandColor = "#000000", ticketUrl = null, isActive = true,
    )

    private fun exh(id: String, eventId: String?) = Exhibition(
        id = id, nameKo = id, nameEn = id, venueNameKo = "v", venueNameEn = "v",
        cityKo = "서울", cityEn = "Seoul", regionKo = "강남", regionEn = "Gangnam",
        openingDate = LocalDate.parse("2026-01-01"), closingDate = LocalDate.parse("2030-01-01"),
        isFeatured = false, latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "", addressKo = "", addressEn = "",
        coverImageUrl = null, eventId = eventId,
    )

    private class MutableEventRepo(var events: List<Event>) : EventRepository {
        override suspend fun getActiveEvents() = Result.success(events.sortedBy { it.startDate })
        override suspend fun getEventById(id: String) = Result.success(events.firstOrNull { it.id == id })
        override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
    }

    private class FakeExhibitionRepo(private val all: List<Exhibition>) : ExhibitionRepository {
        override suspend fun getExhibitions() = Result.success(all)
        override suspend fun getFeaturedExhibitions() = Result.success(all)
    }

    private object FakeBookmarks : BookmarkRepository {
        override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun isBookmarked(exhibitionId: String) = false
        override suspend fun addBookmark(exhibitionId: String) {}
        override suspend fun removeBookmark(exhibitionId: String) {}
        override suspend fun clearAll() {}
        override fun setMutationListener(listener: suspend () -> Unit) {}
    }

    private object FakeLanguage : LanguageRepository {
        override fun observeLanguage(): Flow<AppLanguage> = flowOf(AppLanguage.KO)
        override suspend fun setLanguage(language: AppLanguage) {}
    }

    private object FakeTheme : ThemeRepository {
        override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) {}
    }

    private fun vm(events: List<Event>, exhibitions: List<Exhibition> = emptyList()): Pair<TabsViewModel, MutableEventRepo> {
        val repo = MutableEventRepo(events)
        val vm = TabsViewModel(
            exhibitionRepository = FakeExhibitionRepo(exhibitions),
            bookmarkRepository = FakeBookmarks,
            languageRepository = FakeLanguage,
            themeRepository = FakeTheme,
            eventRepository = repo,
        )
        return vm to repo
    }

    @Test
    fun two_active_events_both_surface() = runTest(dispatcher) {
        val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.activeEvents.value.map { it.id })
    }

    @Test
    fun zero_events_empty_list() = runTest(dispatcher) {
        val (vm, _) = vm(emptyList())
        advanceUntilIdle()
        assertTrue(vm.activeEvents.value.isEmpty())
    }

    @Test
    fun one_event_single_entry() = runTest(dispatcher) {
        val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.activeEvents.value.map { it.id })
    }

    @Test
    fun event_only_keeps_exhibitions_in_any_active_event() = runTest(dispatcher) {
        val (vm, _) = vm(
            events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
            exhibitions = listOf(exh("x", "a"), exh("y", "b"), exh("z", "other"), exh("w", null)),
        )
        // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
        backgroundScope.launch { vm.filteredExhibitions.collect {} }
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
        assertEquals(setOf("x", "y"), ids.toSet())
    }

    @Test
    fun event_only_clears_when_active_set_becomes_empty() = runTest(dispatcher) {
        val (vm, repo) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        assertTrue(vm.filterState.value.eventOnly)
        repo.events = emptyList()
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.filterState.value.eventOnly)
    }

    @Test
    fun shrink_two_to_one_does_not_clear_event_only() = runTest(dispatcher) {
        val (vm, repo) = vm(
            events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
            exhibitions = listOf(exh("x", "a"), exh("y", "b")),
        )
        // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
        backgroundScope.launch { vm.filteredExhibitions.collect {} }
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        repo.events = listOf(ev("a", "2026-09-03", "2026-09-07"))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.filterState.value.eventOnly)
        val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
        assertEquals(setOf("x"), ids.toSet())
    }
}
