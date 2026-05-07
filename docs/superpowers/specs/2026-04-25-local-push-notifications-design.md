# Local Push Notifications — Design Spec

**Date:** 2026-04-25
**Status:** Design (approved, pending implementation plan)
**Source brief:** `260424-local-push-notifications-p2.md`
**Priority:** P2

---

## 1. Problem

gallr has no re-engagement mechanism. Users bookmark exhibitions, then forget — closing dates pass, openings start without them, reception nights happen quietly. v1 adds **on-device** local notifications for time-sensitive reminders tied to bookmarked exhibitions. No backend; remote push (FCM/APNs) is deferred to v2.

## 2. Outcome

When a user bookmarks an exhibition, the app schedules up to three local notifications:
- "Closing in 3 days" — fires 09:00 device time, 3 days before `closingDate`
- "Opening in 3 days" — fires 09:00, 3 days before `openingDate`
- "Reception today at [venue]" — fires 09:00 on `receptionDate`

Plus a single rolling "list inactivity" reminder 7 days after the user's last bookmark mutation.

Tapping a notification deep-links to the relevant exhibition (or My List for inactivity), with My List as fallback if the exhibition is missing.

## 3. Decisions locked from brainstorm

| Question | Decision |
|---|---|
| Q1 — Fire time | **09:00 local device time** for all morning-of triggers. Fixed; no per-user setting (notification settings screen is explicitly out of scope per brief). |
| Q2 — Inactivity re-fire behavior | **Option 3** — fire once after 7d of no bookmark mutation. After firing, do not auto-reschedule. Only a bookmark mutation re-arms the timer. |
| Q3 — Cold-start sync timing + past-due triggers | **A1 + B1** — sync runs after first successful exhibition fetch (if `featuredState` becomes `Success`); skipped on `Error`, retried next launch. **No offline-cache layer.** Past-due triggers are skipped (never schedule for moments already passed). |
| Q4 — Notification tap behavior | **Option C** — deep-link to exhibition detail; fall back to My List if the exhibition is missing/removed. Inactivity notifications always land on My List. |
| Android exact-alarm permission | Use `AlarmManager.setWindow` with 10-min window centered on 09:00. Avoids `SCHEDULE_EXACT_ALARM` user-grant requirement on Android 14+. ±5min on a calendar reminder is fine. |
| Language switch mid-cycle | **Accept v1 limitation:** scheduled notifications keep the language they were rendered with at schedule-time. Switching language does not re-render in-flight notifications. Document for users; optional v1.x enhancement to cancelAll + sync on language change. |

## 4. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  composeApp/commonMain                                          │
│                                                                 │
│  NotificationPermissionHandler                                  │
│  - Composable + state holder                                    │
│  - Triggered by BookmarkViewModel on first add (or first        │
│    add/remove if user already has bookmarks pre-feature)        │
│  - Pre-permission rationale dialog → calls                      │
│    NotificationScheduler.requestPermission()                    │
└─────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────┐
│  shared/commonMain/notifications                                │
│                                                                 │
│  NotificationSyncService (the brain — called from               │
│    BookmarkRepository post-mutation + App startup post-fetch)   │
│  - sync(): permission gate → desired set → diff → schedule/cancel
│  - Idempotent; safe to call repeatedly                          │
│                                                                 │
│  TriggerRules (pure functions)                                  │
│  - computeTriggers(exhibition, now, tz): List<Trigger>          │
│  - Skips past-due                                               │
│                                                                 │
│  NotificationContent                                            │
│  - Bilingual templates (4 trigger types × 2 langs)              │
│  - Resolved at schedule-time using current LanguageRepository   │
│                                                                 │
│  NotificationScheduler  (expect interface)                      │
│  - hasPermission, requestPermission                             │
│  - schedule(spec), cancel(id), cancelAll, scheduledIds          │
│  - pendingDeepLink: StateFlow<DeepLink?>                        │
└─────────────────────────────────────────────────────────────────┘
              │
        ┌─────┴─────┐
        ▼           ▼
┌──────────────┐  ┌──────────────────────┐
│ androidMain  │  │ iosMain              │
│              │  │                      │
│ AlarmManager │  │ UNUserNotification-  │
│ + Notif-     │  │ Center               │
│ Compat       │  │                      │
│ + DataStore  │  │ Native pending list  │
│ id index     │  │ is the source        │
│              │  │                      │
│ Receiver →   │  │ Delegate →           │
│ deep-link    │  │ deep-link state      │
└──────────────┘  └──────────────────────┘
```

### Diff algorithm (`NotificationSyncService.sync()`)

```
1. if !scheduler.hasPermission(): return
2. bookmarks = bookmarkRepo.bookmarkedIds.value
3. if bookmarks.isEmpty():
     scheduler.cancelAll()
     return
4. exhibitions = exhibitionRepo.getByIds(bookmarks)  // already cached after first fetch
5. desired: Set<NotificationSpec> = mutableSetOf()
   for exhibition in exhibitions:
     desired += TriggerRules.computeTriggers(exhibition, now, tz)
   desired += inactivitySpec(now + 7d at 09:00)
6. existing = scheduler.scheduledIds()
7. for id in (existing - desired.ids): scheduler.cancel(id)
8. for spec in (desired - existing.ids): scheduler.schedule(spec)
```

### Notification ID scheme (stable, drives diff correctness)

```
{exhibitionId}_closing
{exhibitionId}_opening
{exhibitionId}_reception
inactivity                 (single global ID)
```

A bookmark already scheduled for "closing" reuses the same ID — diff treats it as a no-op. Removing a bookmark cancels by ID set: `{id}_closing`, `{id}_opening`, `{id}_reception`.

### Data flow — bookmark mutation

```
User taps bookmark
  → BookmarkRepository.add(exhibitionId)
  → DataStore write
  → emit new bookmarkedIds
  → BookmarkRepository launches NotificationSyncService.sync()
  → (if no permission yet) NotificationPermissionHandler dialog appears
       → on Enable, OS sheet → on grant, sync() runs again
  → diff produces 3 new schedules + 1 inactivity reschedule
```

### Data flow — cold start

```
App.onCreate
  → SplashController + featuredState collected (separate feature)
  → on first featuredState == Success:
       NotificationSyncService.sync()  // catches up missed schedules
  → on Error: skip; retry next launch
```

### Data flow — notification tap

```
[Android]
User taps notification
  → AlarmManager fires PendingIntent → NotificationReceiver
  → builds NotificationCompat.Builder with content PendingIntent
    carrying extras { deepLink: "exhibition/abc" or "mylist" }
  → user taps notification UI
  → MainActivity.onCreate / onNewIntent reads extras
  → scheduler.setPendingDeepLink(...)

[iOS]
User taps
  → UNUserNotificationCenterDelegate.didReceive(response)
  → reads userInfo { deepLink }
  → scheduler.setPendingDeepLink(...)

[Both — App.kt collects pendingDeepLink: StateFlow<DeepLink?>]
  → on emit:
     - DeepLink.Exhibition(id):
         exhibitionRepo.getById(id) → if exists: navigate to detail
                                    → if missing: navigate to My List (fallback)
     - DeepLink.MyList: switch to My List tab
  → scheduler.consumePendingDeepLink()
```

### Permission gate invariant

`NotificationSyncService.sync()` is a no-op when `scheduler.hasPermission() == false`.
**No permission = zero scheduled notifications, full stop.** When permission is granted, the next `sync()` call schedules the full desired set retroactively.

### Permission UX — pre-existing bookmarks at feature launch

User who already has 5 bookmarks before this feature ships:
- First launch with feature: `sync()` no-ops (no permission)
- App does NOT auto-prompt on cold start (brief explicit: no cold prompts)
- On their next bookmark add or remove → `NotificationPermissionHandler` rationale dialog shows
- If they grant permission, `sync()` runs and schedules the full set

If they never mutate, they never see the prompt. Intentional per platform guidelines.

## 5. Components & files

**New files:**

```
shared/src/commonMain/kotlin/com/gallr/shared/notifications/
├── NotificationScheduler.kt      expect interface
├── NotificationSyncService.kt    diff + reconcile
├── NotificationContent.kt        bilingual templates
├── TriggerRules.kt               pure: computeTriggers(...)
├── NotificationSpec.kt           data class
├── DeepLink.kt                   sealed class: Exhibition(id) | MyList
└── TriggerType.kt                enum: CLOSING, OPENING, RECEPTION, INACTIVITY

shared/src/androidMain/kotlin/com/gallr/shared/notifications/
├── NotificationScheduler.android.kt   AlarmManager + NotificationManagerCompat
├── ScheduledIdIndex.kt                DataStore-backed (Android needs it; iOS doesn't)
├── NotificationReceiver.kt            BroadcastReceiver — fires + posts builder
└── NotificationTapHandler.kt          extracts deep-link from PendingIntent extras

shared/src/iosMain/kotlin/com/gallr/shared/notifications/
├── NotificationScheduler.ios.kt       UNUserNotificationCenter wrapper
└── NotificationDelegate.kt            UNUserNotificationCenterDelegate

composeApp/src/commonMain/kotlin/com/gallr/app/notifications/
├── NotificationPermissionHandler.kt   composable + state holder
└── NotificationPermissionStrings.kt   bilingual rationale copy
```

**Modified files:**

```
shared/src/commonMain/kotlin/com/gallr/shared/repository/BookmarkRepositoryImpl.kt
  - Inject NotificationSyncService (constructor param, nullable for tests)
  - After add()/remove() DataStore write: launch sync() in a CoroutineScope

composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
  - Accept NotificationScheduler + NotificationSyncService params
  - Collect featuredState: on first Success → syncService.sync()
  - Collect scheduler.pendingDeepLink: route to ExhibitionDetail or MyList
  - Mount NotificationPermissionHandler composable

composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
  - Construct AndroidNotificationScheduler(context, scheduledIdIndex)
  - Construct NotificationSyncService(scheduler, exhibitionRepo, bookmarkRepo, languageRepo)
  - Pass into App()
  - In onCreate + onNewIntent: NotificationTapHandler.extract(intent)?.let {
      scheduler.setPendingDeepLink(it) }

composeApp/src/androidMain/AndroidManifest.xml
  - <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  - Register NotificationReceiver
  - (No SCHEDULE_EXACT_ALARM — using setWindow inexact)

composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
  - Construct IosNotificationScheduler()
  - Wire UNUserNotificationCenter.delegate = NotificationDelegate(scheduler)
  - Pass into App()
```

### Key contracts

```kotlin
// commonMain
expect class NotificationScheduler {
    suspend fun hasPermission(): Boolean
    suspend fun requestPermission(): Boolean   // shows OS sheet, returns granted
    suspend fun schedule(spec: NotificationSpec)
    suspend fun cancel(id: String)
    suspend fun cancelAll()
    suspend fun scheduledIds(): Set<String>
    val pendingDeepLink: StateFlow<DeepLink?>
    fun consumePendingDeepLink()
    fun setPendingDeepLink(link: DeepLink)  // platform code calls this
}

data class NotificationSpec(
    val id: String,
    val title: String,
    val body: String,
    val triggerAt: Instant,
    val deepLink: DeepLink,
)

sealed class DeepLink {
    data class Exhibition(val id: String) : DeepLink()
    object MyList : DeepLink()
}

class NotificationSyncService(
    private val scheduler: NotificationScheduler,
    private val exhibitionRepo: ExhibitionRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val languageRepo: LanguageRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    suspend fun sync()
}

object TriggerRules {
    fun computeTriggers(
        exhibition: Exhibition,
        now: Instant,
        timeZone: TimeZone,
    ): List<Trigger>
}
```

### Trigger time computation

```
triggerAt = LocalDate(targetDate).atTime(9, 0).toInstant(timeZone)

targetDate by trigger:
  CLOSING:    closingDate.minus(3, DAY)
  OPENING:    openingDate.minus(3, DAY)
  RECEPTION:  receptionDate          // the day of, at 09:00
  INACTIVITY: today.plus(7, DAY)
```

If `triggerAt < now`, the trigger is past-due and skipped.

### Notification copy

| Trigger | EN | KO |
|---|---|---|
| Closing | `[name] closes in 3 days — don't miss it.` | `[name] 마감 3일 전입니다. 놓치지 마세요.` |
| Opening | `[name] opens in 3 days.` | `[name] 개막 3일 전입니다.` |
| Reception | `Reception today at [venue].` | `오늘 [venue]에서 오프닝 리셉션이 열립니다.` |
| Inactivity | `Your list hasn't changed in a while — check what's closing soon.` | `마이 리스트를 업데이트한 지 꽤 됐어요. 곧 마감되는 전시를 확인해보세요.` |

Title for all: `gallr` (app name). Body: per table above.

### Platform implementation notes

**Android — `AlarmManager` + DataStore-backed id index:**
- `setWindow(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli() - 5min, 10min, pendingIntent)` — fires within ±5min of 09:00.
- `BroadcastReceiver` builds the `Notification` at fire-time so the localized string is current at fire-time, not schedule-time. Wait — this contradicts the "language at schedule-time" decision. **Clarification:** the bilingual *string* is rendered at schedule-time and stored in the `PendingIntent` extras. Receiver just posts the notification. Language switch mid-cycle stays a v1 limitation per the locked decision.
- **AlarmManager doesn't expose scheduled alarms**, so we maintain a `ScheduledIdIndex` in DataStore (set of currently-scheduled ids). Updated atomically with each schedule/cancel call. `scheduledIds()` reads from this index.
- `POST_NOTIFICATIONS` runtime permission required on Android 13+ (handled by `requestPermission`).

**iOS — `UNUserNotificationCenter`:**
- `UNCalendarNotificationTrigger(dateComponents = DateComponents(year, month, day, hour=9, minute=0), repeats=false)`.
- `UNNotificationRequest(identifier, content, trigger)` — fires precisely at 09:00.
- `getPendingNotificationRequests()` is the source of truth — no DataStore index needed.
- Persists across force-quit and reboot natively.

This Android/iOS asymmetry (DataStore index vs. native pending list) is hidden behind the `expect/actual` interface. Worth flagging in implementation plan.

### Permission dialog UX

`AlertDialog` with:
- **Title:** "Get reminders for your saved exhibitions" / "저장한 전시 알림 받기"
- **Body:** "We'll let you know when bookmarked exhibitions are closing soon, opening soon, or hosting a reception today." / (KO equivalent)
- **Primary:** "Enable" / "켜기" → `scheduler.requestPermission()` → OS sheet
- **Secondary:** "Not now" / "다음에" → dismiss

After either response, set DataStore key `notification_permission_prompted = true`. **Never show again** unless DataStore is cleared. Settings deep-link banner is explicitly v2 (out of scope per brief).

## 6. Edge cases

| Scenario | Behavior |
|---|---|
| User has no bookmarks | `sync()` calls `cancelAll()`, returns. No inactivity scheduled. |
| User adds first bookmark, denies permission | Dialog shown once, denied → no schedule. No re-prompt. |
| User adds bookmark before exhibition data fetched | Cold-start path: bookmark mutation triggers `sync()`, but `exhibitionRepo.getByIds()` uses already-fetched cache or returns empty. If empty, sync produces no specs (no exhibition data → no triggers). Next sync (after Success fetch) catches up. |
| Past-due trigger | `TriggerRules` returns no Trigger for that type. Diff treats it as not-desired → cancels if existing. |
| Exhibition removed from data after notification scheduled | Notification still fires (OS-managed). Tap → `getById` returns null → fallback to My List. |
| User's reception is at 8pm, notification at 9am of reception day | By design (per brief: "morning of reception day"). |
| Timezone change (user travels) | `triggerAt` was computed in old TZ. iOS recomputes against device TZ; Android `AlarmManager.RTC_WAKEUP` is wall-clock based — fires at the original UTC instant. Documented limitation; v1 doesn't compensate. |
| 3 bookmarks added in 1 second | Each mutation calls `sync()`. Idempotent: second/third call diff against already-scheduled → no duplicate work. |
| User force-quits app | Notifications still fire (OS-managed on both platforms). |
| Inactivity fires, user doesn't open app for another month | No re-fire (Q2 decision). Silent until next mutation. |
| Inactivity fires, user opens app but doesn't bookmark | No re-fire. Cold-start `sync()` will not re-schedule inactivity (per Q2: only mutations re-arm). |
| Language switch mid-cycle | Scheduled notifications keep schedule-time language. Documented limitation. |

## 7. Testing

### Unit tests (commonMain — heaviest investment)

**`TriggerRules`:**
- Closing in 5d at noon → CLOSING at `closingDate - 3d` 09:00 device TZ
- Closing in 2d → CLOSING past-due → skipped
- Opens in 10d, closes in 30d → OPENING + CLOSING (no reception)
- `receptionDate = today` and now = 8am → RECEPTION at 9am same day (valid)
- `receptionDate = today` and now = 10am → RECEPTION past-due → skipped
- `receptionDate = null` → no RECEPTION trigger
- 1-day pop-up (`closingDate = openingDate + 1d`): both 3-day triggers may be in past → skipped
- Timezone: exhibition data in KST, device in PST → trigger uses device TZ (intentional)

**`NotificationSyncService` (with `FakeNotificationScheduler`):**
- Empty bookmarks + empty existing → no schedule, no cancel
- 1 bookmark added (closing in 10d) → 1 closing schedule + 1 inactivity schedule
- 1 bookmark removed (had 3 schedules) → 3 cancels for that bookmark only
- Already-scheduled bookmark (id matches) → no-op
- `hasPermission == false` → noop, zero schedule/cancel calls
- Inactivity: bookmark mutation → cancel existing inactivity, reschedule at now + 7d
- Inactivity: zero bookmarks after removal → cancel inactivity, do NOT reschedule
- Inactivity already fired (not in scheduledIds) and no mutation → do NOT reschedule (Q2)
- Past-due bookmark → no schedule
- Idempotent: `sync()` called 3× in a row → final scheduledIds same as after 1× call

**`NotificationContent`:** 8 cases — 4 trigger types × 2 languages, with name + venue interpolation.

**`NotificationPermissionHandler` state:**
- First bookmark add, permission unknown → dialog shown
- Tap Enable → `scheduler.requestPermission()` → on grant → `sync()` runs
- Tap Not now → no permission request, sets `prompted = true`
- Subsequent mutations after deny → dialog NOT shown
- Pre-existing user with bookmarks → dialog only on next *mutation*, not app open

### Platform integration tests (light)

**Android (`AndroidNotificationScheduler`):**
- Schedule via `setWindow` for 5s in future → `ScheduledIdIndex` contains id; pending intent registered
- Cancel by id → index shrinks; pending intent removed
- `scheduledIds()` round-trips correctly through DataStore index

**iOS (`IosNotificationScheduler`):**
- Schedule via `UNCalendarNotificationTrigger` → `getPendingNotificationRequests()` contains id
- Cancel by id → request list shrinks
- `scheduledIds()` reads OS-side directly

### Manual end-to-end verification

- **Permission flow** (fresh install, both platforms): bookmark → rationale dialog → Enable → OS sheet → grant → notification scheduled (verify in OS dev tools / `adb shell dumpsys alarm`).
- **Permission flow — deny on rationale**: no OS sheet shown, no schedule. Bookmark again → no re-prompt.
- **Permission flow — deny on OS sheet**: no schedule, no re-prompt.
- **Triggers** (clock-shifted device): bookmark exhibition closing in 4d → set device clock to 09:00 of trigger day → notification appears within ±5min (Android) / on the dot (iOS).
- **Tap behavior**: tap notification → app opens → lands on that exhibition's detail page. Tap inactivity → lands on My List.
- **Tap fallback**: bookmark, schedule, simulate exhibition removal (mock data layer or delete from Supabase test instance) → tap notification → falls back to My List.
- **Inactivity**: bookmark, advance device clock 7d, no further mutations → notification fires at 9am day 7. Tap → My List.
- **App force-quit**: schedule, force-quit, advance clock → notification still fires (OS-managed sanity check).

### Out of scope for testing

- Cross-timezone travel (OS handles)
- Load testing (max ~3 × bookmarks + 1 inactivity ≈ tens of items)
- Copy A/B testing

## 8. Out of scope (v1 — per brief)

- Remote push notifications (FCM/APNs backend) — deferred to v2
- "New exhibitions in your city" / weekly digest / editor's picks (require backend fan-out)
- In-app notification center / history
- Notification settings screen (per-type toggles)
- Settings deep-link banner on permission deny
- Re-rendering scheduled notifications on language switch

## 9. Future: v2 remote push

When ready: extend `NotificationScheduler` interface with a registration step (device token upload to Supabase). Add a remote trigger path in `NotificationSyncService` (or a sibling). Local notifications for personal triggers (bookmarks) remain. Remote handles editorial/discovery triggers.

## 10. Success metrics

- Permission grant rate on first prompt (industry baseline for contextual prompts is roughly 30–50%; we'll establish our own baseline post-launch)
- Tap-through rate per trigger type (CLOSING expected highest)
- Re-engagement: % of users who return to app within 24h of a notification
- Zero duplicate notifications per (exhibition, trigger type)

## 11. Open questions for implementation phase

- DataStore key naming for `ScheduledIdIndex` (Android) and `notification_permission_prompted`. Conform to existing key conventions in the repo.
- iOS provisional notifications — should we use `.provisional` authorization (silent quiet-delivery) for users who deny? Brief says no re-prompts; provisional is a workaround. **Recommend: skip for v1.**
- Whether `NotificationSyncService` should be in a long-lived `CoroutineScope` (Application-scoped on Android) vs. fire-and-forget per call. Implementation detail — likely Application-scoped to survive Activity recreation during sync.
