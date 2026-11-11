package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.FakeBookmarkRepository
import com.gallr.shared.repository.FakeExhibitionRepository
import com.gallr.shared.repository.FakeLanguageRepository
import com.gallr.shared.repository.LanguageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val UTC = TimeZone.UTC

private fun ex(
    id: String,
    opening: LocalDate,
    closing: LocalDate,
    reception: LocalDate? = null,
): Exhibition =
    Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = "",
        cityEn = "",
        regionKo = "",
        regionEn = "",
        openingDate = opening,
        closingDate = closing,
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        receptionDate = reception,
    )

private fun fixedClock(
    date: LocalDate,
    hour: Int,
): Clock =
    object : Clock {
        override fun now(): Instant = date.atTime(LocalTime(hour, 0)).toInstant(UTC)
    }

private fun at9(date: LocalDate): Instant = date.atTime(LocalTime(9, 0)).toInstant(UTC)

/** The saves as a guest's device holds them: always known. */
private class KnownBookmarks(
    private val bookmarks: BookmarkRepository,
) : ReminderBookmarks {
    override suspend fun knownIds(): Set<String> = bookmarks.observeBookmarkedIds().first()
}

/** A service that follows [bookmarks] for the rest of the test, as the app's composition does. */
private fun TestScope.followingService(
    scheduler: NotificationScheduler,
    exhibitions: ExhibitionRepository,
    bookmarks: BookmarkRepository,
    language: LanguageRepository,
    clock: Clock,
): NotificationSyncService = followingService(scheduler, exhibitions, KnownBookmarks(bookmarks), language, clock)

private fun TestScope.followingService(
    scheduler: NotificationScheduler,
    exhibitions: ExhibitionRepository,
    bookmarks: ReminderBookmarks,
    language: LanguageRepository,
    clock: Clock,
): NotificationSyncService {
    val service =
        NotificationSyncService(
            scheduler = scheduler,
            exhibitionRepo = exhibitions,
            languageRepo = language,
            clock = clock,
            timeZone = UTC,
        )
    backgroundScope.launch { service.follow(bookmarks) }
    runCurrent()
    return service
}

private class FailingExhibitionRepository : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> =
        Result.failure(IllegalStateException("offline"))

    override suspend fun getExhibitions(): Result<List<Exhibition>> = Result.failure(IllegalStateException("offline"))
}

private fun pendingClosing(
    id: String,
    closesOn: LocalDate,
    body: String = "Show $id closes in 3 days — don't miss it.",
): NotificationSpec =
    NotificationSpec(
        id = "${id}_closing",
        title = "gallr",
        body = body,
        triggerAt = at9(closesOn.minus(3, DateTimeUnit.DAY)),
        deepLink = DeepLink.Exhibition(id),
    )

class NotificationSyncServiceTest {
    @Test
    fun `no permission - sync is a no-op`() =
        runTest {
            val scheduler = FakeNotificationScheduler(permissionGranted = false)
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(emptyList()),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync()
            assertTrue(scheduler.scheduled.isEmpty())
            assertTrue(scheduler.cancelCalls.isEmpty())
            assertEquals(0, scheduler.cancelAllCallCount)
        }

    @Test
    fun `empty bookmarks - cancelAll and no schedules`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            scheduler.scheduled["stale_closing"] =
                NotificationSpec(
                    id = "stale_closing",
                    title = "x",
                    body = "y",
                    triggerAt = LocalDate(2026, 6, 1).atTime(LocalTime(9, 0)).toInstant(UTC),
                    deepLink = DeepLink.Exhibition("stale"),
                )
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(emptyList()),
                    FakeBookmarkRepository(emptySet()),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync()
            assertEquals(1, scheduler.cancelAllCallCount)
            assertTrue(scheduler.scheduled.isEmpty())
        }

    @Test
    fun `1 bookmark closing in 10d schedules CLOSING and INACTIVITY on mutation`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)
            assertContains(scheduler.scheduled.keys, "ex1_closing")
            assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
            assertFalse(scheduler.scheduled.containsKey("ex1_opening"))
            assertFalse(scheduler.scheduled.containsKey("ex1_reception"))
        }

    @Test
    fun `cold-start sync after inactivity fired does NOT re-schedule it`() =
        runTest {
            // Simulates iOS behavior: inactivity fired → removed from pending list.
            // Cold-start sync runs without a mutation → must NOT re-schedule inactivity.
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            // Pre-populate exhibition triggers but NOT inactivity (it already fired)
            scheduler.scheduled["ex1_closing"] =
                NotificationSpec(
                    id = "ex1_closing",
                    title = "gallr",
                    body = "Show ex1 closes in 3 days — don't miss it.",
                    triggerAt = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC),
                    deepLink = DeepLink.Exhibition("ex1"),
                )
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = false)
            assertFalse(scheduler.scheduled.containsKey(INACTIVITY_NOTIFICATION_ID))
        }

    @Test
    fun `cold-start sync preserves an in-flight inactivity`() =
        runTest {
            // Inactivity is still pending → must be preserved (no-op), not cancelled.
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            val inactivityTriggerAt = LocalDate(2026, 5, 7).atTime(LocalTime(9, 0)).toInstant(UTC)
            scheduler.scheduled[INACTIVITY_NOTIFICATION_ID] =
                NotificationSpec(
                    id = INACTIVITY_NOTIFICATION_ID,
                    title = "gallr",
                    body = "Your list hasn't changed in a while — check what's closing soon.",
                    triggerAt = inactivityTriggerAt,
                    deepLink = DeepLink.MyList,
                )
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = false)
            assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
            assertFalse(scheduler.cancelCalls.contains(INACTIVITY_NOTIFICATION_ID))
        }

    @Test
    fun `bookmark removed cancels its 3 IDs only`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            // Pre-populate as if previously scheduled
            listOf("ex1_closing", "ex1_opening", "ex1_reception", "ex2_closing").forEach { id ->
                scheduler.scheduled[id] =
                    NotificationSpec(
                        id = id,
                        title = "x",
                        body = "y",
                        triggerAt = LocalDate(2026, 6, 1).atTime(LocalTime(9, 0)).toInstant(UTC),
                        deepLink = DeepLink.Exhibition(id.substringBefore("_")),
                    )
            }
            val ex2 = ex("ex2", LocalDate(2026, 1, 1), LocalDate(2026, 6, 11))

            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(ex2)),
                    FakeBookmarkRepository(setOf("ex2")), // ex1 was removed
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync()

            assertContains(scheduler.cancelCalls, "ex1_closing")
            assertContains(scheduler.cancelCalls, "ex1_opening")
            assertContains(scheduler.cancelCalls, "ex1_reception")
            assertContains(scheduler.scheduled.keys, "ex2_closing")
        }

    @Test
    fun `already-scheduled trigger is no-op`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            val expectedTriggerAt = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC)
            scheduler.scheduled["ex1_closing"] =
                NotificationSpec(
                    id = "ex1_closing",
                    title = "gallr",
                    body = "Show ex1 closes in 3 days — don't miss it.",
                    triggerAt = expectedTriggerAt,
                    deepLink = DeepLink.Exhibition("ex1"),
                )

            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)

            // Strong assertion: schedule() was never called for ex1_closing.
            // Old assertion (no cancel) was weak — the spec was already scheduled.
            assertFalse(scheduler.scheduleCalls.any { it.id == "ex1_closing" })
            assertFalse(scheduler.cancelCalls.contains("ex1_closing"))
            assertContains(scheduler.scheduled.keys, "ex1_closing")
        }

    @Test
    fun `idempotent - 3 mutation syncs in a row produce same scheduled set`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)
            val first = scheduler.scheduled.keys.toSet()
            sync.sync(triggeredByMutation = true)
            sync.sync(triggeredByMutation = true)
            assertEquals(first, scheduler.scheduled.keys.toSet())
        }

    @Test
    fun `inactivity on mutation is scheduled at now plus 7d at 9am`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11))
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)
            val inactivity = scheduler.scheduled[INACTIVITY_NOTIFICATION_ID]!!
            val expected = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC)
            assertEquals(expected, inactivity.triggerAt)
        }

    @Test
    fun `past-due trigger is not scheduled`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val target = ex("ex1", LocalDate(2026, 4, 1), LocalDate(2026, 5, 2)) // closes tomorrow
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(target)),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync()
            assertFalse(scheduler.scheduled.containsKey("ex1_closing"))
        }

    @Test
    fun `failed catalogue read keeps every reminder`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val closing = pendingClosing("ex1", LocalDate(2026, 5, 11))
            val inactivity = TriggerRules.inactivitySpec(at9(LocalDate(2026, 4, 30)), UTC, AppLanguage.EN)
            scheduler.scheduled[closing.id] = closing
            scheduler.scheduled[inactivity.id] = inactivity
            val sync =
                followingService(
                    scheduler,
                    FailingExhibitionRepository(),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )

            sync.sync(triggeredByMutation = true)

            assertEquals(mapOf(closing.id to closing, inactivity.id to inactivity), scheduler.scheduled.toMap())
            assertTrue(scheduler.cancelCalls.isEmpty())
            assertTrue(scheduler.scheduleCalls.isEmpty())
            assertEquals(0, scheduler.cancelAllCallCount)
        }

    @Test
    fun `saves that are not known yet leave every reminder alone`() =
        runTest {
            // A signed-in account whose bookmarks have not been read: its set is a placeholder, not "nothing saved".
            val scheduler = FakeNotificationScheduler()
            val closing = pendingClosing("ex1", LocalDate(2026, 5, 11))
            scheduler.scheduled[closing.id] = closing
            val unknown =
                object : ReminderBookmarks {
                    override suspend fun knownIds(): Set<String>? = null
                }
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(emptyList()),
                    unknown,
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )

            sync.sync(triggeredByMutation = true)

            assertEquals(mapOf(closing.id to closing), scheduler.scheduled.toMap())
            assertEquals(0, scheduler.cancelAllCallCount)
            assertTrue(scheduler.cancelCalls.isEmpty())
        }

    @Test
    fun `a service nobody follows yet leaves every reminder alone`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val closing = pendingClosing("ex1", LocalDate(2026, 5, 11))
            scheduler.scheduled[closing.id] = closing
            val sync =
                NotificationSyncService(
                    scheduler = scheduler,
                    exhibitionRepo = FakeExhibitionRepository(emptyList()),
                    languageRepo = FakeLanguageRepository(AppLanguage.EN),
                    clock = fixedClock(LocalDate(2026, 5, 1), 12),
                    timeZone = UTC,
                )

            sync.sync(triggeredByMutation = true)

            assertEquals(mapOf(closing.id to closing), scheduler.scheduled.toMap())
            assertEquals(0, scheduler.cancelAllCallCount)
        }

    @Test
    fun `a changed closing date moves the reminder to the new day`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            scheduler.scheduled["ex1_closing"] = pendingClosing("ex1", LocalDate(2026, 5, 11))
            // The gallery extended the show by ten days since the reminder was set.
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 21)))),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )

            sync.sync()

            assertEquals(at9(LocalDate(2026, 5, 18)), scheduler.scheduled.getValue("ex1_closing").triggerAt)
            assertEquals(listOf("ex1_closing"), scheduler.scheduleCalls.map { it.id })
        }

    @Test
    fun `a renamed exhibition rewrites its reminder`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            scheduler.scheduled["ex1_closing"] =
                pendingClosing("ex1", LocalDate(2026, 5, 11), body = "Old title closes in 3 days — don't miss it.")
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11)))),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )

            sync.sync()

            assertEquals(
                "Show ex1 closes in 3 days — don't miss it.",
                scheduler.scheduled.getValue("ex1_closing").body,
            )
        }

    @Test
    fun `changing the app language rewrites pending reminders in the new language`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val language = FakeLanguageRepository(AppLanguage.EN)
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11)))),
                    FakeBookmarkRepository(setOf("ex1")),
                    language,
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)
            val inactivityAt = scheduler.scheduled.getValue(INACTIVITY_NOTIFICATION_ID).triggerAt

            language.setLanguage(AppLanguage.KO)
            runCurrent()

            assertEquals("전시 ex1 마감 3일 전입니다. 놓치지 마세요.", scheduler.scheduled.getValue("ex1_closing").body)
            val inactivity = scheduler.scheduled.getValue(INACTIVITY_NOTIFICATION_ID)
            assertEquals(
                NotificationContent.render(TriggerType.INACTIVITY, AppLanguage.KO, "", "").second,
                inactivity.body,
            )
            // Rewording is not a mutation: the inactivity reminder keeps its day.
            assertEquals(inactivityAt, inactivity.triggerAt)
        }

    @Test
    fun `reminders the platform lost are scheduled again on the next sync`() =
        runTest {
            // Android drops alarms on reboot and force-stop; the scheduler then no longer reports them as pending.
            val scheduler = FakeNotificationScheduler()
            val sync =
                followingService(
                    scheduler,
                    FakeExhibitionRepository(listOf(ex("ex1", LocalDate(2026, 1, 1), LocalDate(2026, 5, 11)))),
                    FakeBookmarkRepository(setOf("ex1")),
                    FakeLanguageRepository(AppLanguage.EN),
                    fixedClock(LocalDate(2026, 5, 1), 12),
                )
            sync.sync(triggeredByMutation = true)
            val before = scheduler.scheduled.keys.toSet()
            scheduler.scheduled.remove("ex1_closing")

            sync.sync(triggeredByMutation = false)

            assertEquals(before, scheduler.scheduled.keys.toSet())
        }
}
