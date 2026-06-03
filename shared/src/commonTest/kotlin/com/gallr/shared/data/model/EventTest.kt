package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTest {

    private val sample = Event(
        id = "loop-lab-busan-2025",
        nameKo = "루프랩 부산 2025",
        nameEn = "Loop Lab Busan 2025",
        descriptionKo = "한국어 설명",
        descriptionEn = "English description",
        locationLabelKo = "부산 전역",
        locationLabelEn = "Across Busan",
        startDate = LocalDate(2025, 4, 18),
        endDate = LocalDate(2025, 5, 10),
        brandColor = "#0099FF",
        ticketUrl = "https://example.com/tickets",
        isActive = true,
    )

    @Test
    fun `localizedName returns Korean for KO`() {
        assertEquals("루프랩 부산 2025", sample.localizedName(AppLanguage.KO))
    }

    @Test
    fun `localizedName returns English for EN`() {
        assertEquals("Loop Lab Busan 2025", sample.localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName falls back to Korean when English is empty`() {
        val koOnly = sample.copy(nameEn = "")
        assertEquals("루프랩 부산 2025", koOnly.localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedLocationLabel falls back to Korean when English is empty`() {
        val koOnly = sample.copy(locationLabelEn = "")
        assertEquals("부산 전역", koOnly.localizedLocationLabel(AppLanguage.EN))
    }

    @Test
    fun `localizedDescription returns English when both present`() {
        assertEquals("English description", sample.localizedDescription(AppLanguage.EN))
    }

    @Test
    fun `isActiveOn returns true when today equals start date`() {
        assertEquals(true, sample.isActiveOn(LocalDate(2025, 4, 18)))
    }

    @Test
    fun `isActiveOn returns true when today equals end date`() {
        assertEquals(true, sample.isActiveOn(LocalDate(2025, 5, 10)))
    }

    @Test
    fun `isActiveOn returns false the day after end date`() {
        assertEquals(false, sample.isActiveOn(LocalDate(2025, 5, 11)))
    }

    @Test
    fun `isActiveOn returns false the day before start date`() {
        assertEquals(false, sample.isActiveOn(LocalDate(2025, 4, 17)))
    }

    @Test
    fun `isActiveOn returns false when isActive flag is false`() {
        val killed = sample.copy(isActive = false)
        assertEquals(false, killed.isActiveOn(LocalDate(2025, 4, 20)))
    }

    // ── ribbonLabel ────────────────────────────────────────────────────────

    @Test
    fun `ribbonLabel uses shortLabel when present`() {
        val withShort = sample.copy(shortLabel = "FLUX 614")
        assertEquals("FLUX 614", withShort.ribbonLabel(AppLanguage.KO))
        assertEquals("FLUX 614", withShort.ribbonLabel(AppLanguage.EN))
    }

    @Test
    fun `ribbonLabel trims shortLabel and ignores blank`() {
        assertEquals("FLUX 614", sample.copy(shortLabel = "  FLUX 614  ").ribbonLabel(AppLanguage.EN))
        // blank short label falls back to truncated name
        assertEquals("Loop Lab Bus…", sample.copy(shortLabel = "   ").ribbonLabel(AppLanguage.EN))
    }

    @Test
    fun `ribbonLabel falls back to truncated localized name when shortLabel null`() {
        // "Loop Lab Busan 2025" → first 12 chars + ellipsis
        assertEquals("Loop Lab Bus…", sample.ribbonLabel(AppLanguage.EN))
        // "루프랩 부산 2025" is 12 chars → no truncation
        assertEquals("루프랩 부산 2025", sample.ribbonLabel(AppLanguage.KO))
    }

    @Test
    fun `ribbonLabel does not truncate names at or under the limit`() {
        val short = sample.copy(nameEn = "Dance Hall") // 10 chars
        assertEquals("Dance Hall", short.ribbonLabel(AppLanguage.EN))
    }

    @Test
    fun `EventDto toDomain maps short_label`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "x",
            nameKo = "x", nameEn = "x",
            locationLabelKo = "x", locationLabelEn = "x",
            startDate = "2025-04-18",
            endDate = "2025-05-10",
            brandColor = "#000000",
            shortLabel = "FLUX 614",
        )
        assertEquals("FLUX 614", dto.toDomain()!!.shortLabel)
    }

    @Test
    fun `EventDto toDomain defaults short_label to null when absent`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "x",
            nameKo = "x", nameEn = "x",
            locationLabelKo = "x", locationLabelEn = "x",
            startDate = "2025-04-18",
            endDate = "2025-05-10",
            brandColor = "#000000",
        )
        kotlin.test.assertNull(dto.toDomain()!!.shortLabel)
    }

    @Test
    fun `EventDto toDomain returns null when start_date is malformed`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "x",
            nameKo = "x", nameEn = "x",
            locationLabelKo = "x", locationLabelEn = "x",
            startDate = "not-a-date",
            endDate = "2025-05-10",
            brandColor = "#000000",
        )
        kotlin.test.assertNull(dto.toDomain())
    }

    @Test
    fun `EventDto toDomain returns Event with parsed dates and defaults`() {
        val dto = com.gallr.shared.data.network.dto.EventDto(
            id = "loop-lab-busan-2025",
            nameKo = "루프랩 부산 2025", nameEn = "Loop Lab Busan 2025",
            locationLabelKo = "부산 전역", locationLabelEn = "Across Busan",
            startDate = "2025-04-18",
            endDate = "2025-05-10",
            brandColor = "#0099FF",
        )
        val event = dto.toDomain()!!
        kotlin.test.assertEquals(LocalDate(2025, 4, 18), event.startDate)
        kotlin.test.assertEquals(LocalDate(2025, 5, 10), event.endDate)
        kotlin.test.assertEquals("", event.descriptionKo)  // default
        kotlin.test.assertEquals(true, event.isActive)     // default
        kotlin.test.assertEquals(null, event.coverImageUrl)  // optional default
    }
}
