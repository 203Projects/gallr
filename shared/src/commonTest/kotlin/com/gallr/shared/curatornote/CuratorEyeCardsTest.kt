package com.gallr.shared.curatornote

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CuratorEyeCardsTest {
    private val today = LocalDate(2026, 10, 11)

    @Test
    fun `cards keep only notes whose exhibition is in the catalogue and still running`() {
        val catalogue = listOf(exhibition("a"), exhibition("b"), exhibition("ended", closing = LocalDate(2026, 10, 10)))
        val notes = listOf(note("a", day = 1), note("b", day = 2), note("ended", day = 3), note("missing", day = 4))

        val cards = curatorEyeCards(notes, catalogue, today)

        assertEquals(listOf("b", "a"), cards.map { it.exhibition.id })
    }

    @Test
    fun `cards are newest approval first and at most the limit with one per exhibition`() {
        val catalogue = (1..12).map { exhibition("e$it") }
        val notes = (1..12).map { note("e$it", day = it) } + note("e1", day = 20)

        val cards = curatorEyeCards(notes, catalogue, today)

        assertEquals(CURATOR_EYE_LIMIT, cards.size)
        assertEquals("e1", cards.first().exhibition.id)
        assertEquals(cards.map { it.exhibition.id }.distinct(), cards.map { it.exhibition.id })
        val limited = curatorEyeCards(notes.take(3), catalogue, today, limit = 2)
        assertEquals(listOf("e3", "e2"), limited.map { it.exhibition.id })
    }

    @Test
    fun `language helpers fall back to Korean when English is empty`() {
        val bilingual =
            note("a", day = 1).copy(
                hookEn = "Hook",
                insightsEn = listOf("One", "Two"),
                tipEn = "Tip",
                reviewerNameEn = "Jiwon Kim",
            )
        val koreanOnly = note("b", day = 1)

        assertEquals("Hook", bilingual.hook(AppLanguage.EN))
        assertEquals("훅 a", bilingual.hook(AppLanguage.KO))
        assertEquals("One", bilingual.firstInsight(AppLanguage.EN))
        assertEquals(listOf("One", "Two"), bilingual.insights(AppLanguage.EN))
        assertEquals("Tip", bilingual.tip(AppLanguage.EN))
        assertEquals("Jiwon Kim", bilingual.reviewerName(AppLanguage.EN))

        assertEquals("훅 b", koreanOnly.hook(AppLanguage.EN))
        assertEquals("첫 통찰 b", koreanOnly.firstInsight(AppLanguage.EN))
        assertEquals(listOf("첫 통찰 b", "둘째 통찰 b"), koreanOnly.insights(AppLanguage.EN))
        assertEquals("팁 b", koreanOnly.tip(AppLanguage.EN))
        assertEquals("김지원", koreanOnly.reviewerName(AppLanguage.EN))
    }

    private fun note(
        exhibitionId: String,
        day: Int,
    ) = CuratorNote(
        exhibitionId = exhibitionId,
        versionNumber = 1,
        hookKo = "훅 $exhibitionId",
        hookEn = "",
        insightsKo = listOf("첫 통찰 $exhibitionId", "둘째 통찰 $exhibitionId"),
        insightsEn = emptyList(),
        tipKo = "팁 $exhibitionId",
        tipEn = "",
        reviewerNameKo = "김지원",
        reviewerNameEn = "",
        approvedAt = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T03:00:00Z"),
    )

    private fun exhibition(
        id: String,
        closing: LocalDate = LocalDate(2026, 11, 30),
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "장소",
        venueNameEn = "Venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "",
        regionEn = "",
        openingDate = LocalDate(2026, 9, 1),
        closingDate = closing,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
    )
}
