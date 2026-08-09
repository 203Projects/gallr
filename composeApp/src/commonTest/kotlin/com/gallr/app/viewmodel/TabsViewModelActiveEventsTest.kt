package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ThemeRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelActiveEventsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun ev(
        id: String,
        start: String,
        end: String,
        brandColor: String = "#000000",
    ) = Event(
        id = id,
        nameKo = id,
        nameEn = id,
        descriptionKo = "",
        descriptionEn = "",
        locationLabelKo = "x",
        locationLabelEn = "x",
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        brandColor = brandColor,
        ticketUrl = null,
        isActive = true,
    )

    private fun exh(
        id: String,
        eventId: String?,
        latitude: Double? = null,
        longitude: Double? = null,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "v",
        venueNameEn = "v",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "강남",
        regionEn = "Gangnam",
        openingDate = LocalDate.parse("2026-01-01"),
        closingDate = LocalDate.parse("2030-01-01"),
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )

    private class MutableEventRepo(
        var events: List<Event>,
    ) : EventRepository {
        override suspend fun getActiveEvents() = Result.success(events.sortedBy { it.startDate })

        override suspend fun getEventById(id: String) = Result.success(events.firstOrNull { it.id == id })

        override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
    }

    private class FakeExhibitionRepo(
        private val all: List<Exhibition>,
    ) : ExhibitionRepository {
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

    private fun vm(
        events: List<Event>,
        exhibitions: List<Exhibition> = emptyList(),
    ): Pair<TabsViewModel, MutableEventRepo> {
        val repo = MutableEventRepo(events)
        val vm =
            TabsViewModel(
                exhibitionRepository = FakeExhibitionRepo(exhibitions),
                bookmarkRepository = FakeBookmarks,
                languageRepository = FakeLanguage,
                themeRepository = FakeTheme,
                eventRepository = repo,
            )
        return vm to repo
    }

    @Test
    fun two_active_events_both_surface() =
        runTest(dispatcher) {
            val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")))
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), vm.activeEvents.value.map { it.id })
        }

    @Test
    fun zero_events_empty_list() =
        runTest(dispatcher) {
            val (vm, _) = vm(emptyList())
            advanceUntilIdle()
            assertTrue(vm.activeEvents.value.isEmpty())
        }

    @Test
    fun one_event_single_entry() =
        runTest(dispatcher) {
            val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
            advanceUntilIdle()
            assertEquals(listOf("a"), vm.activeEvents.value.map { it.id })
        }

    @Test
    fun selected_event_filter_keeps_only_that_event() =
        runTest(dispatcher) {
            val (vm, _) =
                vm(
                    events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
                    exhibitions = listOf(exh("x", "a"), exh("y", "b"), exh("z", "other"), exh("w", null)),
                )
            // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
            backgroundScope.launch { vm.filteredExhibitions.collect {} }
            advanceUntilIdle()
            vm.toggleEventFilter("b")
            advanceUntilIdle()
            val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
            assertEquals(listOf("y"), ids)
            assertEquals("b", vm.filterState.value.selectedEventId)
        }

    @Test
    fun tapping_selected_event_filter_clears_it() =
        runTest(dispatcher) {
            val (vm, _) =
                vm(
                    events = listOf(ev("a", "2026-09-03", "2026-09-07")),
                    exhibitions = listOf(exh("x", "a"), exh("w", null)),
                )
            // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
            backgroundScope.launch { vm.filteredExhibitions.collect {} }
            advanceUntilIdle()
            vm.toggleEventFilter("a")
            vm.toggleEventFilter("a")
            advanceUntilIdle()
            val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
            assertEquals(setOf("x", "w"), ids.toSet())
            assertEquals(null, vm.filterState.value.selectedEventId)
        }

    @Test
    fun orphaned_event_id_stays_visible_when_no_event_selected() =
        runTest(dispatcher) {
            // Regression (260608-orphaned-event-id-exhibition-hidden-p1): an exhibition
            // whose event_id points at an event that is NOT in the active set ("orphaned"
            // — e.g. the linked event was deactivated) must remain visible. event_id is
            // provenance metadata, not a visibility gate, so with no selected event the
            // default it must pass through filteredExhibitions untouched alongside
            // active-event and unlinked exhibitions.
            val (vm, _) =
                vm(
                    events = listOf(ev("a", "2026-09-03", "2026-09-07")),
                    exhibitions = listOf(exh("active", "a"), exh("orphan", "deactivated-event"), exh("none", null)),
                )
            // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
            backgroundScope.launch { vm.filteredExhibitions.collect {} }
            advanceUntilIdle()
            assertEquals(null, vm.filterState.value.selectedEventId)
            val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
            assertEquals(setOf("active", "orphan", "none"), ids.toSet())
        }

    @Test
    fun selected_event_clears_when_active_set_becomes_empty() =
        runTest(dispatcher) {
            val (vm, repo) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
            advanceUntilIdle()
            vm.toggleEventFilter("a")
            advanceUntilIdle()
            assertEquals("a", vm.filterState.value.selectedEventId)
            repo.events = emptyList()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(null, vm.filterState.value.selectedEventId)
        }

    @Test
    fun shrink_two_to_one_clears_removed_selected_event() =
        runTest(dispatcher) {
            val (vm, repo) =
                vm(
                    events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
                    exhibitions = listOf(exh("x", "a"), exh("y", "b")),
                )
            // Keep a live subscriber so the WhileSubscribed filteredExhibitions flow activates.
            backgroundScope.launch { vm.filteredExhibitions.collect {} }
            advanceUntilIdle()
            vm.toggleEventFilter("b")
            advanceUntilIdle()
            repo.events = listOf(ev("a", "2026-09-03", "2026-09-07"))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(null, vm.filterState.value.selectedEventId)
            val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
            assertEquals(setOf("x", "y"), ids.toSet())
        }

    @Test
    fun all_map_pins_include_colored_pins_for_each_active_event() =
        runTest(dispatcher) {
            val (vm, _) =
                vm(
                    events =
                        listOf(
                            ev("first-event", "2026-06-10", "2026-06-20", brandColor = "#FF5CB3"),
                            ev("later-event", "2026-06-16", "2026-06-16", brandColor = "#F0BE1D"),
                        ),
                    exhibitions =
                        listOf(
                            exh("first-pin", "first-event", latitude = 37.551224, longitude = 126.925539),
                            exh("later-pin", "later-event", latitude = 37.536594, longitude = 126.998476),
                            exh("regular-pin", null, latitude = 37.5665, longitude = 126.9780),
                        ),
                )
            backgroundScope.launch { vm.allMapPins.collect {} }
            advanceUntilIdle()

            val pins = vm.allMapPins.value.associateBy { it.id }
            assertEquals("#FF5CB3", pins["first-pin"]?.brandColorHex)
            assertEquals("#F0BE1D", pins["later-pin"]?.brandColorHex)
            assertEquals(null, pins["regular-pin"]?.brandColorHex)
        }
}
