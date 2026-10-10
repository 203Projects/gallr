package com.gallr.shared.hours

import com.gallr.shared.fixture.DiscoveryFixture
import com.gallr.shared.fixture.PublishedCatalogueHoursExpectations
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Builds the opening-hours parity file shared by the app's parser and the web route page's port
 * (spec 089, contracts/parity-file.md). The checked-in copy lives at [CHECKED_IN_PATH]; the
 * generator test writes a fresh copy under `build/` so a normal test run never rewrites it.
 */
internal object OpeningHoursParityFile {
    const val CHECKED_IN_PATH = "../specs/089-personal-routes/contracts/opening-hours-parity.json"

    /** Inputs of the 21 golden cases in specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md. */
    val goldenInputs: List<Pair<String, String?>> =
        listOf(
            "golden-01" to "10am - 6pm\nTuesday - Saturday",
            "golden-02" to "11am - 6pm\nTuesday - Sunday ",
            "golden-03" to "10am - 6pm\nMonday - Sunday (Wed & Sat ~9pm)",
            "golden-04" to "10am - 6pm Monday - Saturday\n10am - 5pm Sunday and National holidays",
            "golden-05" to "10am - 8pm Tuesday - Thursday\n10am - 9pm Friday\n10am - 7pm Saturday - Sunday",
            "golden-06" to "Tue, Thu, Fri 10:00–18:00 · Wed, Sat 10:00–21:00 · Closed Monday",
            "golden-07" to "Tuesday–Sunday 10:00–18:00 · Closed Monday",
            "golden-08" to "12pm - 7pm",
            "golden-09" to "12pm - 7pm\n",
            "golden-10" to "10:30am - 6:30pm\nTuesday - Sunday",
            "golden-11" to "1pm - 7pm\nMonday - Sunday\nClsoed on 9/25 Friday ",
            "golden-12" to "10am - 6pm Tuesday - Sunday \n(until 9pm on Wednesday, Saturday) ",
            "golden-13" to "화-일 10:00-18:00, 월요일 휴관",
            "golden-14" to "11:00~19:00 (월 휴무)",
            "golden-15" to "",
            "golden-15-null" to null,
            "golden-16" to "By appointment only",
            "golden-17" to "10pm - 2am\nFriday - Saturday",
            "golden-18" to "9am - 6pm\nMonday - Friday",
            "golden-19" to "Friday - Monday 12pm - 6pm",
            "golden-20" to
                "11am - 7pm Tuesday - Friday\n10am - 6pm Saturday - Sunday\nClosed on Mondays and Public Holidays",
            "golden-21" to "2pm - 5:30pm\nMonday - Saturday",
            // Cases 22–30 were added with the weekday-word, comma-group, multi-range and closed-day fixes.
            "golden-22" to "평일 10:00-18:00",
            "golden-23" to "평일 10:00-18:00, 주말 11:00-17:00",
            "golden-24" to "Weekdays 10am-6pm, weekends 11am-5pm",
            "golden-25" to "Mon-Fri 10:00-18:00, Sat 11:00-17:00",
            "golden-26" to "Tue-Sat 10:00-12:00, 13:00-18:00",
            "golden-27" to "화-일 10:00-18:00 휴관일: 월요일",
            "golden-28" to "휴관일: 월, 화\n수-일 10:00-18:00",
            "golden-29" to "화-일 10:00-18:00, 매주 월요일 휴관일",
            "golden-30" to "화-토 11:00-18:00 그 외 휴관",
        )

    /** Status line wording per route stop verdict (design review DR-D8); `{name}` marks substitutions. */
    val statusLabels: Map<String, Pair<String, String>> =
        linkedMapOf(
            "ClosedOnPlannedDay" to ("! 휴관일" to "! CLOSED THAT DAY"),
            "ArrivesAfterClose" to ("! {arrival} 도착 · {closes} 마감" to "! ARRIVES {arrival} · CLOSES {closes}"),
            "VisitCutShort" to ("! 관람 {minutes}분밖에 없어요" to "! ONLY {minutes} MIN TO VISIT"),
            "NotYetOpen" to ("! {date} 개막" to "! OPENS {date}"),
            "Ended" to ("! 종료된 전시" to "! EXHIBITION ENDED"),
            "Unavailable" to ("! 더 이상 볼 수 없는 전시" to "! NO LONGER LISTED"),
            "HoursUnknown" to ("운영 시간 미확인" to "HOURS UNCONFIRMED"),
        )

    /**
     * Golden answers come from the parser, which the grammar-contract tests already pin; catalogue answers
     * come from the hand-checked expectation table.
     */
    fun build(): JsonObject {
        val catalogue =
            DiscoveryFixture.exhibitions
                .filter { !it.hours.isNullOrBlank() }
                .sortedBy { it.id }
                .map { exhibition ->
                    Triple(
                        "catalogue-${exhibition.id}",
                        exhibition.hours,
                        PublishedCatalogueHoursExpectations.byExhibitionId.getValue(exhibition.id),
                    )
                }
        val golden = goldenInputs.map { (id, input) -> Triple(id, input, parseOpeningHours(input)) }
        return buildJsonObject {
            put("version", 1)
            put(
                "hours",
                buildJsonArray {
                    (golden + catalogue).forEach { (id, input, expected) -> add(entry(id, input, expected)) }
                },
            )
            put(
                "statusLabels",
                buildJsonObject {
                    statusLabels.forEach { (verdict, labels) ->
                        put(
                            verdict,
                            buildJsonObject {
                                put("ko", labels.first)
                                put("en", labels.second)
                            },
                        )
                    }
                },
            )
        }
    }

    fun entry(
        id: String,
        input: String?,
        hours: WeeklyOpeningHours,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("input", input?.let(::JsonPrimitive) ?: JsonNull)
            put("completeness", hours.completeness.name)
            put("week", week(hours))
        }

    fun week(hours: WeeklyOpeningHours): JsonObject =
        buildJsonObject {
            DayOfWeek.entries.forEach { day ->
                val opening = hours.byDay[day]
                val interval =
                    opening?.let {
                        JsonArray(listOf(JsonPrimitive(it.opens.toString()), JsonPrimitive(it.closes.toString())))
                    }
                put(day.name.take(3), interval ?: JsonNull)
            }
        }

    fun checkedIn(): File = File(CHECKED_IN_PATH)
}
