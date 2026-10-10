package com.gallr.shared.hours

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * Reads a venue's free-text hours into a weekly schedule.
 *
 * Total and deterministic: any input yields a value and never throws. Grammar is documented in
 * `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md`: time ranges in 12- or
 * 24-hour form, English or Korean day names, ranges and lists, weekday/weekend words, `Closed <days>`
 * and `휴관일: <days>` statements, with parenthetical notes, holiday phrases and dated one-off closures
 * ignored. A group that lists more than one time range on the same days is read as the span from the
 * first opening to the last closing and marked partial, so it can never claim a closure.
 */
fun parseOpeningHours(text: String?): WeeklyOpeningHours {
    val source = text?.trim().orEmpty()
    if (source.isEmpty()) return WeeklyOpeningHours.UNKNOWN
    return try {
        OpeningHoursReader(source).read()
    } catch (_: IllegalArgumentException) {
        WeeklyOpeningHours.UNKNOWN
    } catch (_: IllegalStateException) {
        WeeklyOpeningHours.UNKNOWN
    }
}

private class OpeningHoursReader(
    source: String,
) {
    private val closedDays = mutableSetOf<DayOfWeek>()
    private val byDay = mutableMapOf<DayOfWeek, DailyOpening>()
    private val daylessTimes = mutableListOf<DailyOpening>()
    private var unreadable = false
    private val body: String =
        source
            .normalized()
            .withoutDatedLines()
            .withoutParentheticals()
            .withoutClosedClauses()

    fun read(): WeeklyOpeningHours {
        var pending: DailyOpening? = null
        body.split('\n').flatMap(::splitDayGroups).forEach { rawSegment ->
            val segment = rawSegment.trim().trim(',', '.', ':', ';', ' ')
            if (segment.isEmpty()) return@forEach
            val timeMatches = TIME_RANGE.findAll(segment).toList()
            val times = timeMatches.map(::readTimeRange)
            if (times.any { it == null }) {
                unreadable = true
                return@forEach
            }
            val time = times.filterNotNull().spanOrNull()
            if (timeMatches.size > 1) unreadable = true
            val days = readDays(segment.withoutRanges(timeMatches))
            when {
                time != null && days.isNotEmpty() -> {
                    pending?.let(daylessTimes::add)
                    pending = null
                    days.forEach { byDay[it] = time }
                }

                time != null -> {
                    pending?.let(daylessTimes::add)
                    pending = time
                }

                days.isNotEmpty() -> {
                    val paired = pending
                    if (paired == null) {
                        unreadable = true
                    } else {
                        days.forEach { byDay[it] = paired }
                        pending = null
                    }
                }

                else -> {
                    unreadable = true
                }
            }
        }
        pending?.let(daylessTimes::add)

        var completeness = if (unreadable) OpeningHoursCompleteness.PARTIAL else OpeningHoursCompleteness.COMPLETE
        if (byDay.isEmpty()) {
            val dayless = daylessTimes.singleOrNull() ?: return WeeklyOpeningHours.UNKNOWN
            DayOfWeek.entries.forEach { byDay[it] = dayless }
            if (closedDays.isEmpty()) completeness = OpeningHoursCompleteness.PARTIAL
        } else if (daylessTimes.isNotEmpty()) {
            completeness = OpeningHoursCompleteness.PARTIAL
        }
        closedDays.forEach(byDay::remove)
        if (byDay.isEmpty()) return WeeklyOpeningHours.UNKNOWN
        return WeeklyOpeningHours(byDay.toMap(), completeness)
    }

    private fun String.normalized(): String =
        lowercase()
            .replace(DASH_VARIANTS, "-")
            .replace(TIME_TO, "$1 - $2")
            .replace(SEGMENT_SEPARATORS, "\n")
            .replace(HOLIDAY_PHRASES, " ")
            .replace(EVERY_DAY, " $ALL_DAYS_TOKEN ")
            .replace(WEEKDAYS_WORD, " 월-금 ")
            .replace(WEEKEND_WORD, " 토-일 ")

    private fun String.withoutDatedLines(): String =
        lineSequence()
            .filterNot { DATED_NOTE.containsMatchIn(it) }
            .joinToString("\n")

    /** Parenthetical notes are ignored, except that a closed-day marker inside one still applies. */
    private fun String.withoutParentheticals(): String =
        replace(PARENTHETICAL) { match ->
            val note = match.groupValues[1]
            if (CLOSED_MARKER.containsMatchIn(note)) closedDays += readDays(note)
            " "
        }

    private fun String.withoutClosedClauses(): String =
        replace(ENGLISH_CLOSED_CLAUSE) { match ->
            closedDays += readDays(match.groupValues[1])
            " "
        }.replace(KOREAN_CLOSED_CLAUSE) { match ->
            closedDays += readDays(match.groupValues[1])
            " "
        }.replace(KOREAN_CLOSED_DAYS_AFTER) { match ->
            closedDays += readDays(match.groupValues[1])
            " "
        }.replace(OTHERWISE_CLOSED, " ")
            .replace(BARE_CLOSED_WORD, " ")

    /**
     * A comma starts a new group only when hours precede it and a day follows it, so `Mon-Fri 10-18, Sat 11-17`
     * reads as two groups while `Mon, Wed, Fri 10-18` and `10:00-12:00, 13:00-18:00` each stay one.
     */
    private fun splitDayGroups(line: String): List<String> {
        val groups = mutableListOf<String>()
        var start = 0
        line.forEachIndexed { index, char ->
            if (char != ',') return@forEachIndexed
            val left = line.substring(start, index)
            val right = line.substring(index + 1).trimStart()
            if (TIME_RANGE.containsMatchIn(left) && DAY_GROUP_START.containsMatchIn(right)) {
                groups += left
                start = index + 1
            }
        }
        groups += line.substring(start)
        return groups
    }

    private fun String.withoutRanges(ranges: List<MatchResult>): String =
        ranges.foldRight(this) { match, text -> text.removeRange(match.range) }

    /** Several ranges on the same days read as one span from the first opening to the last closing. */
    private fun List<DailyOpening>.spanOrNull(): DailyOpening? {
        if (isEmpty()) return null
        return DailyOpening(opens = minOf { it.opens }, closes = maxOf { it.closes })
    }

    private fun readTimeRange(match: MatchResult): DailyOpening? {
        val startHour = match.groupValues[1].toInt()
        val startMinute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val startMeridiem = match.groupValues[3].toMeridiem()
        val endHour = match.groupValues[4].toInt()
        val endMinute = match.groupValues[5].ifEmpty { "0" }.toInt()
        val endMeridiem = match.groupValues[6].toMeridiem()
        if (startMinute !in 0..59 || endMinute !in 0..59) return null

        val (opens, closes) =
            when {
                startMeridiem == null && endMeridiem == null -> {
                    val start = hour24(startHour) ?: return null
                    val end = hour24(endHour) ?: return null
                    start to end
                }

                startMeridiem != null && endMeridiem != null -> {
                    val start = hour12(startHour, startMeridiem) ?: return null
                    val end = hour12(endHour, endMeridiem) ?: return null
                    start to end
                }

                startMeridiem == null -> {
                    val end = hour12(endHour, endMeridiem!!) ?: return null
                    val start = inferMissingMeridiem(startHour, endMeridiem) { it < end } ?: return null
                    start to end
                }

                else -> {
                    val start = hour12(startHour, startMeridiem) ?: return null
                    val end = inferMissingMeridiem(endHour, startMeridiem) { it > start } ?: return null
                    start to end
                }
            }
        val opensAt = opens * MINUTES_PER_HOUR + startMinute
        val closesAt = closes * MINUTES_PER_HOUR + endMinute
        if (closesAt <= opensAt) return null
        return DailyOpening(opensAt.toLocalTime(), closesAt.toLocalTime())
    }

    /** A bare hour next to a 12-hour time takes the same meridiem unless only the other one is valid. */
    private fun inferMissingMeridiem(
        hour: Int,
        sibling: Meridiem,
        valid: (Int) -> Boolean,
    ): Int? {
        if (hour > 12) return hour24(hour)?.takeIf(valid)
        val same = hour12(hour, sibling)
        if (same != null && valid(same)) return same
        val other = hour12(hour, sibling.opposite())
        return other?.takeIf(valid)
    }

    private fun readDays(text: String): List<DayOfWeek> {
        if (text.contains(ALL_DAYS_TOKEN)) return DayOfWeek.entries.toList()
        val tokens = DAY_TOKEN.findAll(text).filter { text.isStandaloneDay(it) }.toList()
        val days = mutableListOf<DayOfWeek>()
        var index = 0
        while (index < tokens.size) {
            val current = tokens[index]
            val day = current.value.toDayOfWeek()
            if (day == null) {
                index += 1
                continue
            }
            val next = tokens.getOrNull(index + 1)
            val between = next?.let { text.substring(current.range.last + 1, it.range.first).trim() }
            val nextDay = next?.value?.toDayOfWeek()
            if (between == "-" && nextDay != null) {
                days += daysFrom(day, nextDay)
                index += 2
            } else {
                days += day
                index += 1
            }
        }
        return days.distinct()
    }

    /**
     * A single Korean day character counts only on its own: `일` inside `평일`, `휴관일` or `일부` is not Sunday.
     * Neighbouring day characters are fine (`토일`), and `월요일` is matched whole.
     */
    private fun String.isStandaloneDay(match: MatchResult): Boolean {
        if (match.value.length != 1) return true
        val before = getOrNull(match.range.first - 1)
        val after = getOrNull(match.range.last + 1)
        return !before.isNonDayHangul() && !after.isNonDayHangul()
    }

    private fun Char?.isNonDayHangul(): Boolean = this != null && this in HANGUL_SYLLABLES && this !in KOREAN_DAY_CHARS

    private fun daysFrom(
        from: DayOfWeek,
        to: DayOfWeek,
    ): List<DayOfWeek> {
        val all = DayOfWeek.entries
        return if (from.ordinal <= to.ordinal) {
            all.subList(from.ordinal, to.ordinal + 1)
        } else {
            all.subList(from.ordinal, all.size) + all.subList(0, to.ordinal + 1)
        }
    }

    private fun String.toDayOfWeek(): DayOfWeek? =
        when {
            startsWith("mon") || startsWith("월") -> DayOfWeek.MONDAY
            startsWith("tue") || startsWith("화") -> DayOfWeek.TUESDAY
            startsWith("wed") || startsWith("수") -> DayOfWeek.WEDNESDAY
            startsWith("thu") || startsWith("목") -> DayOfWeek.THURSDAY
            startsWith("fri") || startsWith("금") -> DayOfWeek.FRIDAY
            startsWith("sat") || startsWith("토") -> DayOfWeek.SATURDAY
            startsWith("sun") || startsWith("일") -> DayOfWeek.SUNDAY
            else -> null
        }
}

private enum class Meridiem {
    AM,
    PM,
    ;

    fun opposite(): Meridiem = if (this == AM) PM else AM
}

private fun String.toMeridiem(): Meridiem? =
    when (replace(".", "")) {
        "am" -> Meridiem.AM
        "pm" -> Meridiem.PM
        else -> null
    }

private fun hour24(hour: Int): Int? = hour.takeIf { it in 0..23 }

private fun hour12(
    hour: Int,
    meridiem: Meridiem,
): Int? {
    if (hour !in 1..12) return null
    val base = hour % 12
    return if (meridiem == Meridiem.PM) base + 12 else base
}

private fun Int.toLocalTime(): LocalTime = LocalTime(this / MINUTES_PER_HOUR, this % MINUTES_PER_HOUR)

private const val MINUTES_PER_HOUR = 60
private const val ALL_DAYS_TOKEN = "alldays"
private const val KOREAN_DAY_CHARS = "월화수목금토일"
private val HANGUL_SYLLABLES = '\uAC00'..'\uD7A3'
private const val ENGLISH_DAY =
    "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)s?"
private const val ENGLISH_DAY_LIST = "(?:\\b$ENGLISH_DAY\\b\\.?\\s*(?:,|&|and|-|/)?\\s*)+"
private const val KOREAN_DAY_LIST = "(?:[월화수목금토일](?:요일)?\\s*(?:,|및|/|-)?\\s*)+"

/** A day list that stays on its line, so `휴관일: 월, 화` cannot swallow the next line's hours. */
private const val KOREAN_DAY_LIST_INLINE = "(?:[월화수목금토일](?:요일)?[ \\t]*(?:,|및|/|-)?[ \\t]*)+"
private const val CLOSED_WORDS = "휴관일|휴무일|정기휴일|정기휴무|휴관|휴무|쉽니다"
private val DASH_VARIANTS = Regex("[–—−~〜∼]")
private val TIME_TO = Regex("(\\d|am|pm)\\s+to\\s+(\\d)")
private val SEGMENT_SEPARATORS = Regex("[·•|;]")
private val HOLIDAY_PHRASES = Regex("(?:(?:,|and|&)\\s*)?(?:national|public)\\s+holidays?|공휴일")
private val EVERY_DAY = Regex("\\b(?:every\\s*day|everyday|daily)\\b|매일|연중무휴")
private val WEEKDAYS_WORD = Regex("\\bweekdays?\\b|평일")
private val WEEKEND_WORD = Regex("\\bweekends?\\b|주말")
private val DATED_NOTE = Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일")
private val PARENTHETICAL = Regex("[(（]([^)）\\n]*)[)）]?")
private val CLOSED_MARKER = Regex("closed|$CLOSED_WORDS")
private val ENGLISH_CLOSED_CLAUSE = Regex("closed(?:\\s+(?:on|every))?\\s*:?\\s*($ENGLISH_DAY_LIST)")
private val KOREAN_CLOSED_CLAUSE = Regex("($KOREAN_DAY_LIST)(?:은|는)?\\s*(?:$CLOSED_WORDS)")
private val KOREAN_CLOSED_DAYS_AFTER = Regex("(?:$CLOSED_WORDS)[ \\t]*[:：]?[ \\t]*($KOREAN_DAY_LIST_INLINE)")
private val OTHERWISE_CLOSED = Regex("(?:그\\s*외|이외|나머지)(?:\\s*요일)?(?:은|는|에는)?\\s*(?:$CLOSED_WORDS)")
private val BARE_CLOSED_WORD = Regex("closed(?:\\s+on)?|$CLOSED_WORDS")
private const val CLOCK = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"
private val TIME_RANGE = Regex("$CLOCK\\s*-\\s*$CLOCK")
private val DAY_TOKEN = Regex("\\b$ENGLISH_DAY\\b|[월화수목금토일](?:요일)?")
private val DAY_GROUP_START = Regex("^(?:$ENGLISH_DAY\\b|[월화수목금토일]|$ALL_DAYS_TOKEN)")
