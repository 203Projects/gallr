package com.gallr.shared.fixture

import com.gallr.shared.hours.DailyOpening
import com.gallr.shared.hours.OpeningHoursCompleteness
import com.gallr.shared.hours.WeeklyOpeningHours
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SATURDAY
import kotlinx.datetime.DayOfWeek.SUNDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.LocalTime

/**
 * Hand-checked weekly hours for every fixture exhibition with a non-blank `hours` field, written
 * from the raw listing text (2026-10-03 snapshot, 44 distinct listings over 68 exhibitions).
 */
internal object PublishedCatalogueHoursExpectations {
    // Declared before the table: object properties initialise in declaration order.
    private val MON_SUN = days(MONDAY, SUNDAY)
    private val MON_SAT = days(MONDAY, SATURDAY)
    private val TUE_SUN = days(TUESDAY, SUNDAY)
    private val TUE_SAT = days(TUESDAY, SATURDAY)

    val byExhibitionId: Map<String, WeeklyOpeningHours> =
        listOf(
            // "10:30am - 6:30pm\nTuesday - Saturday"
            ids("2056f64e-c745-4733-90d7-8775dfecb9fd") to complete(TUE_SAT to open(10, 30, 18, 30)),
            // "10:30am - 6:30pm\nTuesday - Sunday"
            ids("24eabadc-2fea-4103-83dc-a21b383623e7") to complete(TUE_SUN to open(10, 30, 18, 30)),
            // "10:30am - 6pm\nMonday - Saturday"
            ids("1e9d1465-fa6d-4ab1-9103-1dcaf74d9a4b") to complete(MON_SAT to open(10, 30, 18, 0)),
            // "10am - 6pm Monday - Saturday\n10am - 5pm Sunday and National holidays"
            ids("12109f7b-e29d-4342-ac6b-440760ab0130", "de03eac3-0fa3-4678-bdf1-128dc31782e4") to
                complete(MON_SAT to open(10, 0, 18, 0), listOf(SUNDAY) to open(10, 0, 17, 0)),
            // "10am - 6pm Monday - Sunday\n"
            ids("e17f7cb3-f10a-44bb-80b1-0a6010d3470a") to complete(MON_SUN to open(10, 0, 18, 0)),
            // "10am - 6pm Tuesday - Sunday \n(until 9pm on Wednesday, Saturday) "
            ids("db63bb9a-de4c-47ff-9331-d8165a92bca1") to complete(TUE_SUN to open(10, 0, 18, 0)),
            // "10am - 6pm \nTuesday - Sunday"
            ids("43eea0b5-62a8-4368-9147-48e7779ae8ef") to complete(TUE_SUN to open(10, 0, 18, 0)),
            // "10am - 6pm\nMonday - Saturday"
            ids("0530e977-8063-4bb9-9784-e1cfd6c48563", "88e0cd13-d4c7-4342-92f7-d112f1b7a35e") to
                complete(MON_SAT to open(10, 0, 18, 0)),
            // "10am - 6pm\nMonday - Sunday (Wed & Sat ~9pm)"
            ids("1537bb0b-140a-4947-baa3-2eb07414b6f6", "182f33ef7815f00a", "9fbc7780bf59b72b") to
                complete(MON_SUN to open(10, 0, 18, 0)),
            // "10am - 6pm\nTuesday - Saturday"
            ids(
                "0cde1933-7349-4eca-9e4c-9fc1302e5749",
                "4057e361-b7b7-4676-a330-1531d75c2adc",
                "5f210ccf-a241-449e-8b8c-0f4c4f9d865f",
                "6fa7b4d3-8ee8-4ebd-8925-8ddca5633778",
                "76c3989a-73e3-4521-a5ce-3de0cee4dce4",
                "bada890a-8b4b-4003-a2a8-72f88f39966e",
            ) to complete(TUE_SAT to open(10, 0, 18, 0)),
            // "10am - 6pm\nTuesday - Sunday"
            ids(
                "36b1bb46-37e7-4e46-a2ed-c45fb051520f",
                "6c149e0c-98c7-4849-b91b-555e24ef643f",
                "75aa1493-9ad8-4d65-b308-f68ceb88119f",
                "7d488ca7c2510505",
                "bcb2bf9a1451c255",
            ) to complete(TUE_SUN to open(10, 0, 18, 0)),
            // "10am - 6pm\nTuesday - Sunday\n"
            ids("561b3846-e199-4c6f-b5e6-3b4787c2b1ef") to complete(TUE_SUN to open(10, 0, 18, 0)),
            // "10am - 7pm\nTuesday - Sunday"
            ids("2edb3dd50e7f1662") to complete(TUE_SUN to open(10, 0, 19, 0)),
            // "10am - 8pm Tuesday - Friday\n10am - 7pm Saturday - Sunday"
            ids("8106fe10c42a59f9") to
                complete(days(TUESDAY, FRIDAY) to open(10, 0, 20, 0), days(SATURDAY, SUNDAY) to open(10, 0, 19, 0)),
            // "10am - 8pm Tuesday - Thursday\n10am - 9pm Friday\n10am - 7pm Saturday - Sunday"
            ids("34a6724f37a8c90b") to
                complete(
                    days(TUESDAY, THURSDAY) to open(10, 0, 20, 0),
                    listOf(FRIDAY) to open(10, 0, 21, 0),
                    days(SATURDAY, SUNDAY) to open(10, 0, 19, 0),
                ),
            // "11am - 6:30pm\nMonday - Saturday"
            ids("3f32b431-4233-4701-a098-a720197b4de9") to complete(MON_SAT to open(11, 0, 18, 30)),
            // "11am - 6pm Tuesday - Sunday"
            ids("127630bb-7a83-4fd2-95f0-e4ca3bdad48b") to complete(TUE_SUN to open(11, 0, 18, 0)),
            // "11am - 6pm \nTuesday - Saturday"
            ids("55a716ae-e6e5-46a5-8202-df57133eb35e") to complete(TUE_SAT to open(11, 0, 18, 0)),
            // "11am - 6pm\nTuesday - Saturday"
            ids(
                "62becbcd-2a55-4c37-9699-2243092bd76c",
                "80322db2-2a7b-48ff-bc21-b88bfbce27e2",
                "a6afb7ce-9cda-48fa-9a80-0e8561dd81b3",
                "c930c91a-8b81-4dd9-91fc-6d191c088a18",
                "d3497edf-b3df-48ab-8d4f-c2d6ef467a81",
            ) to complete(TUE_SAT to open(11, 0, 18, 0)),
            // "11am - 6pm\nTuesday - Sunday"
            ids("06298c4f-51eb-4274-9b6e-53bb9b299dea") to complete(TUE_SUN to open(11, 0, 18, 0)),
            // "11am - 7pm Tuesday - Friday\n10am - 6pm Saturday - Sunday\nClosed on Mondays and Public Holidays"
            ids("c81c8e08-abd4-4290-8cb1-cde6aeaf50a9") to
                complete(days(TUESDAY, FRIDAY) to open(11, 0, 19, 0), days(SATURDAY, SUNDAY) to open(10, 0, 18, 0)),
            // "11am - 7pm\nTuesday - Saturday"
            ids("41220b69-344f-42ee-9348-6223cddaf508") to complete(TUE_SAT to open(11, 0, 19, 0)),
            // "11am - 7pm\nTuesday - Sunday " and "11am - 7pm\nTuesday - Sunday"
            ids(
                "42448fe0-01b7-466e-97da-8cf9f827a8b6",
                "d9eea010-2d62-4a60-a0cb-ffe2ea001103",
                "a7d50f6b-d4c7-43f1-99b1-63026e305473",
            ) to complete(TUE_SUN to open(11, 0, 19, 0)),
            // "12pm - 6pm\nTuesday - Saturday " and "12pm - 6pm\nTuesday - Saturday"
            ids(
                "ed6630fa-b356-4035-96da-5f36c6c4974c",
                "95fde29a-24bf-451c-959d-29e6075b45d1",
                "d71444f1-5506-4bcd-9b50-01b66dd5ba40",
            ) to complete(TUE_SAT to open(12, 0, 18, 0)),
            // "12pm - 6pm\nTuesday - Sunday"
            ids(
                "4e77f612-e610-4992-9a6b-5e8ab1bceafd",
                "5e5c9a5b-c83a-42f4-a61c-f103be982015",
                "c2e47077-e7f2-43e9-b447-3d3378763b44",
            ) to complete(TUE_SUN to open(12, 0, 18, 0)),
            // "12pm - 6pm\nWednesday - Saturday"
            ids("04db8ff9-e44b-427b-9719-61389dac2360") to complete(days(WEDNESDAY, SATURDAY) to open(12, 0, 18, 0)),
            // "12pm - 6pm\nWednesday - Sunday"
            ids("4aebe7e4-e5e8-45bd-b7b6-026b4d27846d", "60e20410-5405-4c2d-a42c-0d3a12b714e4") to
                complete(days(WEDNESDAY, SUNDAY) to open(12, 0, 18, 0)),
            // "12pm - 7pm\n" (times only)
            ids("ad149377-67a8-474b-9f35-48855007cde3") to partialEveryDay(open(12, 0, 19, 0)),
            // "12pm - 7pm\nTuesday - Saturday"
            ids("876ab6bb-11c1-4566-b9fe-e27a9c1b34c7") to complete(TUE_SAT to open(12, 0, 19, 0)),
            // "1pm - 6pm\nWednesday - Sunday"
            ids("556d1db7-f7c2-428e-966c-2eda2ce72a08", "cdd60d1e-379c-4769-a89e-a8d2eef8aa53") to
                complete(days(WEDNESDAY, SUNDAY) to open(13, 0, 18, 0)),
            // "1pm - 7pm\nMonday - Sunday" and "1pm - 7pm\nMonday - Sunday\nClsoed on 9/25 Friday "
            ids("c323b9e6-1f1e-4b1b-97f3-b8f5046366b2", "276bacab-40bf-4d0c-b182-c0bf8fd15af9") to
                complete(MON_SUN to open(13, 0, 19, 0)),
            // "1pm - 7pm\nTuesday - Friday"
            ids("dc7b4a41-9153-448c-a73f-769336c47460") to complete(days(TUESDAY, FRIDAY) to open(13, 0, 19, 0)),
            // "1pm - 7pm\nTuesday - Saturday"
            ids("24f1b670-f3fd-4548-a04d-6790b3c677b9", "4d580703-644b-4b89-b553-820e8b8cd91b") to
                complete(TUE_SAT to open(13, 0, 19, 0)),
            // "1pm - 7pm\nWednesday - Sunday"
            ids("5b13dc05-76ea-4b31-8a7f-f265fba32ba9") to complete(days(WEDNESDAY, SUNDAY) to open(13, 0, 19, 0)),
            // "1pm - 8pm\nTuesday - Sunday"
            ids("53180852-1cf0-4c79-b23a-3fb08d5d6be3") to complete(TUE_SUN to open(13, 0, 20, 0)),
            // "2pm - 5:30pm\nMonday - Saturday"
            ids("797772d8-e4e5-4147-9ee2-4c7afc1a882d") to complete(MON_SAT to open(14, 0, 17, 30)),
            // "2pm - 7pm\nWednesday - Saturday"
            ids("5ae0f912-ad5b-450e-8b24-585ff854e778") to complete(days(WEDNESDAY, SATURDAY) to open(14, 0, 19, 0)),
            // "2pm - 8pm \nWednesday - Sunday"
            ids("e4962bc9-a581-47f4-b08d-e738698f4294") to complete(days(WEDNESDAY, SUNDAY) to open(14, 0, 20, 0)),
            // "9am - 6pm\nMonday - Friday"
            ids("f8a25c0b-0110-4e90-8de3-05fab8e77d2e") to complete(days(MONDAY, FRIDAY) to open(9, 0, 18, 0)),
            // "Tue, Thu, Fri 10:00–18:00 · Wed, Sat 10:00–21:00 · Closed Monday"
            ids("c443add9-e4c1-4c43-aa59-172d3d401a9c") to
                complete(
                    listOf(TUESDAY, THURSDAY, FRIDAY) to open(10, 0, 18, 0),
                    listOf(WEDNESDAY, SATURDAY) to open(10, 0, 21, 0),
                ),
            // "Tuesday–Sunday 10:00–18:00 · Closed Monday"
            ids("886ea792-cb45-4890-851d-5bfdcb19dd09") to complete(TUE_SUN to open(10, 0, 18, 0)),
        ).flatMap { (ids, hours) -> ids.map { it to hours } }
            .toMap()

    private fun ids(vararg values: String): List<String> = values.toList()

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
}
