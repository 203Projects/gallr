package com.gallr.shared.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.GallrUser
import com.gallr.shared.repository.BookmarkRepositoryImpl
import com.gallr.shared.repository.CloudBookmarkRepository
import com.gallr.shared.repository.FakeExhibitionRepository
import com.gallr.shared.repository.FakeLanguageRepository
import com.gallr.shared.repository.SyncBookmarkRepository
import com.gallr.shared.repository.cloudBookmarkTestClient
import com.gallr.shared.repository.signIn
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val DOWN = """{"message":"down"}"""

/**
 * Reminders follow the signed-in account's bookmarks: after sign-in the device's guest store is moved to the account
 * and emptied, so a service reading that store would see nothing saved and cancel every reminder.
 */
class SignedInRemindersTest {
    private val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(TimeZone.UTC)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val signedIn = MutableStateFlow<AuthState>(AuthState.Authenticated(GallrUser("u1", "하나", null)))

    @Test
    fun `a signed-in account's saves get reminders after the guest store is moved to the account`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val local = BookmarkRepositoryImpl(InMemoryPreferencesDataStore())
            local.addBookmark("ex1")
            val cloud = cloudRepository(accountBookmarksJson = """[{"exhibition_id":"ex1"}]""")
            val bookmarks = SyncBookmarkRepository(local, cloud, signedIn)
            val service =
                NotificationSyncService(
                    scheduler = scheduler,
                    exhibitionRepo = FakeExhibitionRepository(listOf(exhibition("ex1"))),
                    languageRepo = FakeLanguageRepository(AppLanguage.EN),
                    clock = clock,
                    timeZone = TimeZone.UTC,
                )
            backgroundScope.launch {
                service.follow(ReminderBookmarks.forAccount(bookmarks, signedIn, cloud.readBookmarkedIds))
            }
            runCurrent()
            bookmarks.setMutationListener { service.sync(triggeredByMutation = true) }

            // Launch sync before the account's bookmarks are read: nothing is known yet, so nothing changes.
            service.sync()
            assertTrue(scheduler.scheduled.isEmpty())
            assertEquals(0, scheduler.cancelAllCallCount)

            bookmarks.migrateLocalToCloud()

            assertTrue(local.observeBookmarkedIds().first().isEmpty())
            assertContains(scheduler.scheduled.keys, "ex1_closing")
            assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
            assertEquals(0, scheduler.cancelAllCallCount)
        }

    @Test
    fun `an account whose bookmarks cannot be read keeps its reminders`() =
        runTest {
            val scheduler = FakeNotificationScheduler()
            val closing =
                NotificationSpec(
                    id = "ex1_closing",
                    title = "gallr",
                    body = "Show ex1 closes in 3 days — don't miss it.",
                    triggerAt = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(TimeZone.UTC),
                    deepLink = DeepLink.Exhibition("ex1"),
                )
            scheduler.scheduled[closing.id] = closing
            val local = BookmarkRepositoryImpl(InMemoryPreferencesDataStore())
            val cloud = cloudRepository(accountBookmarksJson = null)
            val bookmarks = SyncBookmarkRepository(local, cloud, signedIn)
            val service =
                NotificationSyncService(
                    scheduler = scheduler,
                    exhibitionRepo = FakeExhibitionRepository(listOf(exhibition("ex1"))),
                    languageRepo = FakeLanguageRepository(AppLanguage.EN),
                    clock = clock,
                    timeZone = TimeZone.UTC,
                )
            backgroundScope.launch {
                service.follow(ReminderBookmarks.forAccount(bookmarks, signedIn, cloud.readBookmarkedIds))
            }
            runCurrent()
            bookmarks.setMutationListener { service.sync(triggeredByMutation = true) }

            // An offline launch: every read fails, the account settles without a read and the listener still fires.
            bookmarks.migrateLocalToCloud()

            assertEquals(mapOf(closing.id to closing), scheduler.scheduled.toMap())
            assertEquals(0, scheduler.cancelAllCallCount)
        }

    /** A cloud store whose reads answer [accountBookmarksJson], or fail when it is null; writes succeed. */
    private fun cloudRepository(accountBookmarksJson: String?): CloudBookmarkRepository =
        CloudBookmarkRepository(
            cloudBookmarkTestClient { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    request.method != HttpMethod.Get -> respond("", HttpStatusCode.Created, json)
                    accountBookmarksJson == null -> respond(DOWN, HttpStatusCode.ServiceUnavailable, json)
                    else -> respond(accountBookmarksJson, HttpStatusCode.OK, json)
                }
            }.apply { signIn("u1") },
        )

    private fun exhibition(id: String): Exhibition =
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
            openingDate = LocalDate(2026, 1, 1),
            closingDate = LocalDate(2026, 5, 11),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = null,
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
