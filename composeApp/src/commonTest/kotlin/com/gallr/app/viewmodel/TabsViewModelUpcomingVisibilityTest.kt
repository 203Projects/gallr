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

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelUpcomingVisibilityTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 6, 23)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun catalog_surfaces_hide_exhibitions_opening_more_than_14_days_out() = runTest(dispatcher) {
        val exhibitions = listOf(
            exhibition("open", openingDate = LocalDate(2026, 6, 1), closingDate = LocalDate(2026, 7, 1)),
            exhibition("opens-today", openingDate = today, closingDate = LocalDate(2026, 7, 1)),
            exhibition("opens-day-14", openingDate = LocalDate(2026, 7, 7), closingDate = LocalDate(2026, 8, 1)),
            exhibition("opens-day-15", openingDate = LocalDate(2026, 7, 8), closingDate = LocalDate(2026, 8, 1)),
            exhibition("closed", openingDate = LocalDate(2026, 5, 1), closingDate = LocalDate(2026, 6, 22)),
        )
        val visibleIds = listOf("open", "opens-today", "opens-day-14")
        val vm = TabsViewModel(
            exhibitionRepository = FakeExhibitionRepo(exhibitions),
            bookmarkRepository = FakeBookmarks(exhibitions.map { it.id }.toSet()),
            languageRepository = FakeLanguage,
            themeRepository = FakeTheme,
            eventRepository = FakeEvents,
            todayProvider = { today },
        )

        backgroundScope.launch { vm.filteredExhibitions.collect {} }
        backgroundScope.launch { vm.myListMapPins.collect {} }
        backgroundScope.launch { vm.allMapPins.collect {} }
        advanceUntilIdle()

        val filteredIds = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
        val featuredIds = (vm.featuredState.value as ExhibitionListState.Success).exhibitions.map { it.id }
        val myListPinIds = vm.myListMapPins.value.map { it.id }
        val allPinIds = vm.allMapPins.value.map { it.id }

        assertEquals(visibleIds, filteredIds)
        assertEquals(visibleIds, featuredIds)
        assertEquals(visibleIds, myListPinIds)
        assertEquals(visibleIds, allPinIds)
    }

    private fun exhibition(
        id: String,
        openingDate: LocalDate,
        closingDate: LocalDate,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "venue",
        venueNameEn = "venue",
        cityKo = "Seoul",
        cityEn = "Seoul",
        regionKo = "Gangnam",
        regionEn = "Gangnam",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = true,
        latitude = 37.5,
        longitude = 127.0,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    private class FakeExhibitionRepo(private val exhibitions: List<Exhibition>) : ExhibitionRepository {
        override suspend fun getExhibitions() = Result.success(exhibitions)
        override suspend fun getFeaturedExhibitions() = Result.success(exhibitions)
    }

    private class FakeBookmarks(private val ids: Set<String>) : BookmarkRepository {
        override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(ids)
        override suspend fun isBookmarked(exhibitionId: String) = exhibitionId in ids
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

    private object FakeEvents : EventRepository {
        override suspend fun getActiveEvents() = Result.success(emptyList<Event>())
        override suspend fun getEventById(id: String) = Result.success(null)
        override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
    }
}
