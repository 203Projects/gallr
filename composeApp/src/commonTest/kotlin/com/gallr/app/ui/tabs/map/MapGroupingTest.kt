package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.ExhibitionMapPin
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MapGroupingTest {

    @Test
    fun groupPinsByLocation_preserves_pins_from_multiple_events_at_same_coordinates() {
        val locations = groupPinsByLocation(
            listOf(
                pin("a", eventId = "first-event", brand = "#FF5CB3"),
                pin("b", eventId = "later-event", brand = "#F0BE1D"),
            )
        )

        assertEquals(1, locations.size)
        assertEquals(setOf("first-event", "later-event"), locations.single().pins.map { it.eventId }.toSet())
        assertEquals(setOf("#FF5CB3", "#F0BE1D"), locations.single().pins.map { it.brandColorHex }.toSet())
    }

    private fun pin(id: String, eventId: String, brand: String) = ExhibitionMapPin(
        id = id,
        name = id,
        venueName = "venue",
        latitude = 37.536594,
        longitude = 126.998476,
        openingDate = LocalDate.parse("2026-06-01"),
        closingDate = LocalDate.parse("2026-08-01"),
        eventId = eventId,
        brandColorHex = brand,
    )
}
