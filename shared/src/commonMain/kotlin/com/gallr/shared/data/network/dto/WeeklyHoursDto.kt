package com.gallr.shared.data.network.dto

import com.gallr.shared.hours.DailyOpening
import com.gallr.shared.hours.OpeningHoursCompleteness
import com.gallr.shared.hours.WeeklyOpeningHours
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val WEEKDAY_KEYS: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.name }
private val OPENING_KEYS = setOf("opens", "closes")
private val CLOCK_TIME = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")

/**
 * Reads the catalogue's `hours_weekly`: exactly the seven keys `MONDAY` … `SUNDAY`, each `null` (closed) or
 * `{"opens":"HH:MM","closes":"HH:MM"}` with opens before closes. Returns null for anything else, including a week
 * closed every day, so the caller falls back to reading the free-text hours.
 */
internal fun JsonElement?.toConfirmedWeeklyHoursOrNull(): WeeklyOpeningHours? {
    val week = this as? JsonObject ?: return null
    if (week.keys != WEEKDAY_KEYS.keys) return null
    val byDay = mutableMapOf<DayOfWeek, DailyOpening>()
    for ((key, value) in week) {
        if (value is JsonNull) continue
        byDay[WEEKDAY_KEYS.getValue(key)] = value.toDailyOpeningOrNull() ?: return null
    }
    if (byDay.isEmpty()) return null
    return WeeklyOpeningHours(byDay.toMap(), OpeningHoursCompleteness.CONFIRMED)
}

private fun JsonElement.toDailyOpeningOrNull(): DailyOpening? {
    val opening = this as? JsonObject ?: return null
    if (opening.keys != OPENING_KEYS) return null
    val opens = opening.getValue("opens").toClockTimeOrNull() ?: return null
    val closes = opening.getValue("closes").toClockTimeOrNull() ?: return null
    return if (closes > opens) DailyOpening(opens, closes) else null
}

private fun JsonElement.toClockTimeOrNull(): LocalTime? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val match = CLOCK_TIME.matchEntire(primitive.content) ?: return null
    return LocalTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}
