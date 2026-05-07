package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val FIRE_TIME = LocalTime(9, 0)

object TriggerRules {

    fun computeTriggers(
        exhibition: Exhibition,
        now: Instant,
        timeZone: TimeZone,
        language: AppLanguage,
    ): List<NotificationSpec> {
        val name = exhibition.localizedName(language)
        val venue = exhibition.localizedVenueName(language)

        val candidates = buildList {
            add(specFor(exhibition, TriggerType.CLOSING, exhibition.closingDate.minus(3, DateTimeUnit.DAY), timeZone, language, name, venue))
            add(specFor(exhibition, TriggerType.OPENING, exhibition.openingDate.minus(3, DateTimeUnit.DAY), timeZone, language, name, venue))
            exhibition.receptionDate?.let { reception ->
                add(specFor(exhibition, TriggerType.RECEPTION, reception, timeZone, language, name, venue))
            }
        }
        return candidates.filter { it.triggerAt > now }
    }

    fun inactivitySpec(now: Instant, timeZone: TimeZone, language: AppLanguage): NotificationSpec {
        val targetDate = now.toLocalDateTime(timeZone).date.plus(7, DateTimeUnit.DAY)
        val (title, body) = NotificationContent.render(
            type = TriggerType.INACTIVITY,
            language = language,
            exhibitionName = "",
            venueName = "",
        )
        return NotificationSpec(
            id = INACTIVITY_NOTIFICATION_ID,
            title = title,
            body = body,
            triggerAt = targetDate.atTime(FIRE_TIME).toInstant(timeZone),
            deepLink = DeepLink.MyList,
        )
    }

    private fun specFor(
        exhibition: Exhibition,
        type: TriggerType,
        targetDate: LocalDate,
        timeZone: TimeZone,
        language: AppLanguage,
        name: String,
        venue: String,
    ): NotificationSpec {
        val (title, body) = NotificationContent.render(type, language, name, venue)
        return NotificationSpec(
            id = notificationId(exhibition.id, type),
            title = title,
            body = body,
            triggerAt = targetDate.atTime(FIRE_TIME).toInstant(timeZone),
            deepLink = DeepLink.Exhibition(exhibition.id),
        )
    }
}
