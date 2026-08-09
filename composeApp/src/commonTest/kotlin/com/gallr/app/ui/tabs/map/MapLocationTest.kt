package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.ExhibitionMapPin
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MapLocationTest {
    @Test
    fun single_pin_label_truncates_long_exhibition_name() {
        val location =
            MapLocation(
                latitude = 37.5,
                longitude = 127.0,
                pins = listOf(pin("0123456789abcdefZ")),
            )

        assertEquals("0123456789abcdef\u2026", location.label)
    }

    @Test
    fun single_pin_label_keeps_names_at_the_limit() {
        val location =
            MapLocation(
                latitude = 37.5,
                longitude = 127.0,
                pins = listOf(pin("0123456789abcdef")),
            )

        assertEquals("0123456789abcdef", location.label)
    }

    @Test
    fun multi_pin_label_uses_count_without_truncation() {
        val location =
            MapLocation(
                latitude = 37.5,
                longitude = 127.0,
                pins = listOf(pin("long exhibition name"), pin("another long exhibition name")),
            )

        assertEquals("2", location.label)
    }

    private fun pin(name: String) =
        ExhibitionMapPin(
            id = name,
            name = name,
            venueName = "venue",
            latitude = 37.5,
            longitude = 127.0,
            openingDate = LocalDate(2026, 6, 1),
            closingDate = LocalDate(2026, 7, 1),
        )
}
