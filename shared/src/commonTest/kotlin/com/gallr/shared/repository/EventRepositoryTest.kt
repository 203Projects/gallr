package com.gallr.shared.repository

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.EventApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRepositoryTest {

    private fun event(
        id: String,
        start: LocalDate,
        end: LocalDate,
        active: Boolean = true,
    ) = Event(
        id = id,
        nameKo = id, nameEn = id,
        descriptionKo = "", descriptionEn = "",
        locationLabelKo = "x", locationLabelEn = "x",
        startDate = start, endDate = end,
        brandColor = "#000000", ticketUrl = null,
        isActive = active,
    )

    private class FakeEventApi(
        private val events: List<Event>,
        private val exhibitionsByEventId: Map<String, List<Exhibition>> = emptyMap(),
    ) : EventApi {
        override suspend fun fetchEvents(): List<Event> = events
        override suspend fun fetchEventById(id: String): Event? = events.firstOrNull { it.id == id }
        override suspend fun fetchExhibitionsForEvent(id: String): List<Exhibition> =
            exhibitionsByEventId[id] ?: emptyList()
    }

    private val today = LocalDate(2025, 4, 22)

    @Test
    fun `getActiveEvents includes event whose date range covers today`() = runTest {
        val active = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(active))) { today }
        val result = repo.getActiveEvents().getOrThrow()
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `getActiveEvents excludes event whose end_date is before today`() = runTest {
        val past = event("p", LocalDate(2025, 1, 1), LocalDate(2025, 4, 21))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(past))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents excludes event whose start_date is after today`() = runTest {
        val future = event("f", LocalDate(2025, 4, 23), LocalDate(2025, 5, 30))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(future))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents excludes event with isActive false even when in date range`() = runTest {
        val killed = event("k", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10), active = false)
        val repo = EventRepositoryImpl(FakeEventApi(listOf(killed))) { today }
        assertTrue(repo.getActiveEvents().getOrThrow().isEmpty())
    }

    @Test
    fun `getActiveEvents sorts by start_date ascending`() = runTest {
        val later = event("later", LocalDate(2025, 4, 20), LocalDate(2025, 5, 10))
        val earlier = event("earlier", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(later, earlier))) { today }
        val result = repo.getActiveEvents().getOrThrow()
        assertEquals(listOf("earlier", "later"), result.map { it.id })
    }

    @Test
    fun `getEventById returns null when event missing`() = runTest {
        val repo = EventRepositoryImpl(FakeEventApi(emptyList())) { today }
        assertNull(repo.getEventById("missing").getOrThrow())
    }

    @Test
    fun `getEventById returns the event when present`() = runTest {
        val e = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val repo = EventRepositoryImpl(FakeEventApi(listOf(e))) { today }
        assertNotNull(repo.getEventById("a").getOrThrow())
    }

    @Test
    fun `getExhibitionsForEvent returns the exhibitions for the event id`() = runTest {
        val e = event("a", LocalDate(2025, 4, 18), LocalDate(2025, 5, 10))
        val exh = listOf(stubExhibition("ex1", "a"), stubExhibition("ex2", "a"))
        val repo = EventRepositoryImpl(
            FakeEventApi(events = listOf(e), exhibitionsByEventId = mapOf("a" to exh))
        ) { today }
        assertEquals(listOf("ex1", "ex2"), repo.getExhibitionsForEvent("a").getOrThrow().map { it.id })
    }

    private fun stubExhibition(id: String, eventId: String) = Exhibition(
        id = id,
        nameKo = id, nameEn = id,
        venueNameKo = "venue", venueNameEn = "venue",
        cityKo = "city", cityEn = "city",
        regionKo = "region", regionEn = "region",
        openingDate = LocalDate(2025, 4, 18),
        closingDate = LocalDate(2025, 5, 10),
        isFeatured = false, isEditorsPick = false,
        latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "",
        addressKo = "", addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )
}
