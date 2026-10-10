package com.gallr.shared.hours

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Golden cases from `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md`. */
class OpeningHoursParserTest {
    @Test
    fun `case 1 twelve hour range with day range on the next line`() {
        assertEquals(
            complete(tueSat to open(10, 0, 18, 0)),
            parseOpeningHours("10am - 6pm\nTuesday - Saturday"),
        )
    }

    @Test
    fun `case 2 trailing whitespace is ignored`() {
        assertEquals(
            complete(tueSun to open(11, 0, 18, 0)),
            parseOpeningHours("11am - 6pm\nTuesday - Sunday "),
        )
    }

    @Test
    fun `case 3 parenthetical evening extension is ignored`() {
        assertEquals(
            complete(monSun to open(10, 0, 18, 0)),
            parseOpeningHours("10am - 6pm\nMonday - Sunday (Wed & Sat ~9pm)"),
        )
    }

    @Test
    fun `case 4 two weekday groups and a holiday phrase`() {
        assertEquals(
            complete(monSat to open(10, 0, 18, 0), listOf(DayOfWeek.SUNDAY) to open(10, 0, 17, 0)),
            parseOpeningHours("10am - 6pm Monday - Saturday\n10am - 5pm Sunday and National holidays"),
        )
    }

    @Test
    fun `case 5 three weekday groups with different closing times`() {
        assertEquals(
            complete(
                days(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY) to open(10, 0, 20, 0),
                listOf(DayOfWeek.FRIDAY) to open(10, 0, 21, 0),
                days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) to open(10, 0, 19, 0),
            ),
            parseOpeningHours("10am - 8pm Tuesday - Thursday\n10am - 9pm Friday\n10am - 7pm Saturday - Sunday"),
        )
    }

    @Test
    fun `case 6 abbreviated day lists with middle dots and a closed day`() {
        assertEquals(
            complete(
                listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) to open(10, 0, 18, 0),
                listOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY) to open(10, 0, 21, 0),
            ),
            parseOpeningHours("Tue, Thu, Fri 10:00–18:00 · Wed, Sat 10:00–21:00 · Closed Monday"),
        )
    }

    @Test
    fun `case 7 en dash day range with twenty four hour times and a closed day`() {
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("Tuesday–Sunday 10:00–18:00 · Closed Monday"),
        )
    }

    @Test
    fun `case 8 and 9 times without days apply to every day as partial`() {
        val expected = partialEveryDay(open(12, 0, 19, 0))

        assertEquals(expected, parseOpeningHours("12pm - 7pm"))
        assertEquals(expected, parseOpeningHours("12pm - 7pm\n"))
    }

    @Test
    fun `case 10 half hour times`() {
        assertEquals(
            complete(tueSun to open(10, 30, 18, 30)),
            parseOpeningHours("10:30am - 6:30pm\nTuesday - Sunday"),
        )
    }

    @Test
    fun `case 11 dated one off closure notes are ignored`() {
        assertEquals(
            complete(monSun to open(13, 0, 19, 0)),
            parseOpeningHours("1pm - 7pm\nMonday - Sunday\nClsoed on 9/25 Friday "),
        )
    }

    @Test
    fun `case 12 same line range with a parenthetical note on the next line`() {
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("10am - 6pm Tuesday - Sunday \n(until 9pm on Wednesday, Saturday) "),
        )
    }

    @Test
    fun `case 13 korean day range with a korean closed day`() {
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("화-일 10:00-18:00, 월요일 휴관"),
        )
    }

    @Test
    fun `case 14 korean closed marker inside parentheses still applies`() {
        assertEquals(
            complete(tueSun to open(11, 0, 19, 0)),
            parseOpeningHours("11:00~19:00 (월 휴무)"),
        )
    }

    @Test
    fun `case 15 blank and null are unknown`() {
        assertEquals(WeeklyOpeningHours.UNKNOWN, parseOpeningHours(null))
        assertEquals(WeeklyOpeningHours.UNKNOWN, parseOpeningHours(""))
        assertEquals(WeeklyOpeningHours.UNKNOWN, parseOpeningHours("  \n "))
    }

    @Test
    fun `case 16 prose without times is unknown`() {
        assertEquals(WeeklyOpeningHours.UNKNOWN, parseOpeningHours("By appointment only"))
    }

    @Test
    fun `case 17 overnight ranges are not supported`() {
        assertEquals(WeeklyOpeningHours.UNKNOWN, parseOpeningHours("10pm - 2am\nFriday - Saturday"))
    }

    @Test
    fun `case 18 weekday only venue is closed at the weekend`() {
        val hours = parseOpeningHours("9am - 6pm\nMonday - Friday")

        assertEquals(complete(days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) to open(9, 0, 18, 0)), hours)
        assertTrue(hours.isKnownClosedOn(LocalDate(2026, 10, 3)))
        assertFalse(hours.isKnownClosedOn(LocalDate(2026, 10, 2)))
        assertEquals(open(9, 0, 18, 0), hours.openingOn(LocalDate(2026, 10, 2)))
    }

    @Test
    fun `case 19 day ranges wrap past sunday`() {
        assertEquals(
            complete(
                listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY) to open(12, 0, 18, 0),
            ),
            parseOpeningHours("Friday - Monday 12pm - 6pm"),
        )
    }

    @Test
    fun `case 20 plural closed day with public holidays`() {
        assertEquals(
            complete(
                days(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY) to open(11, 0, 19, 0),
                days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) to open(10, 0, 18, 0),
            ),
            parseOpeningHours(
                "11am - 7pm Tuesday - Friday\n10am - 6pm Saturday - Sunday\nClosed on Mondays and Public Holidays",
            ),
        )
    }

    @Test
    fun `case 21 half hour closing time`() {
        assertEquals(
            complete(monSat to open(14, 0, 17, 30)),
            parseOpeningHours("2pm - 5:30pm\nMonday - Saturday"),
        )
    }

    @Test
    fun `case 22 weekdays word reads as monday to friday, not sunday`() {
        assertEquals(
            complete(days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) to open(10, 0, 18, 0)),
            parseOpeningHours("평일 10:00-18:00"),
        )
    }

    @Test
    fun `case 23 weekday and weekend groups split on the comma`() {
        assertEquals(
            complete(
                days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) to open(10, 0, 18, 0),
                days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) to open(11, 0, 17, 0),
            ),
            parseOpeningHours("평일 10:00-18:00, 주말 11:00-17:00"),
        )
        assertEquals(
            complete(
                days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) to open(10, 0, 18, 0),
                days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) to open(11, 0, 17, 0),
            ),
            parseOpeningHours("Weekdays 10am-6pm, weekends 11am-5pm"),
        )
    }

    @Test
    fun `case 24 a second group after the comma keeps its own hours`() {
        assertEquals(
            complete(
                days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) to open(10, 0, 18, 0),
                listOf(DayOfWeek.SATURDAY) to open(11, 0, 17, 0),
            ),
            parseOpeningHours("Mon-Fri 10:00-18:00, Sat 11:00-17:00"),
        )
    }

    @Test
    fun `case 25 two time ranges on the same days read as their span and stay partial`() {
        val hours = parseOpeningHours("Tue-Sat 10:00-12:00, 13:00-18:00")

        assertEquals(OpeningHoursCompleteness.PARTIAL, hours.completeness)
        assertEquals(tueSat.associateWith { open(10, 0, 18, 0) }, hours.byDay)
        assertFalse(hours.isKnownClosedOn(LocalDate(2026, 10, 5)), "a partial reading never claims Monday closed")
    }

    @Test
    fun `case 26 closed days listed after the closed word`() {
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("화-일 10:00-18:00 휴관일: 월요일"),
        )
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("화-일 10:00-18:00\n휴관일 월요일"),
        )
    }

    @Test
    fun `case 27 a closed day list stays on its line`() {
        assertEquals(
            complete(days(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY) to open(10, 0, 18, 0)),
            parseOpeningHours("휴관일: 월, 화\n수-일 10:00-18:00"),
        )
    }

    @Test
    fun `case 28 the day character inside other words is not a day`() {
        assertEquals(
            complete(tueSun to open(10, 0, 18, 0)),
            parseOpeningHours("화-일 10:00-18:00, 매주 월요일 휴관일"),
        )
        assertEquals(
            complete(tueSat to open(11, 0, 18, 0)),
            parseOpeningHours("화-토 11:00-18:00 그 외 휴관"),
        )
    }

    @Test
    fun `partial readings never claim a closed day`() {
        val hours = parseOpeningHours("12pm - 7pm")

        assertFalse(hours.isVerified)
        DayOfWeek.entries.forEach { day ->
            assertFalse(hours.isKnownClosedOn(LocalDate(2026, 10, 5).plusDays(day.ordinal)))
        }
    }

    @Test
    fun `malformed text never throws`() {
        val malformed =
            listOf(
                "((((",
                "- - -",
                "25:00 - 26:00",
                "10am - 6pm (Tuesday",
                "🏛️ 10am 6pm",
                "Monday - ",
                " - Sunday",
                "99am - 99pm\nMonday",
                "10am - 6pm\n" + "Tuesday - Saturday\n".repeat(400),
                "x".repeat(10_000),
            )

        malformed.forEach { text ->
            val hours = parseOpeningHours(text)
            assertTrue(hours.byDay.values.all { it.closes > it.opens }, text.take(40))
        }
    }

    private fun LocalDate.plusDays(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)

    private fun open(
        fromHour: Int,
        fromMinute: Int,
        toHour: Int,
        toMinute: Int,
    ) = DailyOpening(LocalTime(fromHour, fromMinute), LocalTime(toHour, toMinute))

    private fun days(
        from: DayOfWeek,
        to: DayOfWeek,
    ): List<DayOfWeek> = DayOfWeek.entries.filter { it.ordinal in from.ordinal..to.ordinal }

    private fun complete(vararg groups: Pair<List<DayOfWeek>, DailyOpening>) =
        WeeklyOpeningHours(
            byDay = groups.flatMap { (days, opening) -> days.map { it to opening } }.toMap(),
            completeness = OpeningHoursCompleteness.COMPLETE,
        )

    private fun partialEveryDay(opening: DailyOpening) =
        WeeklyOpeningHours(
            byDay = DayOfWeek.entries.associateWith { opening },
            completeness = OpeningHoursCompleteness.PARTIAL,
        )

    private val monSun = days(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
    private val monSat = days(DayOfWeek.MONDAY, DayOfWeek.SATURDAY)
    private val tueSun = days(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY)
    private val tueSat = days(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)
}
