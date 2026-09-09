package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.PromotionRepository
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelPromotionTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `selected city loads paid placement through its separate repository`() =
        runTest(dispatcher) {
            val calls = mutableListOf<Pair<String, String>>()
            val placement =
                PromotedExhibition(
                    "promotion-one",
                    "between-seasons",
                    "계절 사이",
                    "Between Seasons",
                    "아틀리에 한남",
                    "Atelier Hannam",
                    "서울",
                    "Seoul",
                    "용산구",
                    "Yongsan-gu",
                    "2026-08-08",
                    "2026-09-14",
                    null,
                )
            val promotionRepository =
                object : PromotionRepository {
                    override suspend fun getPromotedExhibition(
                        cityKo: String,
                        regionKo: String,
                    ) = Result.success(placement).also { calls += cityKo to regionKo }
                }
            val vm =
                TabsViewModel(
                    exhibitionRepository = EmptyExhibitions,
                    bookmarkRepository = EmptyBookmarks,
                    languageRepository = Korean,
                    themeRepository = SystemTheme,
                    eventRepository = EmptyEvents,
                    promotionRepository = promotionRepository,
                )
            backgroundScope.launch { vm.promotedExhibition.collect {} }
            vm.setCity("서울")
            advanceUntilIdle()

            assertEquals(placement, vm.promotedExhibition.value)
            assertEquals(listOf("서울" to ""), calls)
        }

    @Test
    fun `returning to a city reuses the session placement without spending the daily cap again`() =
        runTest(dispatcher) {
            var calls = 0
            val placement =
                PromotedExhibition(
                    "promotion-one",
                    "between-seasons",
                    "계절 사이",
                    "Between Seasons",
                    "아틀리에 한남",
                    "Atelier Hannam",
                    "서울",
                    "Seoul",
                    "용산구",
                    "Yongsan-gu",
                    "2026-08-08",
                    "2026-09-14",
                    null,
                )
            val promotionRepository =
                object : PromotionRepository {
                    override suspend fun getPromotedExhibition(
                        cityKo: String,
                        regionKo: String,
                    ) = Result.success(placement).also { calls += 1 }
                }
            val vm =
                TabsViewModel(
                    exhibitionRepository = EmptyExhibitions,
                    bookmarkRepository = EmptyBookmarks,
                    languageRepository = Korean,
                    themeRepository = SystemTheme,
                    eventRepository = EmptyEvents,
                    promotionRepository = promotionRepository,
                )
            backgroundScope.launch { vm.promotedExhibition.collect {} }

            vm.setCity("서울")
            advanceUntilIdle()
            vm.setCity(null)
            advanceUntilIdle()
            vm.setCity("서울")
            advanceUntilIdle()

            assertEquals(placement, vm.promotedExhibition.value)
            assertEquals(1, calls)
        }

    @Test
    fun `bookmark completion callback reflects only completed state changes`() =
        runTest(dispatcher) {
            val bookmarks = MutableBookmarks()
            val completions = mutableListOf<Boolean>()
            val vm =
                TabsViewModel(
                    exhibitionRepository = EmptyExhibitions,
                    bookmarkRepository = bookmarks,
                    languageRepository = Korean,
                    themeRepository = SystemTheme,
                    eventRepository = EmptyEvents,
                )

            vm.toggleBookmark("exhibition-one", completions::add)
            vm.toggleBookmark("exhibition-one", completions::add)
            advanceUntilIdle()
            bookmarks.fail = true
            vm.toggleBookmark("exhibition-one", completions::add)
            advanceUntilIdle()

            assertEquals(listOf(true, false), completions)
        }
}

private object EmptyExhibitions : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions() = Result.success(emptyList<com.gallr.shared.data.model.Exhibition>())

    override suspend fun getExhibitions() = Result.success(emptyList<com.gallr.shared.data.model.Exhibition>())
}

private object EmptyBookmarks : BookmarkRepository {
    override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(emptySet())

    override suspend fun addBookmark(exhibitionId: String) = Unit

    override suspend fun removeBookmark(exhibitionId: String) = Unit

    override suspend fun isBookmarked(exhibitionId: String) = false

    override suspend fun clearAll() = Unit

    override fun setMutationListener(listener: suspend () -> Unit) = Unit
}

private class MutableBookmarks : BookmarkRepository {
    private val state = MutableStateFlow(emptySet<String>())
    var fail = false

    override fun observeBookmarkedIds(): Flow<Set<String>> = state

    override suspend fun addBookmark(exhibitionId: String) {
        if (fail) error("private bookmark detail")
        state.value += exhibitionId
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        if (fail) error("private bookmark detail")
        state.value -= exhibitionId
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in state.value

    override suspend fun clearAll() {
        state.value = emptySet()
    }

    override fun setMutationListener(listener: suspend () -> Unit) = Unit
}

private object Korean : LanguageRepository {
    override fun observeLanguage() = flowOf(AppLanguage.KO)

    override suspend fun setLanguage(language: AppLanguage) = Unit
}

private object SystemTheme : ThemeRepository {
    override fun observeThemeMode() = flowOf(ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit
}

private object EmptyEvents : EventRepository {
    override suspend fun getActiveEvents(): Result<List<Event>> = Result.success(emptyList())

    override suspend fun getEventById(id: String): Result<Event?> = Result.success(null)

    override suspend fun getExhibitionsForEvent(id: String) =
        Result.success(emptyList<com.gallr.shared.data.model.Exhibition>())
}
