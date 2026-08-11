package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTest {
    private val sample =
        Event(
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
    fun `isVisibleOn returns true when today equals start date`() {
        assertEquals(true, sample.isVisibleOn(LocalDate(2025, 4, 18)))
    }

    @Test
    fun `isVisibleOn returns true when today equals end date`() {
        assertEquals(true, sample.isVisibleOn(LocalDate(2025, 5, 10)))
    }

    @Test
    fun `isVisibleOn returns true before start date — upcoming events surface`() {
        assertEquals(true, sample.isVisibleOn(LocalDate(2025, 4, 17)))
        assertEquals(true, sample.isVisibleOn(LocalDate(2025, 1, 1)))
    }

    @Test
    fun `isVisibleOn returns false the day after end date — ended events auto-retire`() {
        assertEquals(false, sample.isVisibleOn(LocalDate(2025, 5, 11)))
    }

    @Test
    fun `isVisibleOn returns false when isActive flag is false`() {
        val killed = sample.copy(isActive = false)
        // even before start and within range
        assertEquals(false, killed.isVisibleOn(LocalDate(2025, 4, 1)))
        assertEquals(false, killed.isVisibleOn(LocalDate(2025, 4, 20)))
    }

    // ── phaseOn / statusEyebrow ─────────────────────────────────────────────

    @Test
    fun `phaseOn is UPCOMING before start date and LIVE from start date on`() {
        assertEquals(EventPhase.UPCOMING, sample.phaseOn(LocalDate(2025, 4, 17)))
        assertEquals(EventPhase.LIVE, sample.phaseOn(LocalDate(2025, 4, 18))) // start day
        assertEquals(EventPhase.LIVE, sample.phaseOn(LocalDate(2025, 5, 10))) // end day
    }

    @Test
    fun `statusEyebrow reads Upcoming before start`() {
        val before = LocalDate(2025, 4, 17)
        assertEquals("예정", sample.statusEyebrow(before, AppLanguage.KO))
        assertEquals("Upcoming", sample.statusEyebrow(before, AppLanguage.EN))
    }

    @Test
    fun `statusEyebrow reads NOW ON on and after start`() {
        val onStart = LocalDate(2025, 4, 18)
        assertEquals("지금 진행 중", sample.statusEyebrow(onStart, AppLanguage.KO))
        assertEquals("NOW ON", sample.statusEyebrow(onStart, AppLanguage.EN))
    }

    @Test
    fun `statusLabel is the compact detail-eyebrow variant`() {
        val before = LocalDate(2025, 4, 17)
        val onStart = LocalDate(2025, 4, 18)
        // upcoming matches statusEyebrow
        assertEquals("예정", sample.statusLabel(before, AppLanguage.KO))
        assertEquals("Upcoming", sample.statusLabel(before, AppLanguage.EN))
        // active KO is the shorter "진행 중" (vs statusEyebrow's "지금 진행 중")
        assertEquals("진행 중", sample.statusLabel(onStart, AppLanguage.KO))
        assertEquals("NOW ON", sample.statusLabel(onStart, AppLanguage.EN))
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
        val dto =
            com.gallr.shared.data.network.dto.EventDto(
                id = "x",
                nameKo = "x",
                nameEn = "x",
                locationLabelKo = "x",
                locationLabelEn = "x",
                startDate = "2025-04-18",
                endDate = "2025-05-10",
                brandColor = "#000000",
                shortLabel = "FLUX 614",
            )
        assertEquals("FLUX 614", dto.toDomain()!!.shortLabel)
    }

    @Test
    fun `EventDto toDomain defaults short_label to null when absent`() {
        val dto =
            com.gallr.shared.data.network.dto.EventDto(
                id = "x",
                nameKo = "x",
                nameEn = "x",
                locationLabelKo = "x",
                locationLabelEn = "x",
                startDate = "2025-04-18",
                endDate = "2025-05-10",
                brandColor = "#000000",
            )
        kotlin.test.assertNull(dto.toDomain()!!.shortLabel)
    }

    @Test
    fun `EventDto toDomain returns null when start_date is malformed`() {
        val dto =
            com.gallr.shared.data.network.dto.EventDto(
                id = "x",
                nameKo = "x",
                nameEn = "x",
                locationLabelKo = "x",
                locationLabelEn = "x",
                startDate = "not-a-date",
                endDate = "2025-05-10",
                brandColor = "#000000",
            )
        kotlin.test.assertNull(dto.toDomain())
    }

    @Test
    fun `EventDto toDomain returns Event with parsed dates and defaults`() {
        val dto =
            com.gallr.shared.data.network.dto.EventDto(
                id = "loop-lab-busan-2025",
                nameKo = "루프랩 부산 2025",
                nameEn = "Loop Lab Busan 2025",
                locationLabelKo = "부산 전역",
                locationLabelEn = "Across Busan",
                startDate = "2025-04-18",
                endDate = "2025-05-10",
                brandColor = "#0099FF",
            )
        val event = dto.toDomain()!!
        kotlin.test.assertEquals(LocalDate(2025, 4, 18), event.startDate)
        kotlin.test.assertEquals(LocalDate(2025, 5, 10), event.endDate)
        kotlin.test.assertEquals("", event.descriptionKo) // default
        kotlin.test.assertEquals(true, event.isActive) // default
        kotlin.test.assertEquals(null, event.coverImageUrl) // optional default
    }
}
