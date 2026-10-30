package com.gallr.shared.hours

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.network.dto.ExhibitionDto
import com.gallr.shared.map.NeighborhoodRoutePlanner
import com.gallr.shared.map.RouteCurationMode
import com.gallr.shared.map.RoutePlanResult
import com.gallr.shared.map.RoutePlanningRequest
import com.gallr.shared.map.RouteStopHoursStatus
import com.gallr.shared.map.RouteWarning
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Staff-confirmed weekly hours from the catalogue's `hours_weekly` (spec 101 US5, T056). */
class ConfirmedHoursTest {
    private val catalogueJson =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    /** A Monday. */
    private val monday = LocalDate(2026, 8, 31)

    @Test
    fun `a catalogue row with hours_weekly maps to confirmed verified hours`() {
        val exhibition = catalogueRow(hours = "전화 문의", hoursWeekly = TUESDAY_TO_SUNDAY_WEEK)

        val hours = exhibition.weeklyOpeningHours()

        assertEquals(OpeningHoursCompleteness.CONFIRMED, hours.completeness)
        assertTrue(hours.isVerified)
        assertEquals(hours, exhibition.confirmedHours)
        assertEquals(DailyOpening(LocalTime(10, 0), LocalTime(18, 0)), hours.byDay[DayOfWeek.TUESDAY])
        assertEquals(DailyOpening(LocalTime(11, 0), LocalTime(19, 30)), hours.byDay[DayOfWeek.SATURDAY])
        assertNull(hours.openingOn(monday))
        assertTrue(hours.isKnownClosedOn(monday), "a null weekday is a confirmed closure")
    }

    @Test
    fun `confirmed hours win over the free text parser`() {
        val exhibition = catalogueRow(hours = "10am - 6pm\nMonday - Sunday", hoursWeekly = TUESDAY_TO_SUNDAY_WEEK)

        assertTrue(exhibition.weeklyOpeningHours().isKnownClosedOn(monday))
    }

    @Test
    fun `without hours_weekly the parser result is unchanged`() {
        val text = "10am - 6pm\nTuesday - Saturday"
        val absent = catalogueRow(hours = text, hoursWeekly = null)
        val explicitNull = catalogueRow(hours = text, hoursWeekly = "null")

        for (exhibition in listOf(absent, explicitNull)) {
            assertNull(exhibition.confirmedHours)
            assertEquals(parseOpeningHours(text), exhibition.weeklyOpeningHours())
            assertEquals(OpeningHoursCompleteness.COMPLETE, exhibition.weeklyOpeningHours().completeness)
        }
    }

    @Test
    fun `a malformed hours_weekly keeps the row and falls back to the parser`() {
        val text = "12pm - 7pm"
        val malformed =
            listOf(
                "\"Tue–Sun 10:00–18:00\"",
                "[]",
                TUESDAY_TO_SUNDAY_WEEK.replace(",\"SUNDAY\":{\"opens\":\"11:00\",\"closes\":\"18:00\"}", ""),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"SUNDAY\"", "\"HOLIDAY\""),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"closes\":\"18:00\"", "\"closes\":\"09:00\""),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"opens\":\"10:00\"", "\"opens\":\"10am\""),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"opens\":\"10:00\"", "\"opens\":\"25:00\""),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"opens\":\"10:00\"", "\"opens\":10"),
                TUESDAY_TO_SUNDAY_WEEK.replace("\"MONDAY\":null", "\"MONDAY\":\"closed\""),
                TUESDAY_TO_SUNDAY_WEEK.replace(
                    "\"closes\":\"18:00\"}",
                    "\"closes\":\"18:00\",\"note\":\"x\"}",
                ),
                ALL_CLOSED_WEEK,
            )

        for (hoursWeekly in malformed) {
            val exhibition = catalogueRow(hours = text, hoursWeekly = hoursWeekly)

            assertNull(exhibition.confirmedHours, hoursWeekly)
            assertEquals(parseOpeningHours(text), exhibition.weeklyOpeningHours(), hoursWeekly)
        }
    }

    @Test
    fun `route stop disclosure no longer marks a confirmed venue unverified`() {
        val confirmed =
            catalogueRow(id = "confirmed", hours = "전화 문의", hoursWeekly = OPEN_DAILY_WEEK)
                .copy(latitude = 37.567, longitude = 126.979)
        val parsed =
            catalogueRow(id = "parsed", hours = "10am - 6pm\nMonday - Sunday", hoursWeekly = null)
                .copy(venueNameKo = "다른 갤러리", venueNameEn = "Other", latitude = 37.570, longitude = 126.985)

        val route =
            assertIs<RoutePlanResult.Success>(
                NeighborhoodRoutePlanner().plan(
                    listOf(confirmed, parsed),
                    emptySet(),
                    RoutePlanningRequest(
                        origin = GeoPoint(37.5665, 126.9780),
                        visitDate = monday,
                        mode = RouteCurationMode.NEIGHBORHOOD,
                        stopCount = 2,
                        maxRadiusKm = 5.0,
                        visitMinutesPerStop = 45,
                    ),
                ),
            ).route

        assertEquals(
            mapOf("confirmed" to RouteStopHoursStatus.VERIFIED, "parsed" to RouteStopHoursStatus.VERIFIED),
            route.stopSchedules.associate { it.exhibitionId to it.hoursStatus },
        )
        assertFalse(RouteWarning.HOURS_UNVERIFIED in route.warnings)
    }

    @Test
    fun `a confirmed venue closed on the visit day is not a stop`() {
        val closedMonday =
            catalogueRow(id = "closed", hours = "10am - 6pm\nMonday - Sunday", hoursWeekly = TUESDAY_TO_SUNDAY_WEEK)
                .copy(latitude = 37.567, longitude = 126.979)

        val result =
            NeighborhoodRoutePlanner().plan(
                listOf(closedMonday),
                emptySet(),
                RoutePlanningRequest(
                    origin = GeoPoint(37.5665, 126.9780),
                    visitDate = monday,
                    mode = RouteCurationMode.NEIGHBORHOOD,
                    stopCount = 2,
                    maxRadiusKm = 5.0,
                    visitMinutesPerStop = 45,
                ),
            )

        assertEquals(RoutePlanResult.InsufficientCandidates(requested = 2, available = 0, closedCount = 1), result)
    }

    private fun catalogueRow(
        id: String = "exhibition-1",
        hours: String?,
        hoursWeekly: String?,
    ): Exhibition {
        val hoursField = hours?.let { "\"hours\": ${Json.encodeToString(String.serializer(), it)}," } ?: ""
        val hoursWeeklyField = hoursWeekly?.let { "\"hours_weekly\": $it," } ?: ""
        val row =
            """
            {
                "id": "$id",
                "name_ko": "전시",
                "name_en": "Exhibition",
                "venue_name_ko": "갤러리",
                "venue_name_en": "Gallery",
                "city_ko": "서울",
                "city_en": "Seoul",
                "region_ko": "종로구",
                "region_en": "Jongno-gu",
                "opening_date": "2026-08-01",
                "closing_date": "2026-09-15",
                "is_featured": false,
                $hoursField
                $hoursWeeklyField
                "content_checksum_sha256": null
            }
            """.trimIndent()
        return assertNotNull(catalogueJson.decodeFromString(ExhibitionDto.serializer(), row).toDomain())
    }

    private companion object {
        const val TUESDAY_TO_SUNDAY_WEEK =
            "{\"MONDAY\":null," +
                "\"TUESDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"WEDNESDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"THURSDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"FRIDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"SATURDAY\":{\"opens\":\"11:00\",\"closes\":\"19:30\"}," +
                "\"SUNDAY\":{\"opens\":\"11:00\",\"closes\":\"18:00\"}}"

        const val OPEN_DAILY_WEEK =
            "{\"MONDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"TUESDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"WEDNESDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"THURSDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"FRIDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"SATURDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}," +
                "\"SUNDAY\":{\"opens\":\"10:00\",\"closes\":\"18:00\"}}"

        const val ALL_CLOSED_WEEK =
            "{\"MONDAY\":null,\"TUESDAY\":null,\"WEDNESDAY\":null,\"THURSDAY\":null," +
                "\"FRIDAY\":null,\"SATURDAY\":null,\"SUNDAY\":null}"
    }
}
