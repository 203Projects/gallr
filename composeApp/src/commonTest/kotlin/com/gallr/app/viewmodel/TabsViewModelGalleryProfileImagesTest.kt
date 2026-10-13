package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GalleryProfileImage
import com.gallr.shared.data.model.GalleryProfileImages
import com.gallr.shared.data.model.ThemeMode
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.GalleryProfileImageRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelGalleryProfileImagesTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `curated gallery images load once for the session`() =
        runTest(dispatcher) {
            var calls = 0
            val kukje =
                GalleryProfileImage(
                    galleryId = "g-1",
                    nameKo = "국제갤러리",
                    nameEn = "Kukje Gallery",
                    kind = GalleryProfileImage.Kind.LOGO,
                    imageUrl = "https://example.supabase.co/storage/v1/object/public/gallery-profile-images/g-1/a.jpg",
                    credit = null,
                )
            val vm =
                tabsViewModel(
                    object : GalleryProfileImageRepository {
                        override suspend fun getProfileImages() =
                            Result.success(GalleryProfileImages(listOf(kukje))).also { calls++ }
                    },
                )
            advanceUntilIdle()

            assertEquals(kukje, vm.galleryProfileImages.value.find(null, "국제갤러리", "Kukje Gallery"))
            assertEquals(1, calls)
        }

    @Test
    fun `failed load keeps the empty lookup so monograms render`() =
        runTest(dispatcher) {
            val vm =
                tabsViewModel(
                    object : GalleryProfileImageRepository {
                        override suspend fun getProfileImages(): Result<GalleryProfileImages> =
                            Result.failure(IllegalStateException("offline"))
                    },
                )
            advanceUntilIdle()

            assertNull(vm.galleryProfileImages.value.find("g-1", "국제갤러리", "Kukje Gallery"))
        }

    private fun tabsViewModel(repository: GalleryProfileImageRepository) =
        TabsViewModel(
            exhibitionRepository = NoExhibitions,
            bookmarkRepository = NoBookmarks,
            languageRepository = KoreanLanguage,
            themeRepository = SystemThemeMode,
            eventRepository = NoEvents,
            galleryProfileImageRepository = repository,
        )
}

private object NoExhibitions : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions() = Result.success(emptyList<Exhibition>())

    override suspend fun getExhibitions() = Result.success(emptyList<Exhibition>())
}

private object NoBookmarks : BookmarkRepository {
    override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(emptySet())

    override suspend fun addBookmark(exhibitionId: String) = Unit

    override suspend fun removeBookmark(exhibitionId: String) = Unit

    override suspend fun isBookmarked(exhibitionId: String) = false

    override suspend fun clearAll() = Unit

    override fun setMutationListener(listener: suspend () -> Unit) = Unit
}

private object KoreanLanguage : LanguageRepository {
    override fun observeLanguage() = flowOf(AppLanguage.KO)

    override suspend fun setLanguage(language: AppLanguage) = Unit
}

private object SystemThemeMode : ThemeRepository {
    override fun observeThemeMode() = flowOf(ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit
}

private object NoEvents : EventRepository {
    override suspend fun getActiveEvents(): Result<List<Event>> = Result.success(emptyList())

    override suspend fun getEventById(id: String): Result<Event?> = Result.success(null)

    override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
}
