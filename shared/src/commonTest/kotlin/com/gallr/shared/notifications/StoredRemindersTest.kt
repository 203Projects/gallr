package com.gallr.shared.notifications

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun at9(date: LocalDate): Instant = date.atTime(LocalTime(9, 0)).toInstant(TimeZone.UTC)

private val closing =
    NotificationSpec(
        id = "ex1_closing",
        title = "gallr",
        body = "색면 마감 3일 전입니다. 놓치지 마세요.",
        triggerAt = at9(LocalDate(2026, 5, 8)),
        deepLink = DeepLink.Exhibition("ex1"),
    )

private val inactivity =
    NotificationSpec(
        id = INACTIVITY_NOTIFICATION_ID,
        title = "gallr",
        body = "My List hasn't changed in a while — check what's closing soon.",
        triggerAt = at9(LocalDate(2026, 5, 3)),
        deepLink = DeepLink.MyList,
    )

class StoredRemindersTest {
    @Test
    fun `stored reminders read back exactly as they were scheduled`() {
        val text = StoredReminders.encode(listOf(closing, inactivity))

        assertEquals(listOf(closing, inactivity), StoredReminders.decode(text))
    }

    @Test
    fun `nothing stored reads as no reminders`() {
        assertTrue(StoredReminders.decode(null).isEmpty())
    }

    @Test
    fun `unreadable text reads as no reminders so the next sync schedules them again`() {
        assertTrue(StoredReminders.decode("{not json").isEmpty())
    }

    @Test
    fun `reminders whose time has passed are finished`() {
        val audit =
            auditStoredReminders(
                stored = listOf(closing, inactivity),
                now = at9(LocalDate(2026, 5, 4)),
                isArmed = { true },
            )

        assertEquals(listOf(closing), audit.pending)
        assertEquals(setOf(INACTIVITY_NOTIFICATION_ID), audit.finished)
        assertTrue(audit.lost.isEmpty())
    }

    @Test
    fun `upcoming reminders without a live alarm are lost and stay pending once armed again`() {
        // After a reboot or a force-stop Android holds none of the app's alarms, though its record lists them all.
        val audit =
            auditStoredReminders(
                stored = listOf(closing, inactivity),
                now = at9(LocalDate(2026, 5, 1)),
                isArmed = { id -> id == INACTIVITY_NOTIFICATION_ID },
            )

        assertEquals(listOf(closing), audit.lost)
        assertEquals(listOf(closing, inactivity), audit.pending)
        assertTrue(audit.finished.isEmpty())
    }
}
