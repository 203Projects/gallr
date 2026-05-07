package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC = TimeZone.UTC

private fun fixture(
    id: String = "ex1",
    nameEn: String = "Color Field",
    nameKo: String = "색면",
    venueEn: String = "Pace",
    venueKo: String = "페이스",
    opening: LocalDate,
    closing: LocalDate,
    reception: LocalDate? = null,
): Exhibition = Exhibition(
    id = id,
    nameKo = nameKo,
    nameEn = nameEn,
    venueNameKo = venueKo,
    venueNameEn = venueEn,
    cityKo = "", cityEn = "",
    regionKo = "", regionEn = "",
    openingDate = opening,
    closingDate = closing,
    isFeatured = false,
    isEditorsPick = false,
    latitude = null, longitude = null,
    descriptionKo = "", descriptionEn = "",
    addressKo = "", addressEn = "",
    coverImageUrl = null,
    receptionDate = reception,
)

private fun localDateAt9amInstant(date: LocalDate, tz: TimeZone): Instant =
    date.atTime(LocalTime(9, 0)).toInstant(tz)

class TriggerRulesTest {

    @Test
    fun `closing in 5 days at noon UTC - returns CLOSING at closing-3d 9am UTC`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 6))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        val closing = triggers.firstOrNull { it.id == "ex1_closing" }
        assertNotNull(closing)
        assertEquals(localDateAt9amInstant(LocalDate(2026, 5, 3), UTC), closing.triggerAt)
        assertEquals(DeepLink.Exhibition("ex1"), closing.deepLink)
    }

    @Test
    fun `closing in 2 days - CLOSING past-due is skipped`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 3))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_closing" })
    }

    @Test
    fun `opens in 10 days - returns OPENING + CLOSING`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 5, 11), closing = LocalDate(2026, 6, 11))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNotNull(triggers.firstOrNull { it.id == "ex1_opening" })
        assertNotNull(triggers.firstOrNull { it.id == "ex1_closing" })
        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `reception today at 8am - RECEPTION at 9am same day is valid`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = LocalDate(2026, 5, 1),
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        val rec = triggers.firstOrNull { it.id == "ex1_reception" }
        assertNotNull(rec)
        assertEquals(localDateAt9amInstant(LocalDate(2026, 5, 1), UTC), rec.triggerAt)
    }

    @Test
    fun `reception today at 10am - RECEPTION past-due is skipped`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(10, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = LocalDate(2026, 5, 1),
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `receptionDate null - no RECEPTION trigger`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = null,
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `1-day pop-up exhibition - both 3-day triggers past so all skipped`() {
        // opening tomorrow, closing day after — 3-day-before triggers are in the past
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 5, 2), closing = LocalDate(2026, 5, 3))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertTrue(triggers.isEmpty(), "all triggers past-due for 1-day pop-up")
    }

    @Test
    fun `Korean language renders KO body`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 6))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.KO)

        val closing = triggers.firstOrNull { it.id == "ex1_closing" }
        assertNotNull(closing)
        assertEquals("색면 마감 3일 전입니다. 놓치지 마세요.", closing.body)
    }
}
