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
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun catalog_surfaces_hide_exhibitions_opening_more_than_14_days_out() =
        runTest(dispatcher) {
            val exhibitions =
                listOf(
                    exhibition("open", openingDate = LocalDate(2026, 6, 1), closingDate = LocalDate(2026, 7, 1)),
                    exhibition("opens-today", openingDate = today, closingDate = LocalDate(2026, 7, 1)),
                    exhibition(
                        "opens-day-14",
                        openingDate = LocalDate(2026, 7, 7),
                        closingDate = LocalDate(2026, 8, 1),
                    ),
                    exhibition(
                        "opens-day-15",
                        openingDate = LocalDate(2026, 7, 8),
                        closingDate = LocalDate(2026, 8, 1),
                    ),
                    exhibition("closed", openingDate = LocalDate(2026, 5, 1), closingDate = LocalDate(2026, 6, 22)),
                )
            val visibleIds = listOf("open", "opens-today", "opens-day-14")
            val vm =
                TabsViewModel(
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

    @Test
    fun filter_counts_hide_exhibitions_opening_more_than_14_days_out() =
        runTest(dispatcher) {
            val exhibitions =
                listOf(
                    exhibition(
                        "seoul-visible",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Seoul",
                        cityEn = "Seoul",
                        regionKo = "Gangnam",
                        regionEn = "Gangnam",
                    ),
                    exhibition(
                        "seoul-day-15",
                        openingDate = LocalDate(2026, 7, 8),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Seoul",
                        cityEn = "Seoul",
                        regionKo = "Gangnam",
                        regionEn = "Gangnam",
                    ),
                    exhibition(
                        "seoul-closed",
                        openingDate = LocalDate(2026, 5, 1),
                        closingDate = LocalDate(2026, 6, 22),
                        cityKo = "Seoul",
                        cityEn = "Seoul",
                        regionKo = "Gangnam",
                        regionEn = "Gangnam",
                    ),
                    exhibition(
                        "busan-visible",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Busan",
                        cityEn = "Busan",
                        regionKo = "Haeundae",
                        regionEn = "Haeundae",
                    ),
                    exhibition(
                        "busan-day-15",
                        openingDate = LocalDate(2026, 7, 8),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Busan",
                        cityEn = "Busan",
                        regionKo = "Haeundae",
                        regionEn = "Haeundae",
                    ),
                )
            val vm =
                TabsViewModel(
                    exhibitionRepository = FakeExhibitionRepo(exhibitions),
                    bookmarkRepository = FakeBookmarks(emptySet()),
                    languageRepository = FakeLanguage,
                    themeRepository = FakeTheme,
                    eventRepository = FakeEvents,
                    todayProvider = { today },
                )

            backgroundScope.launch { vm.distinctCities.collect {} }
            backgroundScope.launch { vm.distinctRegions.collect {} }
            advanceUntilIdle()

            assertEquals(
                mapOf("Seoul" to 1, "Busan" to 1),
                vm.distinctCities.value.associate { it.cityKo to it.count },
            )

            vm.setCity("Seoul")
            advanceUntilIdle()
            assertEquals(
                mapOf("Gangnam" to 1),
                vm.distinctRegions.value.associate { it.regionKo to it.count },
            )

            vm.setCity("Busan")
            advanceUntilIdle()
            assertEquals(
                mapOf("Haeundae" to 1),
                vm.distinctRegions.value.associate { it.regionKo to it.count },
            )
        }

    @Test
    fun filter_counts_follow_the_active_list_tab_without_following_search_or_chips() =
        runTest(dispatcher) {
            val exhibitions =
                listOf(
                    exhibition(
                        "seoul-saved",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Seoul",
                        cityEn = "Seoul",
                        regionKo = "Gangnam",
                        regionEn = "Gangnam",
                    ),
                    exhibition(
                        "seoul-unsaved",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Seoul",
                        cityEn = "Seoul",
                        regionKo = "Jongno",
                        regionEn = "Jongno",
                        isFeatured = false,
                    ),
                    exhibition(
                        "busan-saved",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Busan",
                        cityEn = "Busan",
                        regionKo = "Haeundae",
                        regionEn = "Haeundae",
                        isFeatured = false,
                    ),
                    exhibition(
                        "daegu-unsaved",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "Daegu",
                        cityEn = "Daegu",
                        regionKo = "Jung-gu",
                        regionEn = "Jung-gu",
                    ),
                )
            val vm =
                TabsViewModel(
                    exhibitionRepository = FakeExhibitionRepo(exhibitions),
                    bookmarkRepository = FakeBookmarks(setOf("seoul-saved", "busan-saved")),
                    languageRepository = FakeLanguage,
                    themeRepository = FakeTheme,
                    eventRepository = FakeEvents,
                    todayProvider = { today },
                )

            backgroundScope.launch { vm.listScreenState.collect {} }
            advanceUntilIdle()

            var screenState = vm.listScreenState.value
            assertEquals(
                mapOf("Seoul" to 2, "Busan" to 1, "Daegu" to 1),
                screenState.cities.associate { it.cityKo to it.count },
            )
            assertEquals(4, screenState.tabExhibitionCount)
            assertEquals(false, screenState.showMyListOnly)

            vm.setCity("Seoul")
            vm.toggleRegion("Jongno")
            vm.setShowMyListOnly(true)
            advanceUntilIdle()
            screenState = vm.listScreenState.value
            assertEquals(
                mapOf("Seoul" to 1, "Busan" to 1),
                screenState.cities.associate { it.cityKo to it.count },
            )
            assertEquals("Seoul", screenState.selectedCity)
            assertEquals(emptyList(), screenState.filter.regions)
            assertEquals(listOf("Gangnam" to 1), screenState.regions.map { it.regionKo to it.count })
            assertEquals(2, screenState.tabExhibitionCount)
            assertEquals(true, screenState.showMyListOnly)

            vm.setSearchQuery("does not match anything")
            vm.updateFilter { copy(showFeatured = true) }
            advanceUntilIdle()
            screenState = vm.listScreenState.value
            assertEquals(
                mapOf("Seoul" to 1, "Busan" to 1),
                screenState.cities.associate { it.cityKo to it.count },
            )
            assertEquals(listOf("Gangnam" to 1), screenState.regions.map { it.regionKo to it.count })
            assertEquals(2, screenState.tabExhibitionCount)

            vm.setShowMyListOnly(false)
            advanceUntilIdle()
            screenState = vm.listScreenState.value
            assertEquals(
                mapOf("Seoul" to 2, "Busan" to 1, "Daegu" to 1),
                screenState.cities.associate { it.cityKo to it.count },
            )
            assertEquals(
                mapOf("Gangnam" to 1, "Jongno" to 1),
                screenState.regions.associate { it.regionKo to it.count },
            )
            assertEquals(4, screenState.tabExhibitionCount)

            vm.setCity("Daegu")
            vm.toggleRegion("Jung-gu")
            vm.setShowMyListOnly(true)
            advanceUntilIdle()
            screenState = vm.listScreenState.value
            assertEquals(null, screenState.selectedCity)
            assertEquals(emptyList(), screenState.filter.regions)
            assertEquals(setOf("Seoul", "Busan"), screenState.cities.map { it.cityKo }.toSet())

            vm.toggleBookmark("busan-saved")
            advanceUntilIdle()
            screenState = vm.listScreenState.value
            assertEquals(listOf("Seoul" to 1), screenState.cities.map { it.cityKo to it.count })
            assertEquals(1, screenState.tabExhibitionCount)
        }

    @Test
    fun my_list_filter_counts_are_empty_when_there_are_no_saved_exhibitions() =
        runTest(dispatcher) {
            val vm =
                TabsViewModel(
                    exhibitionRepository =
                        FakeExhibitionRepo(
                            listOf(
                                exhibition(
                                    "seoul-unsaved",
                                    openingDate = LocalDate(2026, 6, 1),
                                    closingDate = LocalDate(2026, 8, 1),
                                ),
                            ),
                        ),
                    bookmarkRepository = FakeBookmarks(emptySet()),
                    languageRepository = FakeLanguage,
                    themeRepository = FakeTheme,
                    eventRepository = FakeEvents,
                    todayProvider = { today },
                )

            backgroundScope.launch { vm.distinctCities.collect {} }
            backgroundScope.launch { vm.distinctRegions.collect {} }
            backgroundScope.launch { vm.tabExhibitionCount.collect {} }
            vm.setCity("Seoul")
            vm.setShowMyListOnly(true)
            advanceUntilIdle()

            assertEquals(emptyList(), vm.distinctCities.value)
            assertEquals(emptyList(), vm.distinctRegions.value)
            assertEquals(null, vm.selectedCity.value)
            assertEquals(0, vm.tabExhibitionCount.value)
        }

    @Test
    fun location_filters_collapse_case_whitespace_and_missing_translation_variants() =
        runTest(dispatcher) {
            val exhibitions =
                listOf(
                    exhibition(
                        "canonical-seoul",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = "서울",
                        cityEn = "Seoul",
                        regionKo = "Seoul",
                        regionEn = "",
                    ),
                    exhibition(
                        "variant-seoul",
                        openingDate = LocalDate(2026, 6, 1),
                        closingDate = LocalDate(2026, 8, 1),
                        cityKo = " 서울 ",
                        cityEn = "",
                        regionKo = "SEOUL",
                        regionEn = "",
                    ),
                )
            val vm =
                TabsViewModel(
                    exhibitionRepository = FakeExhibitionRepo(exhibitions),
                    bookmarkRepository = FakeBookmarks(emptySet()),
                    languageRepository = FakeLanguage,
                    themeRepository = FakeTheme,
                    eventRepository = FakeEvents,
                    todayProvider = { today },
                )

            backgroundScope.launch { vm.distinctCities.collect {} }
            backgroundScope.launch { vm.distinctRegions.collect {} }
            backgroundScope.launch { vm.filteredExhibitions.collect {} }
            advanceUntilIdle()

            assertEquals(listOf("서울" to 2), vm.distinctCities.value.map { it.cityKo to it.count })
            assertEquals(
                "Seoul",
                vm.distinctCities.value
                    .single()
                    .cityEn,
            )

            vm.setCity("서울")
            advanceUntilIdle()
            assertEquals(listOf("Seoul" to 2), vm.distinctRegions.value.map { it.regionKo to it.count })

            vm.toggleRegion("Seoul")
            advanceUntilIdle()
            assertEquals(
                setOf("canonical-seoul", "variant-seoul"),
                (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }.toSet(),
            )
        }

    private fun exhibition(
        id: String,
        openingDate: LocalDate,
        closingDate: LocalDate,
        cityKo: String = "Seoul",
        cityEn: String = "Seoul",
        regionKo: String = "Gangnam",
        regionEn: String = "Gangnam",
        isFeatured: Boolean = true,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "venue",
        venueNameEn = "venue",
        cityKo = cityKo,
        cityEn = cityEn,
        regionKo = regionKo,
        regionEn = regionEn,
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = isFeatured,
        latitude = 37.5,
        longitude = 127.0,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    private class FakeExhibitionRepo(
        private val exhibitions: List<Exhibition>,
    ) : ExhibitionRepository {
        override suspend fun getExhibitions() = Result.success(exhibitions)

        override suspend fun getFeaturedExhibitions() = Result.success(exhibitions)
    }

    private class FakeBookmarks(
        ids: Set<String>,
    ) : BookmarkRepository {
        private val bookmarkedIds = MutableStateFlow(ids)

        override fun observeBookmarkedIds(): Flow<Set<String>> = bookmarkedIds

        override suspend fun isBookmarked(exhibitionId: String) = exhibitionId in bookmarkedIds.value

        override suspend fun addBookmark(exhibitionId: String) {
            bookmarkedIds.value += exhibitionId
        }

        override suspend fun removeBookmark(exhibitionId: String) {
            bookmarkedIds.value -= exhibitionId
        }

        override suspend fun clearAll() {
            bookmarkedIds.value = emptySet()
        }

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
