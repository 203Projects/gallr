package com.gallr.shared.data.model

import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExhibitionVisitTest {
    @Test
    fun `snapshot preserves exhibition display fields`() {
        val exhibition = exhibition("ex-1")

        val snapshot = ExhibitionVisitSnapshot.from(exhibition)

        assertEquals("전시 ex-1", snapshot.nameKo)
        assertEquals("Exhibition ex-1", snapshot.nameEn)
        assertEquals("갤러리", snapshot.venueNameKo)
        assertEquals("Gallery", snapshot.venueNameEn)
        assertEquals(exhibition.openingDate, snapshot.openingDate)
        assertEquals(exhibition.closingDate, snapshot.closingDate)
        assertEquals("https://example.com/ex-1.jpg", snapshot.coverImageUrl)
    }

    @Test
    fun `snapshot uses bilingual fallback helpers`() {
        val snapshot =
            ExhibitionVisitSnapshot.from(
                exhibition("fallback").copy(nameEn = "", venueNameEn = ""),
            )

        assertEquals("전시 fallback", snapshot.localizedName(AppLanguage.EN))
        assertEquals("갤러리", snapshot.localizedVenueName(AppLanguage.EN))
    }

    @Test
    fun `visit rejects blank identifiers`() {
        val snapshot = ExhibitionVisitSnapshot.from(exhibition("ex-1"))

        assertFailsWith<IllegalArgumentException> {
            ExhibitionVisit(
                clientRecordId = " ",
                exhibitionId = "ex-1",
                snapshot = snapshot,
                createdAt = Instant.parse("2026-08-13T00:00:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExhibitionVisit(
                clientRecordId = "record-1",
                exhibitionId = " ",
                snapshot = snapshot,
                createdAt = Instant.parse("2026-08-13T00:00:00Z"),
            )
        }
    }

    @Test
    fun `a stored 1_15 visit without diary keys decodes with empty diary fields`() {
        val visit = storedJson.decodeFromString<ExhibitionVisit>(STORED_1_15_VISIT)

        assertEquals("ex-1", visit.exhibitionId)
        assertEquals(Instant.parse("2026-08-13T00:00:00Z"), visit.createdAt)
        assertNull(visit.visitDate)
        assertNull(visit.feeling)
        assertTrue(visit.tags.isEmpty())
        assertNull(visit.note)
        assertNull(visit.entryUpdatedAt)
    }

    @Test
    fun `diary fields survive a round trip`() {
        val visit =
            storedJson.decodeFromString<ExhibitionVisit>(STORED_1_15_VISIT).copy(
                visitDate = LocalDate(2026, 8, 12),
                feelingWire = VisitFeeling.LOVED.wire,
                tagWires = listOf(VisitTag.THE_WORK.wire, VisitTag.HEAVY.wire),
                note = "메모",
                entryUpdatedAt = Instant.parse("2026-08-14T00:00:00Z"),
            )

        val decoded = storedJson.decodeFromString<ExhibitionVisit>(storedJson.encodeToString(visit))

        assertEquals(visit, decoded)
        assertEquals(VisitFeeling.LOVED, decoded.feeling)
        assertEquals(listOf(VisitTag.THE_WORK, VisitTag.HEAVY), decoded.tags)
    }

    @Test
    fun `an unknown feeling or tag decodes to none instead of failing the payload`() {
        val visit =
            storedJson.decodeFromString<ExhibitionVisit>(
                STORED_1_15_VISIT.replace(
                    "\"createdAt\"",
                    "\"feeling\":\"ecstatic\",\"tags\":[\"the_food\",\"playful\"],\"createdAt\"",
                ),
            )

        assertNull(visit.feeling)
        assertEquals(listOf(VisitTag.PLAYFUL), visit.tags)
    }

    private val storedJson =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private fun exhibition(id: String) =
        Exhibition(
            id = id,
            nameKo = "전시 $id",
            nameEn = "Exhibition $id",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 8, 1),
            closingDate = LocalDate(2026, 8, 31),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = "https://example.com/$id.jpg",
        )

    private companion object {
        /** A visit as 1.15 wrote it into `my_gallr_exhibition_visits_v1` and `my_gallr_account_state_v1`. */
        val STORED_1_15_VISIT =
            """
            {"clientRecordId":"record-1","exhibitionId":"ex-1","snapshot":{"nameKo":"전시","nameEn":"Exhibition",
            "venueNameKo":"갤러리","venueNameEn":"Gallery","openingDate":"2026-08-01","closingDate":"2026-08-31",
            "coverImageUrl":null,"galleryId":null},"createdAt":"2026-08-13T00:00:00Z"}
            """.trimIndent()
    }
}
