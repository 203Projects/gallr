package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExhibitionMapPinTest {

    private val loopLabEvent = Event(
        id = "loop-lab-busan-2025",
        nameKo = "루프랩 부산 2025",
        nameEn = "Loop Lab Busan 2025",
        descriptionKo = "",
        descriptionEn = "",
        locationLabelKo = "부산 전역",
        locationLabelEn = "Across Busan",
        startDate = LocalDate(2025, 4, 18),
        endDate = LocalDate(2025, 5, 10),
        brandColor = "#0099FF",
        ticketUrl = null,
        isActive = true,
    )

    private fun exhibition(
        eventId: String? = null,
        latitude: Double? = 37.5665,
        longitude: Double? = 126.9780,
    ) = Exhibition(
        id = "x1",
        nameKo = "전시",
        nameEn = "Exhibition",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "강남구",
        regionEn = "Gangnam",
        openingDate = LocalDate(2026, 4, 1),
        closingDate = LocalDate(2026, 5, 1),
        isFeatured = false,
        isEditorsPick = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )

    @Test
    fun `toMapPin with empty eventsById leaves brandColorHex null`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(AppLanguage.KO, emptyMap())
        assertEquals("loop-lab-busan-2025", pin?.eventId)
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with matching event writes brandColorHex from event`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(
            AppLanguage.KO,
            mapOf(loopLabEvent.id to loopLabEvent),
        )
        assertEquals("#0099FF", pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with non matching event leaves brandColorHex null`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(
            AppLanguage.KO,
            mapOf("other-event" to loopLabEvent.copy(id = "other-event")),
        )
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with null eventId leaves eventId and brandColorHex null`() {
        val pin = exhibition(eventId = null).toMapPin(
            AppLanguage.KO,
            mapOf(loopLabEvent.id to loopLabEvent),
        )
        assertNull(pin?.eventId)
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin returns null when latitude missing`() {
        val pin = exhibition(latitude = null).toMapPin(AppLanguage.KO, emptyMap())
        assertNull(pin)
    }

    @Test
    fun `toMapPin returns null when longitude missing`() {
        val pin = exhibition(longitude = null).toMapPin(AppLanguage.KO, emptyMap())
        assertNull(pin)
    }
}
