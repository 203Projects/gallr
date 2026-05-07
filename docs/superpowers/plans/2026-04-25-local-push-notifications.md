# Local Push Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device local notifications that nudge users about their bookmarked exhibitions (closing soon, opening soon, reception today) and re-engage inactive users (My List unchanged for 7 days). Bilingual copy. Permission-gated. Notification taps deep-link to the relevant exhibition.

**Architecture:** Platform schedulers (`AlarmManager` on Android, `UNUserNotificationCenter` on iOS) wrapped behind a single `expect/actual NotificationScheduler` interface in `shared/commonMain`. A `NotificationSyncService` in commonMain owns diff-based reconciliation: compute desired notifications from bookmarks → diff against currently-scheduled IDs → schedule missing, cancel extra. Called on bookmark mutation and after first successful exhibition fetch on cold start.

**Tech Stack:** Kotlin 2.1.20, kotlinx-datetime 0.6.1, kotlinx-coroutines-test, androidx.core (`NotificationManagerCompat`), `AlarmManager`, `UNUserNotificationCenter`, DataStore Preferences (Android scheduled-id index, permission-prompted flag).

**Spec:** `docs/superpowers/specs/2026-04-25-local-push-notifications-design.md`

---

## File Structure

**New files (shared module):**

```
shared/src/commonMain/kotlin/com/gallr/shared/notifications/
├── DeepLink.kt                  sealed Exhibition(id) | MyList
├── TriggerType.kt               enum CLOSING, OPENING, RECEPTION, INACTIVITY
├── NotificationSpec.kt          data class id, title, body, triggerAt, deepLink
├── TriggerRules.kt              pure: computeTriggers(exhibition, now, tz, lang, content)
├── NotificationContent.kt       bilingual templates (4 trigger × 2 lang)
├── NotificationScheduler.kt     expect interface
└── NotificationSyncService.kt   diff + reconcile

shared/src/androidMain/kotlin/com/gallr/shared/notifications/
├── NotificationScheduler.android.kt   AlarmManager + NotificationManagerCompat
├── ScheduledIdIndex.kt                DataStore-backed (Android needs it)
├── NotificationReceiver.kt            BroadcastReceiver — fires + posts builder
└── NotificationConstants.kt           channel ID, action keys, extras keys

shared/src/iosMain/kotlin/com/gallr/shared/notifications/
├── NotificationScheduler.ios.kt   UNUserNotificationCenter wrapper
└── NotificationDelegate.kt        UNUserNotificationCenterDelegateProtocol

shared/src/commonTest/kotlin/com/gallr/shared/notifications/
├── TriggerRulesTest.kt
├── NotificationSyncServiceTest.kt
├── NotificationContentTest.kt
└── FakeNotificationScheduler.kt   test double

composeApp/src/commonMain/kotlin/com/gallr/app/notifications/
├── NotificationPermissionHandler.kt    composable + state holder
└── NotificationPermissionStrings.kt    bilingual rationale copy
```

**Modified files:**

```
shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepository.kt
  - Add `setMutationListener(listener: () -> Unit)` (lightweight hook)

shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepositoryImpl.kt
  - Maintain a nullable mutation listener
  - Call listener?.invoke() at the END of addBookmark + removeBookmark + clearAll

composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
  - Accept NotificationScheduler + NotificationSyncService params
  - LaunchedEffect on first Success featuredState → syncService.sync()
  - Collect scheduler.pendingDeepLink → route to ExhibitionDetail or MyList
  - Mount NotificationPermissionHandler

composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
  - Construct AndroidNotificationScheduler + NotificationSyncService
  - Pass into App()
  - In onCreate + onNewIntent: extract deep-link from intent extras

composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
  - Construct IosNotificationScheduler + NotificationSyncService
  - Wire UNUserNotificationCenter.delegate
  - Pass into App()

composeApp/src/androidMain/AndroidManifest.xml
  - <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  - Register NotificationReceiver
```

---

## Task 1: Create DeepLink, TriggerType, NotificationSpec data classes

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/DeepLink.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/TriggerType.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationSpec.kt`

- [ ] **Step 1: Create DeepLink.kt**

```kotlin
package com.gallr.shared.notifications

sealed class DeepLink {
    data class Exhibition(val id: String) : DeepLink()
    object MyList : DeepLink()
}
```

- [ ] **Step 2: Create TriggerType.kt**

```kotlin
package com.gallr.shared.notifications

enum class TriggerType {
    CLOSING,
    OPENING,
    RECEPTION,
    INACTIVITY,
}
```

- [ ] **Step 3: Create NotificationSpec.kt**

```kotlin
package com.gallr.shared.notifications

import kotlinx.datetime.Instant

data class NotificationSpec(
    val id: String,
    val title: String,
    val body: String,
    val triggerAt: Instant,
    val deepLink: DeepLink,
)

const val INACTIVITY_NOTIFICATION_ID = "inactivity"

fun notificationId(exhibitionId: String, type: TriggerType): String = when (type) {
    TriggerType.CLOSING -> "${exhibitionId}_closing"
    TriggerType.OPENING -> "${exhibitionId}_opening"
    TriggerType.RECEPTION -> "${exhibitionId}_reception"
    TriggerType.INACTIVITY -> INACTIVITY_NOTIFICATION_ID
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/
git commit -m "feat(notifications): add DeepLink, TriggerType, NotificationSpec types"
```

---

## Task 2: NotificationContent — bilingual templates with tests

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationContentTest.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationContent.kt`

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationContentTest.kt`:

```kotlin
package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationContentTest {

    @Test
    fun `closing soon - English`() {
        val (title, body) = NotificationContent.render(
            type = TriggerType.CLOSING,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("gallr", title)
        assertEquals("Color Field closes in 3 days — don't miss it.", body)
    }

    @Test
    fun `closing soon - Korean`() {
        val (title, body) = NotificationContent.render(
            type = TriggerType.CLOSING,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("gallr", title)
        assertEquals("색면 마감 3일 전입니다. 놓치지 마세요.", body)
    }

    @Test
    fun `opening soon - English`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.OPENING,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("Color Field opens in 3 days.", body)
    }

    @Test
    fun `opening soon - Korean`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.OPENING,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("색면 개막 3일 전입니다.", body)
    }

    @Test
    fun `reception today - English uses venue name`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.RECEPTION,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("Reception today at Pace Gallery.", body)
    }

    @Test
    fun `reception today - Korean uses venue name`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.RECEPTION,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("오늘 페이스 갤러리에서 오프닝 리셉션이 열립니다.", body)
    }

    @Test
    fun `inactivity - English ignores exhibition + venue args`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.INACTIVITY,
            language = AppLanguage.EN,
            exhibitionName = "",
            venueName = "",
        )
        assertEquals("Your list hasn't changed in a while — check what's closing soon.", body)
    }

    @Test
    fun `inactivity - Korean ignores exhibition + venue args`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.INACTIVITY,
            language = AppLanguage.KO,
            exhibitionName = "",
            venueName = "",
        )
        assertEquals("마이 리스트를 업데이트한 지 꽤 됐어요. 곧 마감되는 전시를 확인해보세요.", body)
    }
}
```

- [ ] **Step 2: Run, expect failure (NotificationContent not defined)**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.NotificationContentTest*"`
Expected: FAIL with "Unresolved reference: NotificationContent"

- [ ] **Step 3: Write NotificationContent.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationContent.kt`:

```kotlin
package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage

object NotificationContent {

    private const val APP_NAME = "gallr"

    fun render(
        type: TriggerType,
        language: AppLanguage,
        exhibitionName: String,
        venueName: String,
    ): Pair<String, String> {
        val body = when (type) {
            TriggerType.CLOSING -> when (language) {
                AppLanguage.EN -> "$exhibitionName closes in 3 days — don't miss it."
                AppLanguage.KO -> "$exhibitionName 마감 3일 전입니다. 놓치지 마세요."
            }
            TriggerType.OPENING -> when (language) {
                AppLanguage.EN -> "$exhibitionName opens in 3 days."
                AppLanguage.KO -> "$exhibitionName 개막 3일 전입니다."
            }
            TriggerType.RECEPTION -> when (language) {
                AppLanguage.EN -> "Reception today at $venueName."
                AppLanguage.KO -> "오늘 ${venueName}에서 오프닝 리셉션이 열립니다."
            }
            TriggerType.INACTIVITY -> when (language) {
                AppLanguage.EN -> "Your list hasn't changed in a while — check what's closing soon."
                AppLanguage.KO -> "마이 리스트를 업데이트한 지 꽤 됐어요. 곧 마감되는 전시를 확인해보세요."
            }
        }
        return APP_NAME to body
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.NotificationContentTest*"`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationContent.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationContentTest.kt
git commit -m "feat(notifications): add bilingual NotificationContent templates"
```

---

## Task 3: TriggerRules — pure functions for trigger computation, with tests

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/TriggerRules.kt`

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt`:

```kotlin
package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC = TimeZone.UTC

private fun fixture(
    id: String = "ex1",
    nameEn: String = "Color Field",
    nameKo: String = "색면",
    venueEn: String = "Pace",
    venueKo: String = "페이스",
    opening: LocalDate,
    closing: LocalDate,
    reception: LocalDate? = null,
): Exhibition = Exhibition(
    id = id,
    nameKo = nameKo,
    nameEn = nameEn,
    venueNameKo = venueKo,
    venueNameEn = venueEn,
    cityKo = "", cityEn = "",
    regionKo = "", regionEn = "",
    openingDate = opening,
    closingDate = closing,
    isFeatured = false,
    isEditorsPick = false,
    latitude = null, longitude = null,
    descriptionKo = "", descriptionEn = "",
    addressKo = "", addressEn = "",
    coverImageUrl = null,
    receptionDate = reception,
)

private fun localDateAt9amInstant(date: LocalDate, tz: TimeZone): Instant =
    date.atTime(LocalTime(9, 0)).toInstant(tz)

class TriggerRulesTest {

    @Test
    fun `closing in 5 days at noon UTC - returns CLOSING at closing-3d 9am UTC`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 6))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        val closing = triggers.firstOrNull { it.id == "ex1_closing" }
        assertNotNull(closing)
        assertEquals(localDateAt9amInstant(LocalDate(2026, 5, 3), UTC), closing.triggerAt)
        assertEquals(DeepLink.Exhibition("ex1"), closing.deepLink)
    }

    @Test
    fun `closing in 2 days - CLOSING is past-due, skipped`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 3))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_closing" })
    }

    @Test
    fun `opens in 10 days - returns OPENING + CLOSING`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 5, 11), closing = LocalDate(2026, 6, 11))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNotNull(triggers.firstOrNull { it.id == "ex1_opening" })
        assertNotNull(triggers.firstOrNull { it.id == "ex1_closing" })
        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `reception today at 8am - RECEPTION at 9am same day, valid`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = LocalDate(2026, 5, 1),
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        val rec = triggers.firstOrNull { it.id == "ex1_reception" }
        assertNotNull(rec)
        assertEquals(localDateAt9amInstant(LocalDate(2026, 5, 1), UTC), rec.triggerAt)
    }

    @Test
    fun `reception today at 10am - RECEPTION past-due, skipped`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(10, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = LocalDate(2026, 5, 1),
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `receptionDate null - no RECEPTION trigger`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(
            opening = LocalDate(2026, 5, 1),
            closing = LocalDate(2026, 6, 1),
            reception = null,
        )
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertNull(triggers.firstOrNull { it.id == "ex1_reception" })
    }

    @Test
    fun `1-day pop-up exhibition - both 3-day triggers past, skipped`() {
        // opening tomorrow, closing day after — 3-day-before triggers are in the past
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(8, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 5, 2), closing = LocalDate(2026, 5, 3))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.EN)

        assertTrue(triggers.isEmpty(), "all triggers past-due for 1-day pop-up")
    }

    @Test
    fun `Korean language renders KO body`() {
        val now = LocalDate(2026, 5, 1).atTime(LocalTime(12, 0)).toInstant(UTC)
        val ex = fixture(opening = LocalDate(2026, 4, 1), closing = LocalDate(2026, 5, 6))
        val triggers = TriggerRules.computeTriggers(ex, now, UTC, AppLanguage.KO)

        val closing = triggers.firstOrNull { it.id == "ex1_closing" }
        assertNotNull(closing)
        assertEquals("색면 마감 3일 전입니다. 놓치지 마세요.", closing.body)
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.TriggerRulesTest*"`
Expected: FAIL with "Unresolved reference: TriggerRules"

- [ ] **Step 3: Write TriggerRules.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/notifications/TriggerRules.kt`:

```kotlin
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
import kotlinx.datetime.toInstant

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

// Helpers — not exposed publicly
private fun Instant.toLocalDateTime(tz: TimeZone) =
    kotlinx.datetime.toLocalDateTime(this, tz)

private fun LocalDate.plus(value: Int, unit: DateTimeUnit.DateBased): LocalDate =
    kotlinx.datetime.plus(this, value, unit)
```

If the helper imports cause issues, use the standard library imports directly:

```kotlin
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.TriggerRulesTest*"`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/TriggerRules.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt
git commit -m "feat(notifications): add TriggerRules pure-function trigger computation"
```

---

## Task 4: NotificationScheduler expect interface

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationScheduler.kt`

- [ ] **Step 1: Write the expect interface**

Create `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationScheduler.kt`:

```kotlin
package com.gallr.shared.notifications

import kotlinx.coroutines.flow.StateFlow

expect class NotificationScheduler {
    suspend fun hasPermission(): Boolean
    suspend fun requestPermission(): Boolean
    suspend fun schedule(spec: NotificationSpec)
    suspend fun cancel(id: String)
    suspend fun cancelAll()
    suspend fun scheduledIds(): Set<String>

    val pendingDeepLink: StateFlow<DeepLink?>
    fun setPendingDeepLink(link: DeepLink)
    fun consumePendingDeepLink()
}
```

- [ ] **Step 2: Verify metadata compile (expect-only is fine)**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: PASS (we'll add actuals in Tasks 7 + 9)

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationScheduler.kt
git commit -m "feat(notifications): add NotificationScheduler expect interface"
```

---

## Task 5: FakeNotificationScheduler (test double)

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/FakeNotificationScheduler.kt`

This file provides a test double for use in NotificationSyncService tests. Note: cannot satisfy the `expect class` directly in commonTest because actuals must live in platform sourceSets. Instead we create a parallel **interface** in commonMain that the actuals also implement, and the fake implements the interface. **OR** we test `NotificationSyncService` against a JVM-only test target that supplies an actual.

The cleaner approach: introduce a small **non-expect** interface that the SyncService consumes, and have the platform actuals delegate to/from it.

- [ ] **Step 1: Refactor NotificationScheduler to an interface + companion**

Edit `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationScheduler.kt`:

```kotlin
package com.gallr.shared.notifications

import kotlinx.coroutines.flow.StateFlow

interface NotificationScheduler {
    suspend fun hasPermission(): Boolean
    suspend fun requestPermission(): Boolean
    suspend fun schedule(spec: NotificationSpec)
    suspend fun cancel(id: String)
    suspend fun cancelAll()
    suspend fun scheduledIds(): Set<String>

    val pendingDeepLink: StateFlow<DeepLink?>
    fun setPendingDeepLink(link: DeepLink)
    fun consumePendingDeepLink()
}
```

(Pure interface; no `expect`. Platform actuals will implement directly. This simplifies testing dramatically and matches the existing pattern in this repo — `BookmarkRepository` is also an interface, `BookmarkRepositoryImpl` is the impl.)

- [ ] **Step 2: Create the fake**

Create `shared/src/commonTest/kotlin/com/gallr/shared/notifications/FakeNotificationScheduler.kt`:

```kotlin
package com.gallr.shared.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeNotificationScheduler(
    private var permissionGranted: Boolean = true,
) : NotificationScheduler {

    val scheduled = mutableMapOf<String, NotificationSpec>()
    val cancelCalls = mutableListOf<String>()
    val cancelAllCallCount: Int get() = _cancelAllCalls
    private var _cancelAllCalls = 0
    var requestPermissionResult: Boolean = true
    var requestPermissionCalls: Int = 0
        private set

    override suspend fun hasPermission(): Boolean = permissionGranted

    override suspend fun requestPermission(): Boolean {
        requestPermissionCalls++
        permissionGranted = requestPermissionResult
        return permissionGranted
    }

    override suspend fun schedule(spec: NotificationSpec) {
        scheduled[spec.id] = spec
    }

    override suspend fun cancel(id: String) {
        cancelCalls.add(id)
        scheduled.remove(id)
    }

    override suspend fun cancelAll() {
        _cancelAllCalls++
        scheduled.clear()
    }

    override suspend fun scheduledIds(): Set<String> = scheduled.keys.toSet()

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink

    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    fun setPermission(granted: Boolean) { permissionGranted = granted }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :shared:compileTestKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationScheduler.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/FakeNotificationScheduler.kt
git commit -m "feat(notifications): refactor NotificationScheduler to interface + fake"
```

---

## Task 6: NotificationSyncService — diff + reconcile, with tests

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationSyncService.kt`

- [ ] **Step 1: Write failing tests with full coverage**

Create `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt`:

```kotlin
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
    fun `empty bookmarks - cancelAll, no schedules`() = runTest {
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
    fun `1 bookmark closing in 10d - schedules CLOSING + INACTIVITY`() = runTest {
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
        sync.sync()
        assertContains(scheduler.scheduled.keys, "ex1_closing")
        assertContains(scheduler.scheduled.keys, INACTIVITY_NOTIFICATION_ID)
        assertFalse(scheduler.scheduled.containsKey("ex1_opening"))
        assertFalse(scheduler.scheduled.containsKey("ex1_reception"))
    }

    @Test
    fun `bookmark removed - cancels its 3 IDs only`() = runTest {
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

        // ex1's three ids cancelled
        assertContains(scheduler.cancelCalls, "ex1_closing")
        assertContains(scheduler.cancelCalls, "ex1_opening")
        assertContains(scheduler.cancelCalls, "ex1_reception")
        // ex2's closing remains
        assertContains(scheduler.scheduled.keys, "ex2_closing")
    }

    @Test
    fun `already-scheduled trigger is no-op (no double schedule)`() = runTest {
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
        sync.sync()

        // ex1_closing was already in scheduled → not re-scheduled (no schedule call beyond inactivity)
        // We can detect via cancelCalls — should not contain ex1_closing
        assertFalse(scheduler.cancelCalls.contains("ex1_closing"))
        assertContains(scheduler.scheduled.keys, "ex1_closing")
    }

    @Test
    fun `idempotent - 3 syncs in a row produce same scheduled set`() = runTest {
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
        sync.sync()
        val first = scheduler.scheduled.keys.toSet()
        sync.sync()
        sync.sync()
        assertEquals(first, scheduler.scheduled.keys.toSet())
    }

    @Test
    fun `inactivity scheduled at now+7d at 9am`() = runTest {
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
        sync.sync()
        val inactivity = scheduler.scheduled[INACTIVITY_NOTIFICATION_ID]!!
        val expected = LocalDate(2026, 5, 8).atTime(LocalTime(9, 0)).toInstant(UTC)
        assertEquals(expected, inactivity.triggerAt)
    }

    @Test
    fun `past-due trigger is not scheduled (closing in 1 day)`() = runTest {
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
```

You'll also need a **FakeBookmarkRepository** and **FakeExhibitionRepository** if they don't exist. Check first:

Run: `find shared/src/commonTest -name "Fake*.kt"`

**Note:** `FakeLanguageRepository` exists but is `private` inside `LanguageRepositoryTest.kt`. Promote it to a public top-level test fake by creating `shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeLanguageRepository.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLanguageRepository(
    savedLanguage: AppLanguage = AppLanguage.KO,
) : LanguageRepository {
    private val flow = MutableStateFlow(savedLanguage)
    override fun observeLanguage(): Flow<AppLanguage> = flow
    override suspend fun setLanguage(language: AppLanguage) { flow.value = language }
}
```

Then DELETE the inline `private class FakeLanguageRepository(...)` from `LanguageRepositoryTest.kt`. Update calls in that file from `FakeLanguageRepository(savedLanguage = ...)` to `FakeLanguageRepository(savedLanguage = ... ?: AppLanguage.KO)` if they pass `null`. Verify `./gradlew :shared:allTests --tests "*Language*"` still passes.

Then create the remaining missing fakes:

If FakeBookmarkRepository doesn't exist, create `shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeBookmarkRepository.kt`:

```kotlin
package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeBookmarkRepository(initial: Set<String> = emptySet()) : BookmarkRepository {
    private val state = MutableStateFlow(initial)
    override fun observeBookmarkedIds(): Flow<Set<String>> = state.asStateFlow()
    override suspend fun addBookmark(exhibitionId: String) { state.value = state.value + exhibitionId }
    override suspend fun removeBookmark(exhibitionId: String) { state.value = state.value - exhibitionId }
    override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in state.value
    override suspend fun clearAll() { state.value = emptySet() }
}
```

If FakeExhibitionRepository doesn't exist, create `shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeExhibitionRepository.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Exhibition

class FakeExhibitionRepository(
    private val exhibitions: List<Exhibition>,
) : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions.filter { it.isFeatured })
    override suspend fun getExhibitions(): Result<List<Exhibition>> =
        Result.success(exhibitions)
}
```

- [ ] **Step 2: Run, expect compile failure (NotificationSyncService missing)**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.NotificationSyncServiceTest*"`
Expected: FAIL with "Unresolved reference: NotificationSyncService"

- [ ] **Step 3: Write NotificationSyncService.kt**

Create `shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationSyncService.kt`:

```kotlin
package com.gallr.shared.notifications

import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

class NotificationSyncService(
    private val scheduler: NotificationScheduler,
    private val exhibitionRepo: ExhibitionRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val languageRepo: LanguageRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    suspend fun sync() {
        if (!scheduler.hasPermission()) return

        val bookmarkIds = bookmarkRepo.observeBookmarkedIds().first()
        if (bookmarkIds.isEmpty()) {
            scheduler.cancelAll()
            return
        }

        val language = languageRepo.observeLanguage().first()
        val now = clock.now()

        // Resolve bookmarked exhibitions; tolerate missing/error by treating as empty
        val all = exhibitionRepo.getExhibitions().getOrElse { emptyList() }
        val bookmarked = all.filter { it.id in bookmarkIds }

        val desired = buildSet {
            bookmarked.forEach { ex ->
                addAll(TriggerRules.computeTriggers(ex, now, timeZone, language))
            }
            add(TriggerRules.inactivitySpec(now, timeZone, language))
        }
        val desiredById = desired.associateBy { it.id }
        val existing = scheduler.scheduledIds()

        // Cancel anything no longer desired
        for (id in existing - desiredById.keys) {
            scheduler.cancel(id)
        }
        // Schedule anything new
        for (spec in desired) {
            if (spec.id !in existing) {
                scheduler.schedule(spec)
            }
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew :shared:allTests --tests "com.gallr.shared.notifications.NotificationSyncServiceTest*"`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/notifications/NotificationSyncService.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeBookmarkRepository.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeExhibitionRepository.kt
git commit -m "feat(notifications): add NotificationSyncService with diff/reconcile"
```

---

## Task 7: Wire BookmarkRepository to fire a mutation listener

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepositoryImpl.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeBookmarkRepository.kt`

We trigger sync via a listener pattern rather than wiring the SyncService directly into the repo (keeps shared/repository free of notifications dependency).

- [ ] **Step 1: Add listener to interface**

Edit `shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepository.kt`:

```kotlin
package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeBookmarkedIds(): Flow<Set<String>>
    suspend fun addBookmark(exhibitionId: String)
    suspend fun removeBookmark(exhibitionId: String)
    suspend fun isBookmarked(exhibitionId: String): Boolean
    suspend fun clearAll()
    fun setMutationListener(listener: suspend () -> Unit)
}
```

- [ ] **Step 2: Implement listener in BookmarkRepositoryImpl**

Edit `shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepositoryImpl.kt`. Replace the file contents:

```kotlin
package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val BOOKMARKED_IDS_KEY = stringSetPreferencesKey("bookmarked_exhibition_ids")

class BookmarkRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : BookmarkRepository {

    private var mutationListener: (suspend () -> Unit)? = null

    override fun setMutationListener(listener: suspend () -> Unit) {
        mutationListener = listener
    }

    override fun observeBookmarkedIds(): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[BOOKMARKED_IDS_KEY] ?: emptySet() }

    override suspend fun addBookmark(exhibitionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[BOOKMARKED_IDS_KEY] ?: emptySet()
            prefs[BOOKMARKED_IDS_KEY] = current + exhibitionId
        }
        mutationListener?.invoke()
    }

    override suspend fun removeBookmark(exhibitionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[BOOKMARKED_IDS_KEY] ?: emptySet()
            prefs[BOOKMARKED_IDS_KEY] = current - exhibitionId
        }
        mutationListener?.invoke()
    }

    override suspend fun isBookmarked(exhibitionId: String): Boolean =
        (dataStore.data.first()[BOOKMARKED_IDS_KEY] ?: emptySet()).contains(exhibitionId)

    override suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.remove(BOOKMARKED_IDS_KEY) }
        mutationListener?.invoke()
    }
}
```

- [ ] **Step 3: Update FakeBookmarkRepository to satisfy the interface**

Edit `shared/src/commonTest/kotlin/com/gallr/shared/repository/FakeBookmarkRepository.kt`:

```kotlin
package com.gallr.shared.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeBookmarkRepository(initial: Set<String> = emptySet()) : BookmarkRepository {
    private val state = MutableStateFlow(initial)
    private var listener: (suspend () -> Unit)? = null

    override fun setMutationListener(listener: suspend () -> Unit) {
        this.listener = listener
    }
    override fun observeBookmarkedIds(): Flow<Set<String>> = state.asStateFlow()
    override suspend fun addBookmark(exhibitionId: String) {
        state.value = state.value + exhibitionId
        listener?.invoke()
    }
    override suspend fun removeBookmark(exhibitionId: String) {
        state.value = state.value - exhibitionId
        listener?.invoke()
    }
    override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in state.value
    override suspend fun clearAll() {
        state.value = emptySet()
        listener?.invoke()
    }
}
```

- [ ] **Step 4: Verify other implementers of BookmarkRepository compile**

Run: `grep -rn "BookmarkRepository\b" shared/src composeApp/src --include="*.kt" -l | xargs grep -l ": BookmarkRepository"`

Expected: BookmarkRepositoryImpl + CloudBookmarkRepository + SyncBookmarkRepository (per App.kt imports). Update each to add a no-op `setMutationListener` if they don't delegate. Specifically:

Open `CloudBookmarkRepository.kt` and `SyncBookmarkRepository.kt`. Add:

```kotlin
override fun setMutationListener(listener: suspend () -> Unit) {
    // Local-only feature in v1; cloud sync notifications via local repo only
    // (or, for SyncBookmarkRepository, delegate to the local repo)
}
```

For `SyncBookmarkRepository`, delegate to the local repo:

```kotlin
override fun setMutationListener(listener: suspend () -> Unit) {
    localRepo.setMutationListener(listener)
}
```

- [ ] **Step 5: Run all shared tests**

Run: `./gradlew :shared:allTests`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add shared/src/
git commit -m "feat(notifications): add mutation listener to BookmarkRepository"
```

---

## Task 8: Android — NotificationConstants + ScheduledIdIndex (DataStore)

**Files:**
- Create: `shared/src/androidMain/kotlin/com/gallr/shared/notifications/NotificationConstants.kt`
- Create: `shared/src/androidMain/kotlin/com/gallr/shared/notifications/ScheduledIdIndex.kt`

- [ ] **Step 1: Create NotificationConstants.kt**

Create `shared/src/androidMain/kotlin/com/gallr/shared/notifications/NotificationConstants.kt`:

```kotlin
package com.gallr.shared.notifications

object NotificationConstants {
    const val CHANNEL_ID = "gallr_reminders"
    const val CHANNEL_NAME = "Reminders"
    const val EXTRA_NOTIFICATION_ID = "gallr.notification.id"
    const val EXTRA_TITLE = "gallr.notification.title"
    const val EXTRA_BODY = "gallr.notification.body"
    const val EXTRA_DEEPLINK_TYPE = "gallr.notification.deeplink.type"
    const val EXTRA_DEEPLINK_EXHIBITION_ID = "gallr.notification.deeplink.exhibitionId"

    const val DEEPLINK_EXHIBITION = "exhibition"
    const val DEEPLINK_MYLIST = "mylist"
}
```

- [ ] **Step 2: Create ScheduledIdIndex backed by DataStore**

Create `shared/src/androidMain/kotlin/com/gallr/shared/notifications/ScheduledIdIndex.kt`:

```kotlin
package com.gallr.shared.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

private val SCHEDULED_NOTIFICATION_IDS = stringSetPreferencesKey("scheduled_notification_ids")

class ScheduledIdIndex(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun getAll(): Set<String> =
        dataStore.data.first()[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()

    suspend fun add(id: String) {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = (prefs[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()) + id
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = (prefs[SCHEDULED_NOTIFICATION_IDS] ?: emptySet()) - id
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs[SCHEDULED_NOTIFICATION_IDS] = emptySet()
        }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add shared/src/androidMain/kotlin/com/gallr/shared/notifications/
git commit -m "feat(notifications): add Android NotificationConstants + ScheduledIdIndex"
```

---

## Task 9: Android — NotificationReceiver

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/gallr/app/notifications/NotificationReceiver.kt`
- Create: `composeApp/src/androidMain/res/drawable/ic_notification.xml`

The receiver lives in `composeApp/src/androidMain` (not `shared`) because it needs access to the app's `R.drawable.ic_notification`.

- [ ] **Step 1: Write the receiver**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/notifications/NotificationReceiver.kt`:

```kotlin
package com.gallr.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gallr.app.R
import com.gallr.shared.notifications.NotificationConstants

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(NotificationConstants.EXTRA_NOTIFICATION_ID) ?: return
        val title = intent.getStringExtra(NotificationConstants.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(NotificationConstants.EXTRA_BODY) ?: return
        val deepLinkType = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE)
        val exhibitionId = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID)

        ensureChannel(context)

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, "com.gallr.app.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, deepLinkType)
            putExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID, exhibitionId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between schedule and fire — silent drop
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NotificationConstants.CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NotificationConstants.CHANNEL_ID,
                NotificationConstants.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
        }
    }
}
```

- [ ] **Step 2: Create ic_notification.xml drawable**

Create `composeApp/src/androidMain/res/drawable/ic_notification.xml`. This must be a 24dp monochrome white-fill vector (Android system tints it). Use the existing `logo` vector path as the source. Open the existing logo:

Run: `find composeApp/src -name "logo.xml" -not -path "*/build/*"`

If found, copy its `<path android:pathData="..."/>` value. Create:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="<COPY_FROM_logo_xml_BUT_RESCALE_TO_24x24_VIEWPORT>"/>
</vector>
```

If the existing `logo` is at viewport 108×108, the simplest correct rescaling is to use the SAME pathData — the `viewportWidth/Height` we set determines the coordinate space. So if you set `viewportWidth="108"` and `viewportHeight="108"` instead of `"24"`, the existing path data works as-is. Updated form:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="<COPY_VERBATIM_FROM_logo.xml>"/>
</vector>
```

If `logo.xml` does not exist (i.e. logo is a PNG or compose-resources asset), use a simple architectural arch icon as fallback — a `M12,4 A8,8 0 0,1 20,12 L20,20 L18,20 L18,12 A6,6 0 0,0 6,12 L6,20 L4,20 L4,12 A8,8 0 0,1 12,4 Z` is a reasonable arch silhouette (verify visually with Android Studio's vector preview).

- [ ] **Step 3: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:processDebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/notifications/NotificationReceiver.kt \
        composeApp/src/androidMain/res/drawable/ic_notification.xml
git commit -m "feat(notifications): add Android NotificationReceiver with deep-link extras"
```

---

## Task 10: Android — AndroidNotificationScheduler implementation

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/gallr/app/notifications/AndroidNotificationScheduler.kt`

(Note: also moved from `shared/androidMain/notifications/NotificationScheduler.android.kt` to `composeApp/src/androidMain` because it needs `R` access via Receiver. Keep the import path for consumers via `com.gallr.app.notifications`.)

- [ ] **Step 1: Write the scheduler implementation**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/notifications/AndroidNotificationScheduler.kt`:

```kotlin
package com.gallr.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationConstants
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSpec
import com.gallr.shared.notifications.ScheduledIdIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val WINDOW_MS = 10 * 60 * 1000L  // 10-minute window

class AndroidNotificationScheduler(
    private val context: Context,
    private val index: ScheduledIdIndex,
    private val permissionRequester: NotificationPermissionRequester,
) : NotificationScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return permissionRequester.request()
    }

    override suspend fun schedule(spec: NotificationSpec) {
        val pi = buildPendingIntent(spec)
        val triggerAtMs = spec.triggerAt.toEpochMilliseconds()
        // Use setWindow (inexact) — avoids SCHEDULE_EXACT_ALARM grant on Android 14+.
        // Window: [triggerAt - 5min, triggerAt + 5min].
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs - WINDOW_MS / 2,
            WINDOW_MS,
            pi,
        )
        index.add(spec.id)
    }

    override suspend fun cancel(id: String) {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pi)
        index.remove(id)
    }

    override suspend fun cancelAll() {
        for (id in index.getAll()) {
            cancel(id)
        }
        index.clear()
    }

    override suspend fun scheduledIds(): Set<String> = index.getAll()

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink

    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    private fun buildPendingIntent(spec: NotificationSpec): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(NotificationConstants.EXTRA_NOTIFICATION_ID, spec.id)
            putExtra(NotificationConstants.EXTRA_TITLE, spec.title)
            putExtra(NotificationConstants.EXTRA_BODY, spec.body)
            when (val link = spec.deepLink) {
                is DeepLink.Exhibition -> {
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, NotificationConstants.DEEPLINK_EXHIBITION)
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID, link.id)
                }
                is DeepLink.MyList -> {
                    putExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE, NotificationConstants.DEEPLINK_MYLIST)
                }
            }
        }
        return PendingIntent.getBroadcast(
            context, spec.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

interface NotificationPermissionRequester {
    suspend fun request(): Boolean
}
```

- [ ] **Step 2: Create ActivityResult-backed NotificationPermissionRequester**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/notifications/ActivityNotificationPermissionRequester.kt`:

```kotlin
package com.gallr.app.notifications

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ActivityNotificationPermissionRequester(
    activity: ComponentActivity,
) : NotificationPermissionRequester {

    private var pending: Continuation<Boolean>? = null

    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.resume(granted)
            pending = null
        }

    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return suspendCoroutine { cont ->
            pending = cont
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/notifications/
git commit -m "feat(notifications): add Android scheduler + permission requester"
```

---

## Task 11: Android — AndroidManifest changes

**Files:**
- Modify: `composeApp/src/androidMain/AndroidManifest.xml`

- [ ] **Step 1: Add permission and receiver**

Open `composeApp/src/androidMain/AndroidManifest.xml`. Add inside `<manifest>` (top-level), before `<application>`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

Inside `<application>...</application>`, add:

```xml
<receiver
    android:name="com.gallr.app.notifications.NotificationReceiver"
    android:exported="false"
    android:enabled="true"/>
```

- [ ] **Step 2: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/AndroidManifest.xml
git commit -m "feat(notifications): declare POST_NOTIFICATIONS + receiver"
```

---

## Task 12: iOS — IosNotificationScheduler

**Files:**
- Create: `shared/src/iosMain/kotlin/com/gallr/shared/notifications/IosNotificationScheduler.kt`
- Create: `shared/src/iosMain/kotlin/com/gallr/shared/notifications/NotificationDelegate.kt`

- [ ] **Step 1: Write IosNotificationScheduler.kt**

Create `shared/src/iosMain/kotlin/com/gallr/shared/notifications/IosNotificationScheduler.kt`:

```kotlin
package com.gallr.shared.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
class IosNotificationScheduler : NotificationScheduler {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun hasPermission(): Boolean = suspendCancellableCoroutine { cont ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            cont.resume(
                status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional,
            )
        }
    }

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            cont.resume(granted)
        }
    }

    override suspend fun schedule(spec: NotificationSpec) = suspendCancellableCoroutine<Unit> { cont ->
        val content = UNMutableNotificationContent().apply {
            setTitle(spec.title)
            setBody(spec.body)
            setUserInfo(buildDeepLinkUserInfo(spec.deepLink))
        }

        val date = NSDate.dateWithTimeIntervalSince1970(spec.triggerAt.toEpochMilliseconds() / 1000.0)
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute,
            date,
        )
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, false)

        val request = UNNotificationRequest.requestWithIdentifier(spec.id, content, trigger)
        center.addNotificationRequest(request) { _ -> cont.resume(Unit) }
    }

    override suspend fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    override suspend fun cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    override suspend fun scheduledIds(): Set<String> = suspendCancellableCoroutine { cont ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            @Suppress("UNCHECKED_CAST")
            val list = requests as? List<UNNotificationRequest> ?: emptyList()
            cont.resume(list.map { it.identifier }.toSet())
        }
    }

    private val _pendingDeepLink = MutableStateFlow<DeepLink?>(null)
    override val pendingDeepLink: StateFlow<DeepLink?> = _pendingDeepLink
    override fun setPendingDeepLink(link: DeepLink) { _pendingDeepLink.value = link }
    override fun consumePendingDeepLink() { _pendingDeepLink.value = null }

    private fun buildDeepLinkUserInfo(link: DeepLink): Map<Any?, Any?> = when (link) {
        is DeepLink.Exhibition -> mapOf(
            "deepLinkType" to "exhibition",
            "exhibitionId" to link.id,
        )
        is DeepLink.MyList -> mapOf("deepLinkType" to "mylist")
    }
}
```

- [ ] **Step 2: Write NotificationDelegate.kt**

Create `shared/src/iosMain/kotlin/com/gallr/shared/notifications/NotificationDelegate.kt`:

```kotlin
package com.gallr.shared.notifications

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class NotificationDelegate(
    private val scheduler: IosNotificationScheduler,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        val type = userInfo["deepLinkType"] as? String
        val link = when (type) {
            "exhibition" -> {
                val id = userInfo["exhibitionId"] as? String
                if (id != null) DeepLink.Exhibition(id) else DeepLink.MyList
            }
            "mylist" -> DeepLink.MyList
            else -> null
        }
        link?.let { scheduler.setPendingDeepLink(it) }
        withCompletionHandler()
    }

    // Show notifications even when the app is in foreground
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: platform.UserNotifications.UNNotification,
        withCompletionHandler: (platform.UserNotifications.UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
    }
}
```

- [ ] **Step 3: Verify iOS compile**

Run: `./gradlew :shared:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL. If platform API resolution fails, Kotlin/Native may need explicit `-Xopt-in` for `kotlinx.cinterop.ExperimentalForeignApi` — already declared via `@OptIn`.

- [ ] **Step 4: Commit**

```bash
git add shared/src/iosMain/kotlin/com/gallr/shared/notifications/
git commit -m "feat(notifications): add iOS scheduler + delegate"
```

---

## Task 13: NotificationPermissionHandler composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/notifications/NotificationPermissionStrings.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/notifications/NotificationPermissionHandler.kt`

- [ ] **Step 1: Bilingual strings**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/notifications/NotificationPermissionStrings.kt`:

```kotlin
package com.gallr.app.notifications

import com.gallr.shared.data.model.AppLanguage

data class NotificationPermissionStrings(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String,
)

fun notificationPermissionStrings(lang: AppLanguage): NotificationPermissionStrings = when (lang) {
    AppLanguage.EN -> NotificationPermissionStrings(
        title = "Get reminders for your saved exhibitions",
        body = "We'll let you know when bookmarked exhibitions are closing soon, opening soon, or hosting a reception today.",
        confirm = "Enable",
        dismiss = "Not now",
    )
    AppLanguage.KO -> NotificationPermissionStrings(
        title = "저장한 전시 알림 받기",
        body = "북마크한 전시가 곧 마감되거나 개막되거나 오프닝 리셉션이 열릴 때 알려드릴게요.",
        confirm = "켜기",
        dismiss = "다음에",
    )
}
```

- [ ] **Step 2: Permission state holder + composable**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/notifications/NotificationPermissionHandler.kt`:

```kotlin
package com.gallr.app.notifications

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import kotlinx.coroutines.launch

/**
 * Mount once near the top of App(). Shows a contextual rationale dialog the
 * first time bookmarkMutationCount increments and permission is unknown.
 *
 * The host wires bookmarkMutationCount via an effect that increments on every
 * BookmarkRepository mutation.
 */
@Composable
fun NotificationPermissionHandler(
    scheduler: NotificationScheduler,
    syncService: NotificationSyncService,
    bookmarkMutationCount: Int,
    permissionPrompted: Boolean,
    onPrompted: () -> Unit,
    language: AppLanguage,
) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookmarkMutationCount) {
        if (bookmarkMutationCount == 0) return@LaunchedEffect
        if (permissionPrompted) return@LaunchedEffect
        if (scheduler.hasPermission()) return@LaunchedEffect
        showDialog = true
    }

    if (showDialog) {
        val s = remember(language) { notificationPermissionStrings(language) }
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onPrompted()
            },
            title = { Text(s.title) },
            text = { Text(s.body) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val granted = scheduler.requestPermission()
                        if (granted) syncService.sync()
                        showDialog = false
                        onPrompted()
                    }
                }) { Text(s.confirm) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    onPrompted()
                }) { Text(s.dismiss) }
            },
        )
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/notifications/
git commit -m "feat(notifications): add bilingual permission dialog composable"
```

---

## Task 14: Persist permission-prompted flag in DataStore

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/repository/NotificationPreferences.kt`

- [ ] **Step 1: Write the preferences wrapper**

Create `shared/src/commonMain/kotlin/com/gallr/shared/repository/NotificationPreferences.kt`:

```kotlin
package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")

class NotificationPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    fun observePermissionPrompted(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PERMISSION_PROMPTED] ?: false }

    suspend fun setPermissionPrompted() {
        dataStore.edit { prefs -> prefs[PERMISSION_PROMPTED] = true }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/repository/NotificationPreferences.kt
git commit -m "feat(notifications): add NotificationPreferences for prompted flag"
```

---

## Task 15: Wire scheduler + sync + permission handler into App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

- [ ] **Step 1: Add parameters**

Edit `App(...)` signature in `App.kt`. Add:

```kotlin
notificationScheduler: NotificationScheduler,
notificationSyncService: NotificationSyncService,
notificationPreferences: NotificationPreferences,
```

Add imports near the top:

```kotlin
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationScheduler
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.repository.NotificationPreferences
import com.gallr.app.notifications.NotificationPermissionHandler
```

- [ ] **Step 2: Wire mutation listener for sync**

Inside `App(...)`, after `syncBookmarkRepository` is constructed, add:

```kotlin
val syncScope = rememberCoroutineScope()
LaunchedEffect(Unit) {
    syncBookmarkRepository.setMutationListener {
        notificationSyncService.sync()
    }
}
```

(`LaunchedEffect(Unit)` runs once per composition entry. Use `rememberCoroutineScope()` for the listener body.)

- [ ] **Step 3: Sync on first Success featuredState**

Add another `LaunchedEffect` near the bookmark listener:

```kotlin
LaunchedEffect(Unit) {
    viewModel.featuredState
        .first { it is com.gallr.app.viewmodel.ExhibitionListState.Success }
    notificationSyncService.sync()
}
```

- [ ] **Step 4: Mount NotificationPermissionHandler**

After other LaunchedEffects, add bookmark mutation count tracking:

```kotlin
var bookmarkMutationCount by remember { mutableStateOf(0) }
LaunchedEffect(Unit) {
    syncBookmarkRepository.observeBookmarkedIds().collect {
        bookmarkMutationCount += 1
    }
}
val permissionPrompted by notificationPreferences.observePermissionPrompted()
    .collectAsState(initial = false)
val coroutineScope = rememberCoroutineScope()
NotificationPermissionHandler(
    scheduler = notificationScheduler,
    syncService = notificationSyncService,
    bookmarkMutationCount = bookmarkMutationCount,
    permissionPrompted = permissionPrompted,
    onPrompted = { coroutineScope.launch { notificationPreferences.setPermissionPrompted() } },
    language = lang,
)
```

(Note: place AFTER `lang` is collected from `viewModel.language`.)

- [ ] **Step 5: Wire pendingDeepLink → navigation**

After the rest, add:

```kotlin
LaunchedEffect(Unit) {
    notificationScheduler.pendingDeepLink.collect { link ->
        when (link) {
            is DeepLink.Exhibition -> {
                val all = (viewModel.featuredState.value as? com.gallr.app.viewmodel.ExhibitionListState.Success)?.exhibitions
                    ?: emptyList()
                val target = all.firstOrNull { it.id == link.id }
                if (target != null) {
                    selectedExhibition = target
                } else {
                    selectedTab = MY_LIST_TAB_INDEX  // fallback
                }
                notificationScheduler.consumePendingDeepLink()
            }
            is DeepLink.MyList -> {
                selectedTab = MY_LIST_TAB_INDEX
                notificationScheduler.consumePendingDeepLink()
            }
            null -> Unit
        }
    }
}
```

`MY_LIST_TAB_INDEX` is a constant for the My List tab. Find it in `App.kt` (look for the existing tab navigation; the My List tab is likely tab index 1 — verify via `selectedTab = 0` and the `BottomNavBar` composable).

If no constant exists, define one near the top of `App.kt`:

```kotlin
private const val FEATURED_TAB_INDEX = 0
private const val MY_LIST_TAB_INDEX = 1  // verify against existing tab order
```

- [ ] **Step 6: Verify compile**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(notifications): wire scheduler/sync/permission/deep-link into App"
```

---

## Task 16: Wire scheduler construction in MainActivity (Android)

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`

- [ ] **Step 1: Construct scheduler + sync service + extract deep-link from intent**

Add imports:

```kotlin
import com.gallr.app.notifications.ActivityNotificationPermissionRequester
import com.gallr.app.notifications.AndroidNotificationScheduler
import com.gallr.shared.notifications.DeepLink
import com.gallr.shared.notifications.NotificationConstants
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.notifications.ScheduledIdIndex
import com.gallr.shared.repository.NotificationPreferences
```

In `onCreate(...)`, after the existing repository construction:

```kotlin
val scheduledIdIndex = ScheduledIdIndex(dataStore)
val permissionRequester = ActivityNotificationPermissionRequester(this)
val notificationScheduler = AndroidNotificationScheduler(
    context = applicationContext,
    index = scheduledIdIndex,
    permissionRequester = permissionRequester,
)
val notificationSyncService = NotificationSyncService(
    scheduler = notificationScheduler,
    exhibitionRepo = exhibitionRepository,
    bookmarkRepo = localBookmarkRepository,
    languageRepo = languageRepository,
)
val notificationPreferences = NotificationPreferences(dataStore)
```

- [ ] **Step 2: Extract deep-link from launch intent**

Add a private helper at the bottom of `MainActivity`:

```kotlin
private fun extractDeepLink(intent: android.content.Intent): com.gallr.shared.notifications.DeepLink? {
    val type = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_TYPE)
    return when (type) {
        NotificationConstants.DEEPLINK_EXHIBITION -> {
            val id = intent.getStringExtra(NotificationConstants.EXTRA_DEEPLINK_EXHIBITION_ID)
            if (id != null) DeepLink.Exhibition(id) else DeepLink.MyList
        }
        NotificationConstants.DEEPLINK_MYLIST -> DeepLink.MyList
        else -> null
    }
}
```

In `onCreate`, after constructing `notificationScheduler`:

```kotlin
extractDeepLink(intent)?.let { notificationScheduler.setPendingDeepLink(it) }
```

In `onNewIntent(intent)`, AFTER the existing super call and OAuth handler:

```kotlin
extractDeepLink(intent)?.let { notificationScheduler.setPendingDeepLink(it) }
```

(Note: the `notificationScheduler` reference must be a class field, not a local in `onCreate`. Add `private lateinit var notificationScheduler: AndroidNotificationScheduler` at the top of MainActivity, and assign it in `onCreate`.)

- [ ] **Step 3: Pass into App()**

Update the `App(...)` call:

```kotlin
App(
    // ... existing params ...
    notificationScheduler = notificationScheduler,
    notificationSyncService = notificationSyncService,
    notificationPreferences = notificationPreferences,
)
```

- [ ] **Step 4: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
git commit -m "feat(notifications): wire AndroidNotificationScheduler in MainActivity"
```

---

## Task 17: Wire scheduler construction in MainViewController (iOS)

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`

- [ ] **Step 1: Construct scheduler + delegate**

Add imports:

```kotlin
import com.gallr.shared.notifications.IosNotificationScheduler
import com.gallr.shared.notifications.NotificationDelegate
import com.gallr.shared.notifications.NotificationSyncService
import com.gallr.shared.repository.NotificationPreferences
import platform.UserNotifications.UNUserNotificationCenter
```

Inside `MainViewController(...)` factory:

```kotlin
val notificationScheduler = IosNotificationScheduler()
val notificationDelegate = NotificationDelegate(notificationScheduler)
UNUserNotificationCenter.currentNotificationCenter().setDelegate(notificationDelegate)
val notificationSyncService = NotificationSyncService(
    scheduler = notificationScheduler,
    exhibitionRepo = exhibitionRepository,
    bookmarkRepo = localBookmarkRepository,
    languageRepo = languageRepository,
)
val notificationPreferences = NotificationPreferences(dataStore)
```

(Note: keep a strong reference to `notificationDelegate` — store it module-level to prevent GC. Add at the top of the file: `private var _notificationDelegate: NotificationDelegate? = null` and assign `_notificationDelegate = notificationDelegate` after construction.)

- [ ] **Step 2: Pass into App()**

Update the `App(...)` call:

```kotlin
App(
    // ... existing params ...
    notificationScheduler = notificationScheduler,
    notificationSyncService = notificationSyncService,
    notificationPreferences = notificationPreferences,
)
```

- [ ] **Step 3: Build for iOS**

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
git commit -m "feat(notifications): wire IosNotificationScheduler in MainViewController"
```

---

## Task 18: Manual verification — Android (permission, triggers, taps)

This is non-code QA. Follow these steps end-to-end.

- [ ] **Step 1: Permission flow — fresh install**

```
adb uninstall com.gallr.app
./gradlew :composeApp:installDebug
adb shell am start -W com.gallr.app/.MainActivity
```

In the app:
1. Navigate to any exhibition
2. Tap bookmark
3. Rationale dialog appears (correct language)
4. Tap **Enable** → OS sheet → tap **Allow**
5. Verify scheduled notifications:

```
adb shell dumpsys alarm | grep -A 5 "com.gallr.app"
```

Expected: alarms scheduled for the bookmarked exhibition's triggers.

- [ ] **Step 2: Permission denied — rationale**

Force-quit app, clear data, re-launch.

```
adb shell pm clear com.gallr.app
adb shell am start -W com.gallr.app/.MainActivity
```

Bookmark something → rationale dialog appears → tap **Not now**. Expected: NO OS sheet shown, no scheduled alarms. Bookmark again → dialog does NOT re-appear (prompted flag persists).

- [ ] **Step 3: Permission denied — OS sheet**

Clear data, re-launch. Bookmark → tap Enable → OS sheet appears → tap **Don't allow**. Expected: no scheduled alarms, no re-prompt on next mutation.

- [ ] **Step 4: Closing trigger fires**

Bookmark an exhibition closing in 4 days. Set device clock to 09:00 of trigger day:

```
adb shell date -s "20260518.090000"   # adjust per trigger day; root or emulator only
```

Wait up to 10 minutes (setWindow inexact). Expected: notification appears with correct title + body. Tap → app opens → lands on exhibition's detail screen.

- [ ] **Step 5: Notification tap deep-link fallback**

While there's a scheduled notification, manually delete the corresponding exhibition from data (use Supabase test instance, set `published = false` or delete row). Wait for notification to fire, tap it. Expected: app opens → falls back to My List tab.

- [ ] **Step 6: Inactivity notification fires after 7 days**

Bookmark something. Advance device clock 7 days. Wait for 09:00. Expected: inactivity notification appears with correct copy. Tap → opens to My List tab.

- [ ] **Step 7: No commit needed (QA only)**

---

## Task 19: Manual verification — iOS (permission, triggers, taps)

- [ ] **Step 1: Permission flow — fresh install (simulator)**

```
xcrun simctl uninstall booted com.gallr.app
# Build & run from Xcode: ⌘R
```

In the app: Bookmark something → rationale dialog appears → tap **Enable** → OS sheet → tap **Allow**.

Verify scheduled requests:

```
xcrun simctl get_app_container booted com.gallr.app data
# (Inspect; or trigger a notification via Xcode → Debug → Simulate Background Fetch)
```

Or use the Console app to inspect `usernotificationsd` logs.

- [ ] **Step 2: Permission denied — rationale**

Reset app data (uninstall + reinstall). Bookmark → tap **Not now**. Expected: no OS sheet, no scheduled requests. No re-prompt on next mutation.

- [ ] **Step 3: Permission denied — OS sheet**

Reset, bookmark → tap Enable → OS sheet → tap **Don't Allow**. Expected: no requests scheduled. No re-prompt.

- [ ] **Step 4: Closing trigger fires**

Bookmark an exhibition closing in 4 days. Use Xcode's Simulator → Features → Trigger Notification, OR set the trigger date to a few seconds in the future for testing. Expected: notification appears with correct content. Tap → app opens → exhibition detail.

- [ ] **Step 5: Inactivity fires**

Same approach: schedule one with `triggerAt` a few seconds in the future. Tap → My List tab.

- [ ] **Step 6: Notification tap when app is force-quit**

Schedule a notification for ~30s in the future. Force-quit the app (swipe up in app switcher). Wait for notification. Tap. Expected: app cold-launches and lands on the deep-linked screen.

- [ ] **Step 7: No commit needed (QA only)**

---

## Self-review checklist

- [ ] Spec §3 (Decisions): all locked decisions implemented:
  - Q1 09:00 fire time → `FIRE_TIME` constant in TriggerRules (Task 3)
  - Q2 inactivity once-only → no re-fire logic in NotificationSyncService (Task 6) — only mutation re-arms
  - Q3 sync after first Success + past-due skipped → App.kt LaunchedEffect (Task 15) + TriggerRules.computeTriggers filter (Task 3)
  - Q4 deep-link with My List fallback → NotificationReceiver (Task 9 Android) + NotificationDelegate (Task 12 iOS) + App.kt routing (Task 15)
  - Android setWindow ±5min → AndroidNotificationScheduler (Task 10)
- [ ] Spec §4 architecture: all 4 commonMain components (TriggerRules, NotificationSyncService, NotificationContent, NotificationScheduler interface) created; both platform actuals (Android in Task 10, iOS in Task 12); permission handler (Task 13).
- [ ] Spec §5 file list reflected in plan File Structure.
- [ ] Spec §6 edge cases covered:
  - No bookmarks → cancelAll (Task 6 test)
  - Permission denied → no schedule (Task 6 test)
  - Past-due → skipped (Task 3 test)
  - Exhibition removed → tap fallback to My List (Task 15 routing)
  - Idempotency → Task 6 test
  - App force-quit + notification fires → manual Task 19 step 6
- [ ] Spec §7 testing: TriggerRules 8 tests, NotificationSyncService 8 tests, NotificationContent 8 tests, FakeNotificationScheduler exists, manual end-to-end steps for both platforms.
- [ ] No "TBD"/"add appropriate"/etc. placeholders.
- [ ] Type consistency:
  - `BookmarkRepository.addBookmark` / `removeBookmark` (not `add`/`remove`) — used correctly in Task 7
  - `ExhibitionRepository.getExhibitions(): Result<List<Exhibition>>` — used correctly in Task 6 with `.getOrElse { emptyList() }`
  - `NotificationScheduler` is an interface (Task 5 refactored from expect)
  - `notificationId(exhibitionId, type)` helper used consistently
  - `INACTIVITY_NOTIFICATION_ID` constant used consistently
- [ ] All listed files match across plan sections.

## Known caveats flagged for the implementer

1. **Android `R` access from shared:** Resolved by relocating `NotificationReceiver` + `AndroidNotificationScheduler` to `composeApp/src/androidMain` (Task 9 step 3). Documented in plan.
2. **iOS storyboard launch flash on dark mode first launch:** Documented in spec §7; not addressed by this plan.
3. **Language switch mid-cycle:** Scheduled notifications keep schedule-time language. Documented in spec §3 as v1 limitation.
4. **`SCHEDULE_EXACT_ALARM` permission avoided:** Used `setWindow` with 10min window — fires within ±5min of 09:00.
5. **Notification small icon:** Task 9 step 2 creates `ic_notification.xml` from the existing `logo.xml` path data. Verify visually in Android Studio's vector preview. If the silhouette doesn't render well at 24dp, hand-author a simpler monochrome arch.
