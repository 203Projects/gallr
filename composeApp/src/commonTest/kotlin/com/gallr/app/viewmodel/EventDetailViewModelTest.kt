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

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_exhibitions_for_requested_later_event_id() = runTest(dispatcher) {
        val repo = FakeEventRepository(
            events = listOf(
                event("first-event", "2026-06-10", "2026-06-20"),
                event("later-event", "2026-06-16", "2026-06-16"),
            ),
            exhibitionsByEvent = mapOf(
                "first-event" to listOf(exhibition("first-a", "first-event")),
                "later-event" to listOf(exhibition("later-a", "later-event"), exhibition("later-b", "later-event")),
            ),
        )

        val vm = EventDetailViewModel("later-event", repo)
        advanceUntilIdle()

        assertEquals("later-event", vm.event.value?.id)
        assertEquals(listOf("later-a", "later-b"), vm.exhibitions.value.map { it.id })
        assertEquals(false, vm.isLoading.value)
    }

    private class FakeEventRepository(
        private val events: List<Event>,
        private val exhibitionsByEvent: Map<String, List<Exhibition>>,
    ) : EventRepository {
        override suspend fun getActiveEvents(): Result<List<Event>> = Result.success(events)
        override suspend fun getEventById(id: String): Result<Event?> =
            Result.success(events.firstOrNull { it.id == id })

        override suspend fun getExhibitionsForEvent(id: String): Result<List<Exhibition>> =
            Result.success(exhibitionsByEvent[id] ?: emptyList())
    }

    private fun event(id: String, start: String, end: String) = Event(
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

    private fun exhibition(id: String, eventId: String) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "venue",
        venueNameEn = "venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "용산구",
        regionEn = "Yongsan-gu",
        openingDate = LocalDate.parse("2026-06-01"),
        closingDate = LocalDate.parse("2026-08-01"),
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
