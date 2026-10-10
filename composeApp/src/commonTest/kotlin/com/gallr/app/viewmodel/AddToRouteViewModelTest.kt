package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.route.toRouteStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Spec 089 DR-D16 and RO5: "동선에 추가" on exhibition detail goes through the draft repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddToRouteViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val thursday = Instant.parse("2026-10-08T04:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun addingAppendsToTheDraftAndReportsTheCount() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(exhibition("a")), stop(exhibition("b"))))
            val viewModel = addToRoute(exhibition("c"), drafts)
            observe(viewModel)
            assertEquals(AddToRouteAvailability.CAN_ADD, viewModel.state.value.availability)

            viewModel.add()
            advanceUntilIdle()

            val route = drafts.draft.value.route
            assertEquals(listOf("a", "b", "c"), route.stops.map { it.exhibitionId })
            assertEquals(AddedToRoute(stopCount = 3), viewModel.state.value.message)
            assertEquals(AddToRouteAvailability.IN_ROUTE, viewModel.state.value.availability)

            viewModel.dismissMessage()
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun theFirstStopOfAnEmptyDraftCountsAsANewDraft() =
        runTest(dispatcher) {
            val analytics = RecordingRouteAnalytics()
            val drafts = FakePersonalRouteDraftRepository()
            val first = addToRoute(exhibition("a"), drafts, analytics = analytics)
            val second = addToRoute(exhibition("b"), drafts, analytics = analytics)
            observe(first)
            observe(second)

            first.add()
            second.add()
            advanceUntilIdle()

            assertEquals(listOf("draft_started"), analytics.events)
        }

    @Test
    fun anExhibitionAlreadyInTheDraftOpensTheComposerInstead() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository(listOf(stop(exhibition("a"))))
            val viewModel = addToRoute(exhibition("a"), drafts)
            observe(viewModel)

            assertEquals(AddToRouteAvailability.IN_ROUTE, viewModel.state.value.availability)
        }

    @Test
    fun aFullDraftDisablesTheButton() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository((1..10).map { stop(exhibition("s$it")) })
            val viewModel = addToRoute(exhibition("eleventh"), drafts)
            observe(viewModel)

            assertEquals(AddToRouteAvailability.FULL, viewModel.state.value.availability)
            viewModel.add()
            advanceUntilIdle()
            assertEquals(10, drafts.draft.value.route.stops.size)
        }

    @Test
    fun endedShowsAndShowsWithoutALocationHaveNoButton() =
        runTest(dispatcher) {
            val drafts = FakePersonalRouteDraftRepository()
            val ended = addToRoute(exhibition("ended", closingDate = LocalDate(2026, 10, 7)), drafts)
            val unlocated = addToRoute(exhibition("x", located = false), drafts)
            observe(ended)
            observe(unlocated)

            assertEquals(AddToRouteAvailability.HIDDEN, ended.state.value.availability)
            assertEquals(AddToRouteAvailability.HIDDEN, unlocated.state.value.availability)
        }

    @Test
    fun theLastDayStillCountsAsRunningInSeoul() =
        runTest(dispatcher) {
            // 23:30 UTC on 10-07 is already 10-08 in Seoul.
            val viewModel =
                addToRoute(
                    exhibition("last-day", closingDate = LocalDate(2026, 10, 8)),
                    FakePersonalRouteDraftRepository(),
                    now = Instant.parse("2026-10-07T23:30:00Z"),
                )
            observe(viewModel)

            assertEquals(AddToRouteAvailability.CAN_ADD, viewModel.state.value.availability)
        }

    private fun TestScope.observe(viewModel: AddToRouteViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private fun addToRoute(
        exhibition: Exhibition,
        drafts: FakePersonalRouteDraftRepository,
        now: Instant = thursday,
        analytics: RouteAnalytics = RouteAnalytics.None,
    ) = AddToRouteViewModel(
        exhibition = exhibition,
        draftRepository = drafts,
        analytics = analytics,
        clock =
            object : Clock {
                override fun now(): Instant = now
            },
    )

    private fun stop(exhibition: Exhibition) = requireNotNull(exhibition.toRouteStop())

    private fun exhibition(
        id: String,
        located: Boolean = true,
        closingDate: LocalDate = LocalDate(2026, 11, 30),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 9, 1),
        closingDate = closingDate,
        isFeatured = false,
        latitude = if (located) 37.57 else null,
        longitude = if (located) 126.98 else null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = null,
        galleryId = "gallery-$id",
    )
}
