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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelFreshnessTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun foreground_refresh_only_fetches_when_catalog_is_stale() = runTest(dispatcher) {
        var nowMillis = 1_000L
        val repository = SequencedExhibitionRepository(listOf(exhibition("initial")))
        val vm = viewModel(repository) { nowMillis }
        advanceUntilIdle()

        assertEquals(1, repository.allRequestCount)

        nowMillis += 14 * 60 * 1_000L
        vm.refreshIfStale(maxAgeMillis = 15 * 60 * 1_000L)
        advanceUntilIdle()
        assertEquals(1, repository.allRequestCount)

        repository.exhibitions = listOf(exhibition("updated"))
        nowMillis += 2 * 60 * 1_000L
        vm.refreshIfStale(maxAgeMillis = 15 * 60 * 1_000L)
        advanceUntilIdle()

        assertEquals(2, repository.allRequestCount)
        assertEquals(
            listOf("updated"),
            assertIs<ExhibitionListState.Success>(vm.allExhibitions.value).exhibitions.map { it.id },
        )
    }

    @Test
    fun background_refresh_keeps_current_catalog_visible_until_replacement_arrives() = runTest(dispatcher) {
        var nowMillis = 1_000L
        val repository = SequencedExhibitionRepository(listOf(exhibition("initial")))
        val vm = viewModel(repository) { nowMillis }
        advanceUntilIdle()

        val releaseFetch = CompletableDeferred<Unit>()
        repository.beforeNextAllFetch = releaseFetch
        repository.exhibitions = listOf(exhibition("updated"))
        nowMillis += 16 * 60 * 1_000L
        vm.refreshIfStale(maxAgeMillis = 15 * 60 * 1_000L)

        assertTrue(vm.isRefreshing.value)
        assertEquals(
            listOf("initial"),
            assertIs<ExhibitionListState.Success>(vm.allExhibitions.value).exhibitions.map { it.id },
        )

        releaseFetch.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.isRefreshing.value)
        assertEquals(
            listOf("updated"),
            assertIs<ExhibitionListState.Success>(vm.allExhibitions.value).exhibitions.map { it.id },
        )
    }

    @Test
    fun failed_background_refresh_retains_the_last_successful_catalog() = runTest(dispatcher) {
        var nowMillis = 1_000L
        val repository = SequencedExhibitionRepository(listOf(exhibition("initial")))
        val vm = viewModel(repository) { nowMillis }
        advanceUntilIdle()

        repository.nextAllFailure = IllegalStateException("temporary")
        nowMillis += 16 * 60 * 1_000L
        vm.refreshIfStale(maxAgeMillis = 15 * 60 * 1_000L)
        advanceUntilIdle()

        assertEquals(
            listOf("initial"),
            assertIs<ExhibitionListState.Success>(vm.allExhibitions.value).exhibitions.map { it.id },
        )
    }

    @Test
    fun foreground_refresh_retries_when_the_initial_catalog_load_failed() = runTest(dispatcher) {
        var nowMillis = 1_000L
        val repository = SequencedExhibitionRepository(listOf(exhibition("recovered"))).apply {
            nextAllFailure = IllegalStateException("startup failure")
        }
        val vm = viewModel(repository) { nowMillis }
        advanceUntilIdle()

        assertIs<ExhibitionListState.Error>(vm.allExhibitions.value)
        assertEquals(1, repository.allRequestCount)

        vm.refreshIfStale()
        advanceUntilIdle()

        assertEquals(2, repository.allRequestCount)
        assertEquals(
            listOf("recovered"),
            assertIs<ExhibitionListState.Success>(vm.allExhibitions.value).exhibitions.map { it.id },
        )
    }

    private fun viewModel(
        repository: ExhibitionRepository,
        nowMillisProvider: () -> Long,
    ) = TabsViewModel(
        exhibitionRepository = repository,
        bookmarkRepository = EmptyBookmarks,
        languageRepository = FakeLanguage,
        themeRepository = FakeTheme,
        eventRepository = FakeEvents,
        todayProvider = { LocalDate(2026, 8, 5) },
        nowMillisProvider = nowMillisProvider,
    )

    private fun exhibition(id: String) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "venue",
        venueNameEn = "venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "용산구",
        regionEn = "Yongsan-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = true,
        latitude = 37.5,
        longitude = 127.0,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )

    private class SequencedExhibitionRepository(
        var exhibitions: List<Exhibition>,
    ) : ExhibitionRepository {
        var allRequestCount = 0
        var beforeNextAllFetch: CompletableDeferred<Unit>? = null
        var nextAllFailure: Throwable? = null

        override suspend fun getExhibitions(): Result<List<Exhibition>> {
            allRequestCount += 1
            beforeNextAllFetch?.also {
                beforeNextAllFetch = null
                it.await()
            }
            nextAllFailure?.also {
                nextAllFailure = null
                return Result.failure(it)
            }
            return Result.success(exhibitions)
        }

        override suspend fun getFeaturedExhibitions() = Result.success(exhibitions)
    }

    private object EmptyBookmarks : BookmarkRepository {
        override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun isBookmarked(exhibitionId: String) = false
        override suspend fun addBookmark(exhibitionId: String) = Unit
        override suspend fun removeBookmark(exhibitionId: String) = Unit
        override suspend fun clearAll() = Unit
        override fun setMutationListener(listener: suspend () -> Unit) = Unit
    }

    private object FakeLanguage : LanguageRepository {
        override fun observeLanguage(): Flow<AppLanguage> = flowOf(AppLanguage.KO)
        override suspend fun setLanguage(language: AppLanguage) = Unit
    }

    private object FakeTheme : ThemeRepository {
        override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
    }

    private object FakeEvents : EventRepository {
        override suspend fun getActiveEvents() = Result.success(emptyList<Event>())
        override suspend fun getEventById(id: String) = Result.success(null)
        override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
    }
}
