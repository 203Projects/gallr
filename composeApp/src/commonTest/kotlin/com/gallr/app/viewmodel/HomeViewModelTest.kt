package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.FollowedGalleryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 10, 10)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theCatalogueDecidesLoadingAndError() {
        assertEquals(
            HomeUiState.Loading,
            homeUiState(
                ExhibitionListState.Loading,
                ExhibitionListState.Loading,
                emptyList(),
                AuthState.Anonymous,
                today,
            ),
        )
        assertEquals(
            HomeUiState.Error("network"),
            homeUiState(
                ExhibitionListState.Error("network"),
                ExhibitionListState.Success(listOf(exhibition("f", isFeatured = true))),
                emptyList(),
                AuthState.Anonymous,
                today,
            ),
        )
    }

    @Test
    fun theHeroFollowsTheFeaturedListAndFallsBackToTheCatalogue() {
        val a = exhibition("a", isFeatured = true)
        val b = exhibition("b", isFeatured = true)
        val plain = exhibition("plain")
        val catalogue = ExhibitionListState.Success(listOf(plain, a, b))

        val ordered =
            homeUiState(catalogue, ExhibitionListState.Success(listOf(b, a)), emptyList(), AuthState.Anonymous, today)
        val fallback = homeUiState(catalogue, ExhibitionListState.Loading, emptyList(), AuthState.Anonymous, today)

        assertEquals(listOf("b", "a"), assertIs<HomeUiState.Ready>(ordered).feed.hero.map { it.id })
        assertEquals(listOf("a", "b"), assertIs<HomeUiState.Ready>(fallback).feed.hero.map { it.id })
    }

    @Test
    fun endedAndFarFutureExhibitionsAreLeftOut() {
        val ended = exhibition("ended", closing = LocalDate(2026, 10, 9), isFeatured = true)
        val farFuture =
            exhibition("later", opening = LocalDate(2026, 11, 20), closing = LocalDate(2027, 1, 1), isFeatured = true)
        val running = exhibition("now", isFeatured = true)
        val catalogue = ExhibitionListState.Success(listOf(ended, farFuture, running))

        val state = homeUiState(catalogue, catalogue, emptyList(), AuthState.Anonymous, today)

        assertEquals(listOf("now"), assertIs<HomeUiState.Ready>(state).feed.hero.map { it.id })
    }

    @Test
    fun theGreetingNamesASignedInVisitorOnly() {
        val catalogue = ExhibitionListState.Success(listOf(exhibition("a")))
        val named = AuthState.Authenticated(GallrUser("u1", " 하신 ", null))
        val unnamed = AuthState.Authenticated(GallrUser("u2", "", null))

        assertEquals(
            "하신",
            assertIs<HomeUiState.Ready>(homeUiState(catalogue, catalogue, emptyList(), named, today)).greetingName,
        )
        assertNull(
            assertIs<HomeUiState.Ready>(homeUiState(catalogue, catalogue, emptyList(), unnamed, today)).greetingName,
        )
        assertNull(
            assertIs<HomeUiState.Ready>(
                homeUiState(catalogue, catalogue, emptyList(), AuthState.Anonymous, today),
            ).greetingName,
        )
    }

    @Test
    fun theStateFollowsTheCatalogueAndTheSession() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val featured = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val auth = MutableStateFlow<AuthState>(AuthState.Anonymous)
            val viewModel = HomeViewModel(exhibitions, featured, NoFollows, auth) { today }
            backgroundScope.launch { viewModel.state.collect {} }

            assertEquals(HomeUiState.Loading, viewModel.state.value)

            exhibitions.value = ExhibitionListState.Success(listOf(exhibition("a", isFeatured = true)))
            assertEquals(listOf("a"), assertIs<HomeUiState.Ready>(viewModel.state.value).feed.hero.map { it.id })

            auth.value = AuthState.Authenticated(GallrUser("u1", "hanshin", null))
            assertEquals("hanshin", assertIs<HomeUiState.Ready>(viewModel.state.value).greetingName)
        }

    private object NoFollows : FollowedGalleryRepository {
        override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = MutableStateFlow(emptyList())

        override suspend fun followGalleries(galleries: List<FollowedGallery>) = error("not used here")

        override suspend fun unfollowGallery(galleryKey: String) = error("not used here")

        override suspend fun acknowledgeGallery(
            galleryKey: String,
            currentExhibitionIds: Set<String>,
        ) = error("not used here")
    }

    private fun exhibition(
        id: String,
        isFeatured: Boolean = false,
        opening: LocalDate = LocalDate(2026, 9, 1),
        closing: LocalDate = LocalDate(2026, 11, 30),
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "장소",
        venueNameEn = "Venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "",
        regionEn = "",
        openingDate = opening,
        closingDate = closing,
        isFeatured = isFeatured,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
