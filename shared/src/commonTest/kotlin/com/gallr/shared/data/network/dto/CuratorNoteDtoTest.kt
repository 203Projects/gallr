package com.gallr.shared.data.network.dto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CuratorNoteDtoTest {
    private val wellFormed =
        """
        {
          "exhibition_id": "who-lived-happily", "version_number": 2,
          "hook_ko": "곰 한 마리가 동화 속으로 들어간 이유", "hook_en": "",
          "insights_ko": [{"text": "첫 통찰.", "grounding": ["description_ko"]}, {"text": "둘째 통찰.", "grounding": ["artists"]}],
          "insights_en": [],
          "tip_ko": "복도 끝의 방부터 보라.", "tip_en": "",
          "reviewer_name_ko": "김지원", "reviewer_name_en": "Jiwon Kim",
          "approved_at": "2026-10-11T03:10:00+00:00", "closing_date": "2026-10-31", "unknown_key": true
        }
        """.trimIndent()

    @Test
    fun `a well-formed row maps to the domain note`() {
        val page = decodeCuratorNotePage("[$wellFormed]")

        assertEquals(0, page.skipped)
        val note = page.notes.single()
        assertEquals("who-lived-happily", note.exhibitionId)
        assertEquals(2, note.versionNumber)
        assertEquals(listOf("첫 통찰.", "둘째 통찰."), note.insightsKo)
        assertEquals(emptyList(), note.insightsEn)
        assertEquals("Jiwon Kim", note.reviewerNameEn)
        assertEquals(Instant.parse("2026-10-11T03:10:00Z"), note.approvedAt)
    }

    @Test
    fun `a malformed row is skipped and counted while the others survive`() {
        val missingHook = wellFormed.replace("\"hook_ko\": \"곰 한 마리가 동화 속으로 들어간 이유\"", "\"hook_ko\": \"\"")
        val badDate = wellFormed.replace("2026-10-11T03:10:00+00:00", "yesterday")
        val missingId = wellFormed.replace("\"exhibition_id\": \"who-lived-happily\", ", "")

        val page = decodeCuratorNotePage("[$missingHook, $wellFormed, $badDate, $missingId]")

        assertEquals(1, page.notes.size)
        assertEquals(3, page.skipped)
    }

    @Test
    fun `optional fields default to empty and reviewer names to empty strings`() {
        val sparse =
            """[{"exhibition_id": "x", "version_number": 1, "hook_ko": "훅", "insights_ko": [{"text": "a"}, {"text": "b"}],
                "tip_ko": "팁", "approved_at": "2026-10-11T03:10:00Z"}]"""

        val note = decodeCuratorNotePage(sparse).notes.single()

        assertEquals("", note.hookEn)
        assertEquals("", note.tipEn)
        assertEquals("", note.reviewerNameKo)
        assertEquals(listOf("a", "b"), note.insightsKo)
    }

    @Test
    fun `a body that is not an array yields an empty page`() {
        assertEquals(0, decodeCuratorNotePage("{}").notes.size)
        assertEquals(0, decodeCuratorNotePage("not json").notes.size)
    }
}
