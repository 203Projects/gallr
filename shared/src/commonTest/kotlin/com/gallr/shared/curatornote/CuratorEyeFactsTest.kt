package com.gallr.shared.curatornote

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class CuratorEyeFactsTest {
    private val today = LocalDate(2026, 10, 13)

    @Test
    fun `the closing badge counts down through the last two weeks and stops at the closing day`() {
        assertNull(closingBadge(15))
        assertEquals("D-14", closingBadge(14))
        assertEquals("D-1", closingBadge(1))
        assertEquals("D-DAY", closingBadge(0))
        assertNull(closingBadge(-1))
    }

    @Test
    fun `cards carry the days left to the closing date`() {
        val cards =
            curatorEyeCards(
                notes = listOf(note("a")),
                catalogue = listOf(exhibition("a", closing = LocalDate(2026, 10, 31))),
                today = today,
            )
        assertEquals(18, cards.single().daysToClosing)
        assertNull(closingBadge(cards.single().daysToClosing))
    }

    @Test
    fun `the facts line names the venue and district and dates and the note's length in each language`() {
        val card = CuratorEyeCard(note("a"), exhibition("a", closing = LocalDate(2026, 10, 31)), daysToClosing = 18)
        assertEquals(
            "에스더쉬퍼 · 용산구 · 2026.09.02 – 2026.10.31 · 노트 2단락",
            curatorEyeFactsLine(card, AppLanguage.KO),
        )
        assertEquals(
            "Esther Schipper · Yongsan-gu · Sep 2 – Oct 31, 2026 · 2 insights",
            curatorEyeFactsLine(card, AppLanguage.EN),
        )
    }

    @Test
    fun `the facts line skips a blank district and counts the English insights on their own`() {
        val card =
            CuratorEyeCard(
                note("a").copy(insightsEn = listOf("One")),
                exhibition("a", closing = LocalDate(2026, 10, 31)).copy(regionKo = "", regionEn = ""),
                daysToClosing = 18,
            )
        assertEquals("에스더쉬퍼 · 2026.09.02 – 2026.10.31 · 노트 2단락", curatorEyeFactsLine(card, AppLanguage.KO))
        assertEquals("Esther Schipper · Sep 2 – Oct 31, 2026 · 1 insight", curatorEyeFactsLine(card, AppLanguage.EN))
    }

    @Test
    fun `the detail chips say when it closes and the hours on one line and where`() {
        val exhibition = exhibition("a", closing = LocalDate(2026, 10, 24), hours = "11am - 6pm\nTuesday - Saturday")
        assertEquals(
            listOf("10.24까지 · D-11", "11am - 6pm · Tuesday - Saturday", "에스더쉬퍼 · 용산구"),
            curatorNoteFactChips(exhibition, AppLanguage.KO, today),
        )
        assertEquals(
            listOf("Until Oct 24 · D-11", "11am - 6pm · Tuesday - Saturday", "Esther Schipper · Yongsan-gu"),
            curatorNoteFactChips(exhibition, AppLanguage.EN, today),
        )
    }

    @Test
    fun `the detail chips drop the badge far from closing and the hours when there are none`() {
        val exhibition = exhibition("a", closing = LocalDate(2027, 1, 3), hours = null)
        assertEquals(listOf("1.03까지", "에스더쉬퍼 · 용산구"), curatorNoteFactChips(exhibition, AppLanguage.KO, today))
        assertEquals(
            listOf("Until Jan 3", "Esther Schipper · Yongsan-gu"),
            curatorNoteFactChips(exhibition, AppLanguage.EN, today),
        )
    }

    private fun note(id: String) =
        CuratorNote(
            exhibitionId = id,
            versionNumber = 1,
            hookKo = "훅",
            hookEn = "Hook",
            insightsKo = listOf("하나", "둘"),
            insightsEn = listOf("One", "Two"),
            tipKo = "팁",
            tipEn = "Tip",
            approvedAt = Instant.parse("2026-10-12T03:00:00Z"),
        )

    private fun exhibition(
        id: String,
        closing: LocalDate,
        hours: String? = null,
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "에스더쉬퍼",
        venueNameEn = "Esther Schipper",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "용산구",
        regionEn = "Yongsan-gu",
        openingDate = LocalDate(2026, 9, 2),
        closingDate = closing,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = hours,
    )
}
