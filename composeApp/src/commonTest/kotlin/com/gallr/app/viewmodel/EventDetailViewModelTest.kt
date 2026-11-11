package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate.parse("2026-06-23")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_exhibitions_for_requested_later_event_id() =
        runTest(dispatcher) {
            val repo =
                FakeEventRepository(
                    events =
                        listOf(
                            event("first-event", "2026-06-10", "2026-06-20"),
                            event("later-event", "2026-06-16", "2026-06-16"),
                        ),
                    exhibitionsByEvent =
                        mapOf(
                            "first-event" to listOf(exhibition("first-a", "first-event")),
                            "later-event" to
                                listOf(
                                    exhibition("later-a", "later-event", venueNameKo = "venue-a"),
                                    exhibition("later-b", "later-event", venueNameKo = "venue-b"),
                                    exhibition(
                                        "hidden-upcoming",
                                        "later-event",
                                        venueNameKo = "hidden-venue",
                                        openingDate = LocalDate.parse("2026-07-08"),
                                    ),
                                ),
                        ),
                )

            val vm = EventDetailViewModel("later-event", repo, todayProvider = { today })
            advanceUntilIdle()

            val content = assertIs<EventDetailUiState.Content>(vm.state.value)
            assertEquals("later-event", content.event.id)
            assertEquals(listOf("later-a", "later-b"), content.exhibitions.map { it.id })
            assertEquals(listOf("venue-a", "venue-b"), content.venuesKo)
        }

    @Test
    fun a_failed_event_read_shows_the_error_and_retry_recovers() =
        runTest(dispatcher) {
            val repo = FakeEventRepository(events = listOf(event("e", "2026-06-10", "2026-06-30")))
            repo.eventFailure = IllegalStateException("offline")

            val vm = EventDetailViewModel("e", repo, todayProvider = { today })
            advanceUntilIdle()
            assertEquals(EventDetailUiState.Error, vm.state.value)

            repo.eventFailure = null
            vm.retry()
            advanceUntilIdle()

            assertEquals("e", assertIs<EventDetailUiState.Content>(vm.state.value).event.id)
            assertEquals(2, repo.eventReads)
        }

    @Test
    fun a_failed_exhibitions_read_shows_the_error_instead_of_a_partial_page() =
        runTest(dispatcher) {
            val repo = FakeEventRepository(events = listOf(event("e", "2026-06-10", "2026-06-30")))
            repo.exhibitionsFailure = IllegalStateException("offline")

            val vm = EventDetailViewModel("e", repo, todayProvider = { today })
            advanceUntilIdle()

            assertEquals(EventDetailUiState.Error, vm.state.value)
        }

    @Test
    fun an_event_that_is_not_published_is_not_found_rather_than_loading_forever() =
        runTest(dispatcher) {
            val repo = FakeEventRepository(events = emptyList())

            val vm = EventDetailViewModel("gone", repo, todayProvider = { today })
            advanceUntilIdle()

            assertEquals(EventDetailUiState.NotFound, vm.state.value)
        }

    private class FakeEventRepository(
        private val events: List<Event>,
        private val exhibitionsByEvent: Map<String, List<Exhibition>> = emptyMap(),
    ) : EventRepository {
        var eventFailure: Throwable? = null
        var exhibitionsFailure: Throwable? = null
        var eventReads = 0
            private set

        override suspend fun getActiveEvents(): Result<List<Event>> = Result.success(events)

        override suspend fun getEventById(id: String): Result<Event?> {
            eventReads += 1
            return eventFailure?.let { Result.failure(it) } ?: Result.success(events.firstOrNull { it.id == id })
        }

        override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
            exhibitionsFailure?.let { Result.failure(it) } ?: Result.success(exhibitionsByEvent[id] ?: emptyList())
    }

    private fun event(
        id: String,
        start: String,
        end: String,
    ) = Event(
        id = id,
        nameKo = id,
        nameEn = id,
        descriptionKo = "",
        descriptionEn = "",
        locationLabelKo = "서울",
        locationLabelEn = "Seoul",
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        brandColor = "#000000",
        ticketUrl = null,
        isActive = true,
    )

    private fun exhibition(
        id: String,
        eventId: String,
        venueNameKo: String = "venue",
        openingDate: LocalDate = LocalDate.parse("2026-06-01"),
        closingDate: LocalDate = LocalDate.parse("2026-08-01"),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = venueNameKo,
        venueNameEn = venueNameKo,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "용산구",
        regionEn = "Yongsan-gu",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        latitude = 37.536594,
        longitude = 126.998476,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )
}
