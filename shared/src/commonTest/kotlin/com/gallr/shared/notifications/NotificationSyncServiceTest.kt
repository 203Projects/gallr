package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.FakeBookmarkRepository
import com.gallr.shared.repository.FakeExhibitionRepository
import com.gallr.shared.repository.FakeLanguageRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val UTC = TimeZone.UTC

private fun ex(
    id: String,
    opening: LocalDate,
    closing: LocalDate,
    reception: LocalDate? = null,
): Exhibition = Exhibition(
    id = id,
    nameKo = "전시 $id", nameEn = "Show $id",
    venueNameKo = "장소 $id", venueNameEn = "Venue $id",
    cityKo = "", cityEn = "",
    regionKo = "", regionEn = "",
    openingDate = opening,
    closingDate = closing,
    isFeatured = false, isEditorsPick = false,
    latitude = null, longitude = null,
    descriptionKo = "", descriptionEn = "",
    addressKo = "", addressEn = "",
    coverImageUrl = null,
    receptionDate = reception,
)

private fun fixedClock(date: LocalDate, hour: Int): Clock = object : Clock {
    override fun now(): Instant = date.atTime(LocalTime(hour, 0)).toInstant(UTC)
}

class NotificationSyncServiceTest {

    @Test
    fun `no permission - sync is a no-op`() = runTest {
        val scheduler = FakeNotificationScheduler(permissionGranted = false)
        val sync = NotificationSyncService(
            scheduler = scheduler,
            exhibitionRepo = FakeExhibitionRepository(emptyList()),
            bookmarkRepo = FakeBookmarkRepository(setOf("ex1")),
            languageRepo = FakeLanguageRepository(AppLanguage.EN),
            clock = fixedClock(LocalDate(2026, 5, 1), 12),
            timeZone = UTC,
        )
        sync.sync()
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelCalls.isEmpty())
        assertEquals(0, scheduler.cancelAllCallCount)
    }

    @Test
    fun `empty bookmarks - cancelAll and no schedules`() = runTest {
        val scheduler = FakeNotificationScheduler()
        scheduler.scheduled["stale_closing"] = NotificationSpec(
            id = "stale_closing", title = "x", body = "y",
            triggerAt = LocalDate(2026, 6, 1).atTime(LocalTime(9, 0)).toInstant(UTC),
            deepLink = DeepLink.Exhibition("stale"),
        )
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(emptyList()),
            FakeBookmarkRepository(emptySet()),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync()
        assertEquals(1, scheduler.cancelAllCallCount)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `1 bookmark closing in 10d schedules CLOSING and INACTIVITY on mutation`() = runTest {
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = true)
        assertContains(scheduler.scheduled.keys, "ex1_closing")
        assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
        assertFalse(scheduler.scheduled.containsKey("ex1_opening"))
        assertFalse(scheduler.scheduled.containsKey("ex1_reception"))
    }

    @Test
    fun `cold-start sync after inactivity fired does NOT re-schedule it`() = runTest {
        // Simulates iOS behavior: inactivity fired → removed from pending list.
        // Cold-start sync runs without a mutation → must NOT re-schedule inactivity.
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        // Pre-populate exhibition triggers but NOT inactivity (it already fired)
        scheduler.scheduled["ex1_closing"] = NotificationSpec(
            id = "ex1_closing", title = "gallr",
            body = "Show ex1 closes in 3 days — don't miss it.",
            triggerAt = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC),
            deepLink = DeepLink.Exhibition("ex1"),
        )
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = false)
        assertFalse(scheduler.scheduled.containsKey(INACTIVITY_NOTIFICATION_ID))
    }

    @Test
    fun `cold-start sync preserves an in-flight inactivity`() = runTest {
        // Inactivity is still pending → must be preserved (no-op), not cancelled.
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        val inactivityTriggerAt = LocalDate(2026, 5, 7).atTime(LocalTime(9, 0)).toInstant(UTC)
        scheduler.scheduled[INACTIVITY_NOTIFICATION_ID] = NotificationSpec(
            id = INACTIVITY_NOTIFICATION_ID,
            title = "gallr",
            body = "Your list hasn't changed in a while — check what's closing soon.",
            triggerAt = inactivityTriggerAt,
            deepLink = DeepLink.MyList,
        )
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = false)
        assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
        assertFalse(scheduler.cancelCalls.contains(INACTIVITY_NOTIFICATION_ID))
    }

    @Test
    fun `bookmark removed cancels its 3 IDs only`() = runTest {
        val scheduler = FakeNotificationScheduler()
        // Pre-populate as if previously scheduled
        listOf("ex1_closing", "ex1_opening", "ex1_reception", "ex2_closing").forEach { id ->
            scheduler.scheduled[id] = NotificationSpec(
                id = id, title = "x", body = "y",
                triggerAt = LocalDate(2026, 6, 1).atTime(LocalTime(9, 0)).toInstant(UTC),
                deepLink = DeepLink.Exhibition(id.substringBefore("_")),
            )
        }
        val ex2 = ex("ex2", LocalDate(2026, 1, 1), LocalDate(2026, 6, 11))

        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(ex2)),
            FakeBookmarkRepository(setOf("ex2")),  // ex1 was removed
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync()

        assertContains(scheduler.cancelCalls, "ex1_closing")
        assertContains(scheduler.cancelCalls, "ex1_opening")
        assertContains(scheduler.cancelCalls, "ex1_reception")
        assertContains(scheduler.scheduled.keys, "ex2_closing")
    }

    @Test
    fun `already-scheduled trigger is no-op`() = runTest {
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        val expectedTriggerAt = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC)
        scheduler.scheduled["ex1_closing"] = NotificationSpec(
            id = "ex1_closing", title = "gallr",
            body = "Show ex1 closes in 3 days — don't miss it.",
            triggerAt = expectedTriggerAt,
            deepLink = DeepLink.Exhibition("ex1"),
        )

        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = true)

        // Strong assertion: schedule() was never called for ex1_closing.
        // Old assertion (no cancel) was weak — the spec was already scheduled.
        assertFalse(scheduler.scheduleCalls.any { it.id == "ex1_closing" })
        assertFalse(scheduler.cancelCalls.contains("ex1_closing"))
        assertContains(scheduler.scheduled.keys, "ex1_closing")
    }

    @Test
    fun `idempotent - 3 mutation syncs in a row produce same scheduled set`() = runTest {
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = true)
        val first = scheduler.scheduled.keys.toSet()
        sync.sync(triggeredByMutation = true)
        sync.sync(triggeredByMutation = true)
        assertEquals(first, scheduler.scheduled.keys.toSet())
    }

    @Test
    fun `inactivity on mutation is scheduled at now plus 7d at 9am`() = runTest {
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync(triggeredByMutation = true)
        val inactivity = scheduler.scheduled[INACTIVITY_NOTIFICATION_ID]!!
        val expected = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC)
        assertEquals(expected, inactivity.triggerAt)
    }

    @Test
    fun `past-due trigger is not scheduled`() = runTest {
        val scheduler = FakeNotificationScheduler()
        val target = ex("ex1", LocalDate(2026, 4, 1), LocalDate(2026, 5, 2))  // closes tomorrow
        val sync = NotificationSyncService(
            scheduler,
            FakeExhibitionRepository(listOf(target)),
            FakeBookmarkRepository(setOf("ex1")),
            FakeLanguageRepository(AppLanguage.EN),
            fixedClock(LocalDate(2026, 5, 1), 12),
            UTC,
        )
        sync.sync()
        assertFalse(scheduler.scheduled.containsKey("ex1_closing"))
    }
}
