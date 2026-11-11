package com.gallr.shared.notifications

import com.gallr.shared.observability.AppLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

private val storedRemindersLog = AppLog.tagged("StoredReminders")

/**
 * The text form of the reminders a platform scheduler records itself, for a platform that cannot list its own
 * pending reminders (Android's AlarmManager cannot).
 */
object StoredReminders {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(reminders: Collection<NotificationSpec>): String =
        json.encodeToString(reminders.map { StoredReminder.from(it) })

    /** Unreadable text decodes to no reminders: the next sync then schedules every one that is owed again. */
    fun decode(text: String?): List<NotificationSpec> {
        if (text == null) return emptyList()
        return try {
            json.decodeFromString<List<StoredReminder>>(text).map { it.toSpec() }
        } catch (error: SerializationException) {
            storedRemindersLog.warn("decode_stored_reminders", error)
            emptyList()
        }
    }
}

/** How a scheduler's own record of reminders compares with the alarms the system still holds. */
data class StoredReminderAudit(
    /** Every reminder still to come, including the [lost] ones once they are armed again. */
    val pending: List<NotificationSpec>,
    /** Reminders still to come whose alarm the system dropped (reboot, force-stop); they must be armed again. */
    val lost: List<NotificationSpec>,
    /** Ids whose time has passed: fired or missed, they are no longer pending and leave the record. */
    val finished: Set<String>,
)

fun auditStoredReminders(
    stored: List<NotificationSpec>,
    now: Instant,
    isArmed: (id: String) -> Boolean,
): StoredReminderAudit {
    val (upcoming, past) = stored.partition { it.triggerAt > now }
    return StoredReminderAudit(
        pending = upcoming,
        lost = upcoming.filterNot { isArmed(it.id) },
        finished = past.mapTo(mutableSetOf()) { it.id },
    )
}

@Serializable
private data class StoredReminder(
    val id: String,
    val title: String,
    val body: String,
    @SerialName("trigger_at_ms") val triggerAtMs: Long,
    /** The exhibition the reminder opens; null opens the saved list. */
    @SerialName("exhibition_id") val exhibitionId: String? = null,
) {
    fun toSpec(): NotificationSpec =
        NotificationSpec(
            id = id,
            title = title,
            body = body,
            triggerAt = Instant.fromEpochMilliseconds(triggerAtMs),
            deepLink = exhibitionId?.let { DeepLink.Exhibition(it) } ?: DeepLink.MyList,
        )

    companion object {
        fun from(spec: NotificationSpec): StoredReminder =
            StoredReminder(
                id = spec.id,
                title = spec.title,
                body = spec.body,
                triggerAtMs = spec.triggerAt.toEpochMilliseconds(),
                exhibitionId =
                    when (val link = spec.deepLink) {
                        is DeepLink.Exhibition -> link.id
                        DeepLink.MyList -> null
                    },
            )
    }
}
