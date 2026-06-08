# Multi-Event Banner / Pager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface ALL simultaneously-active events (not just the first) across the Featured pager, List banner, and Map FAB, each auto-cycling with manual swipe/tap control.

**Architecture:** Three layers. (1) `TabsViewModel` exposes `activeEvents: StateFlow<List<Event>>` (the bug fix). (2) A cycling driver — pure index functions + a thin composition-scoped `@Composable` wrapper that auto-advances and pauses off-screen. (3) Stateless presentation components each render one event; the screen picks which. Featured uses Foundation `HorizontalPager`; List + Map use the cycling driver. A reduced-motion `expect/actual` gate disables auto-advance for accessibility.

**Tech Stack:** Kotlin 2.1.20, Compose Multiplatform 1.8.0 (Foundation `HorizontalPager` — stable), kotlinx-coroutines 1.9.0, kotlinx-datetime 0.6.1. Tests: `kotlin.test` + `kotlinx.coroutines.test` (no Compose UI test harness exists — only pure logic is unit-tested).

**Branch:** `046-multi-event-banner-pager` (already created off `develop`). Spec: `docs/superpowers/specs/2026-06-08-multi-event-banner-pager-design.md`.

**Build/test commands:**
- Common unit tests (JVM/Android): `./gradlew :composeApp:testDebugUnitTest` (composeApp) and `./gradlew :shared:testDebugUnitTest` (shared). Single class: append `--tests "com.gallr.app.viewmodel.TabsViewModelActiveEventsTest"`.
- Compile check (no full build): `./gradlew :composeApp:compileDebugKotlinAndroid` (Android) — fastest signal that Kotlin compiles.
- iOS actuals compile under `:composeApp:compileKotlinIosSimulatorArm64` (slower; run once after the expect/actual task).

---

## Task 0: Pure cycling-index functions (TDD)

The timer can't be unit-tested (no Compose UI harness), so the index math is extracted into pure functions and fully tested here. Uses `Int.mod` (not `%`) so results are always non-negative / in-range.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/CyclingIndex.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gallr/app/ui/components/CyclingIndexTest.kt`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/com/gallr/app/ui/components/CyclingIndexTest.kt`:
```kotlin
package com.gallr.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CyclingIndexTest {

    @Test
    fun next_wraps_0_to_1_to_2_to_0_for_count_3() {
        assertEquals(1, nextCyclingIndex(0, 3))
        assertEquals(2, nextCyclingIndex(1, 3))
        assertEquals(0, nextCyclingIndex(2, 3))
    }

    @Test
    fun next_returns_0_for_nonpositive_count() {
        assertEquals(0, nextCyclingIndex(0, 0))
        assertEquals(0, nextCyclingIndex(5, -1))
    }

    @Test
    fun clamp_keeps_index_in_range_on_shrink() {
        // count drops 2 -> 1 while raw still points at old index 1
        assertEquals(0, clampCyclingIndex(1, 1))
    }

    @Test
    fun clamp_handles_grow_and_normal_reads() {
        assertEquals(2, clampCyclingIndex(2, 3))
        assertEquals(0, clampCyclingIndex(3, 3)) // 3 mod 3 == 0
    }

    @Test
    fun clamp_returns_0_for_nonpositive_count() {
        assertEquals(0, clampCyclingIndex(4, 0))
        assertEquals(0, clampCyclingIndex(4, -2))
    }

    @Test
    fun clamp_normalizes_negative_raw() {
        // defensive: Int.mod never returns negative
        assertEquals(2, clampCyclingIndex(-1, 3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.gallr.app.ui.components.CyclingIndexTest"`
Expected: FAIL — `nextCyclingIndex` / `clampCyclingIndex` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/CyclingIndex.kt`:
```kotlin
package com.gallr.app.ui.components

/** Next index with wrap-around. count <= 0 → 0. */
internal fun nextCyclingIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current + 1).mod(count)

/** Safe read: always in [0, count). Uses Int.mod (never %), so negative raw is normalized. */
internal fun clampCyclingIndex(raw: Int, count: Int): Int =
    if (count <= 0) 0 else raw.mod(count)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.gallr.app.ui.components.CyclingIndexTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/CyclingIndex.kt \
        composeApp/src/commonTest/kotlin/com/gallr/app/ui/components/CyclingIndexTest.kt
git commit -m "feat(events): pure cycling-index functions with wrap + clamp"
```

---

## Task 1: Reduced-motion accessibility gate (expect/actual)

Disables auto-advance when reduce-motion / screen-reader is active (WCAG 2.2.2). Follows the established `PlatformBackHandler` expect/actual pattern. No test (platform-API passthrough; verified by manual QA).

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/accessibility/ReduceMotion.kt`
- Create: `composeApp/src/androidMain/kotlin/com/gallr/app/accessibility/ReduceMotion.android.kt`
- Create: `composeApp/src/iosMain/kotlin/com/gallr/app/accessibility/ReduceMotion.ios.kt`

- [ ] **Step 1: Write the `expect` declaration**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/accessibility/ReduceMotion.kt`:
```kotlin
package com.gallr.app.accessibility

import androidx.compose.runtime.Composable

/**
 * True when the OS signals reduced motion OR a screen reader is active.
 * Callers disable auto-advancing timers when this is true; all content still
 * renders and remains manually swipeable/tappable.
 */
@Composable
expect fun isReduceMotionOrScreenReaderActive(): Boolean
```

- [ ] **Step 2: Write the Android actual**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/accessibility/ReduceMotion.android.kt`:
```kotlin
package com.gallr.app.accessibility

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun isReduceMotionOrScreenReaderActive(): Boolean {
    val context = LocalContext.current
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val touchExploration = am?.isTouchExplorationEnabled == true
    val animationsOff = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
    }.getOrDefault(false)
    return touchExploration || animationsOff
}
```

- [ ] **Step 3: Write the iOS actual**

Create `composeApp/src/iosMain/kotlin/com/gallr/app/accessibility/ReduceMotion.ios.kt`:
```kotlin
package com.gallr.app.accessibility

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

@Composable
actual fun isReduceMotionOrScreenReaderActive(): Boolean =
    UIAccessibilityIsReduceMotionEnabled() || UIAccessibilityIsVoiceOverRunning()
```

- [ ] **Step 4: Verify both targets compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL (confirms the iOS actual resolves `UIAccessibility*` symbols).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/accessibility/ReduceMotion.kt \
        composeApp/src/androidMain/kotlin/com/gallr/app/accessibility/ReduceMotion.android.kt \
        composeApp/src/iosMain/kotlin/com/gallr/app/accessibility/ReduceMotion.ios.kt
git commit -m "feat(a11y): expect/actual reduced-motion + screen-reader gate"
```

---

## Task 2: ViewModel — `activeEvents` list + multi-event filter (TDD, the bug fix)

This is the core fix. Replace the single `activeEvent` with a list; update the `eventOnly` predicate from `==` to `in`; clear `eventOnly` only when the active set is empty.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/TabsViewModelActiveEventsTest.kt` (create)

### Step group A — make the test fake mutable, then write the failing tests

- [ ] **Step 1: Write the failing test (with a mutable fake repo)**

Create `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/TabsViewModelActiveEventsTest.kt`:
```kotlin
package com.gallr.app.viewmodel

import com.gallr.shared.data.model.Event
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FilterState
import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.EventRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import com.gallr.shared.repository.ThemeRepository
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TabsViewModelActiveEventsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ── Fakes ──────────────────────────────────────────────────────────────
    private fun ev(id: String, start: String, end: String) = Event(
        id = id, nameKo = id, nameEn = id, descriptionKo = "", descriptionEn = "",
        locationLabelKo = "x", locationLabelEn = "x",
        startDate = LocalDate.parse(start), endDate = LocalDate.parse(end),
        brandColor = "#000000", ticketUrl = null, isActive = true,
    )

    private fun exh(id: String, eventId: String?) = Exhibition(
        id = id, nameKo = id, nameEn = id, venueNameKo = "v", venueNameEn = "v",
        cityKo = "서울", cityEn = "Seoul", regionKo = "강남", regionEn = "Gangnam",
        openingDate = LocalDate.parse("2026-01-01"), closingDate = LocalDate.parse("2030-01-01"),
        isFeatured = false, latitude = null, longitude = null,
        descriptionKo = "", descriptionEn = "", addressKo = "", addressEn = "",
        coverImageUrl = null, eventId = eventId,
    )

    private class MutableEventRepo(var events: List<Event>) : EventRepository {
        override suspend fun getActiveEvents() = Result.success(events.sortedBy { it.startDate })
        override suspend fun getEventById(id: String) = Result.success(events.firstOrNull { it.id == id })
        override suspend fun getExhibitionsForEvent(id: String) = Result.success(emptyList<Exhibition>())
    }

    private class FakeExhibitionRepo(private val all: List<Exhibition>) : ExhibitionRepository {
        override suspend fun getExhibitions() = Result.success(all)
        override suspend fun getFeaturedExhibitions() = Result.success(all)
    }

    private object FakeBookmarks : BookmarkRepository {
        override fun observeBookmarkedIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun isBookmarked(exhibitionId: String) = false
        override suspend fun addBookmark(exhibitionId: String) {}
        override suspend fun removeBookmark(exhibitionId: String) {}
        override suspend fun clearAll() {}
        override fun setMutationListener(listener: suspend () -> Unit) {}
    }

    private object FakeLanguage : LanguageRepository {
        override fun observeLanguage(): Flow<AppLanguage> = flowOf(AppLanguage.KO)
        override suspend fun setLanguage(lang: AppLanguage) {}
    }

    private object FakeTheme : ThemeRepository {
        override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) {}
    }

    private fun vm(events: List<Event>, exhibitions: List<Exhibition> = emptyList()): Pair<TabsViewModel, MutableEventRepo> {
        val repo = MutableEventRepo(events)
        val vm = TabsViewModel(
            exhibitionRepository = FakeExhibitionRepo(exhibitions),
            bookmarkRepository = FakeBookmarks,
            languageRepository = FakeLanguage,
            themeRepository = FakeTheme,
            eventRepository = repo,
        )
        return vm to repo
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    fun two_active_events_both_surface() = runTest(dispatcher) {
        val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.activeEvents.value.map { it.id })
    }

    @Test
    fun zero_events_empty_list() = runTest(dispatcher) {
        val (vm, _) = vm(emptyList())
        advanceUntilIdle()
        assertTrue(vm.activeEvents.value.isEmpty())
    }

    @Test
    fun one_event_single_entry() = runTest(dispatcher) {
        val (vm, _) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
        advanceUntilIdle()
        assertEquals(listOf("a"), vm.activeEvents.value.map { it.id })
    }

    @Test
    fun event_only_keeps_exhibitions_in_any_active_event() = runTest(dispatcher) {
        val (vm, _) = vm(
            events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
            exhibitions = listOf(exh("x", "a"), exh("y", "b"), exh("z", "other"), exh("w", null)),
        )
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
        assertEquals(setOf("x", "y"), ids.toSet())
    }

    @Test
    fun event_only_clears_when_active_set_becomes_empty() = runTest(dispatcher) {
        val (vm, repo) = vm(listOf(ev("a", "2026-09-03", "2026-09-07")))
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        assertTrue(vm.filterState.value.eventOnly)
        repo.events = emptyList()
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.filterState.value.eventOnly)
    }

    @Test
    fun shrink_two_to_one_does_not_clear_event_only() = runTest(dispatcher) {
        val (vm, repo) = vm(
            events = listOf(ev("a", "2026-09-03", "2026-09-07"), ev("b", "2026-09-04", "2026-09-07")),
            exhibitions = listOf(exh("x", "a"), exh("y", "b")),
        )
        advanceUntilIdle()
        vm.updateFilter { copy(eventOnly = true) }
        advanceUntilIdle()
        repo.events = listOf(ev("a", "2026-09-03", "2026-09-07"))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.filterState.value.eventOnly)
        val ids = (vm.filteredExhibitions.value as ExhibitionListState.Success).exhibitions.map { it.id }
        assertEquals(setOf("x"), ids.toSet())
    }
}
```

NOTE for the implementer: this fake's `ExhibitionRepository` / `BookmarkRepository` / `LanguageRepository` / `ThemeRepository` method signatures must match the real interfaces. If any signature differs, mirror the exact one from `TabsViewModelSignUpNudgeTest.kt` (which already fakes these) rather than guessing. The `Exhibition` constructor args above must match `shared/.../data/model/Exhibition.kt` — copy any missing required fields from that data class with sensible defaults.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.gallr.app.viewmodel.TabsViewModelActiveEventsTest"`
Expected: FAIL — `vm.activeEvents` unresolved reference (property doesn't exist yet).

### Step group B — implement the VM change

- [ ] **Step 3: Replace the active-event state**

In `TabsViewModel.kt`, replace lines 100–101:
```kotlin
    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent
```
with:
```kotlin
    private val _activeEvents = MutableStateFlow<List<Event>>(emptyList())
    val activeEvents: StateFlow<List<Event>> = _activeEvents
```

- [ ] **Step 4: Populate the list in `loadActiveEvents`**

In `loadActiveEvents()` (around lines 106–119), change the success/failure bodies:
```kotlin
    private fun loadActiveEvents() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events ->
                    _activeEventsById.value = events.associateBy { it.id }
                    _activeEvents.value = events
                }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvents: ${it.message}")
                    _activeEventsById.value = emptyMap()
                    _activeEvents.value = emptyList()
                }
        }
    }
```

- [ ] **Step 5: Update the `filteredExhibitions` combine**

In the `combine(...)` for `filteredExhibitions` (lines 256–290):

(a) Change the 7th flow input on line 257 from `_activeEvent` to `_activeEventsById`:
```kotlin
            _allExhibitions, _filterState, _selectedCity, _showMyListOnly, bookmarkedIds, _searchQuery, _activeEventsById,
```

(b) Replace the index-6 cast (line 267):
```kotlin
            val activeEvent = values[6] as Event?
```
with:
```kotlin
            @Suppress("UNCHECKED_CAST")
            val activeEventIds = (values[6] as Map<String, Event>).keys
```

(c) Replace the event-only predicate (lines 285–290):
```kotlin
                        .filter {
                            // Phase 2b — event-only filter. Short-circuits when activeEvent is null
                            // so stale eventOnly state doesn't transiently empty the list while the
                            // auto-reset collector (init block) clears it.
                            !filter.eventOnly || activeEvent == null || it.eventId == activeEvent.id
                        }
```
with:
```kotlin
                        .filter {
                            // Multi-event filter — keep exhibitions linked to ANY active event.
                            // Short-circuits when the active set is empty so stale eventOnly state
                            // doesn't transiently empty the list while the auto-reset collector clears it.
                            !filter.eventOnly || activeEventIds.isEmpty() || it.eventId in activeEventIds
                        }
```

- [ ] **Step 6: Update the auto-reset collector**

In `init {}` (lines 397–403), replace:
```kotlin
        viewModelScope.launch {
            _activeEvent.collect { event ->
                if (event == null && _filterState.value.eventOnly) {
                    _filterState.value = _filterState.value.copy(eventOnly = false)
                }
            }
        }
```
with:
```kotlin
        viewModelScope.launch {
            _activeEvents.collect { events ->
                if (events.isEmpty() && _filterState.value.eventOnly) {
                    _filterState.value = _filterState.value.copy(eventOnly = false)
                }
            }
        }
```

- [ ] **Step 7: Run the VM tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.gallr.app.viewmodel.TabsViewModelActiveEventsTest"`
Expected: PASS (6 tests). The screens still reference `activeEvent` and will NOT compile yet — that's expected; the next tasks migrate them. Do NOT run the full compile here; run only this test class (the test module compiles the VM + test, not the screens, for a `--tests` filter only if the screens are in the same module — if compilation of the whole `composeApp` is triggered and fails on the screens, proceed to Task 3 and re-run after Task 5).

> Implementer note: composeApp is one module, so `testDebugUnitTest` compiles all of commonMain including the screens. If the screens' `activeEvent` references break compilation, this test won't run in isolation. In that case, do Steps 3–6 here, then complete Tasks 3, 4, 5 (the screen migrations) and run all tests together at the end of Task 5. Commit Task 2 only after compilation is green (end of Task 5). The TDD ordering still holds: tests written first, implementation second.

- [ ] **Step 8: (Deferred commit)**

Hold the commit until screens compile (Task 5, Step-group commit). This keeps the tree buildable per-commit.

---

## Task 3: Map FAB — cycling circular cover-image FAB + a11y

Keep the 60dp circle + cover image (decision #8). Cross-fade the image + ring color across active events; tap → current event. Add real `contentDescription` + `liveRegion`.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

- [ ] **Step 1: Rewrite `EventMapFab` to cross-fade + add semantics**

Replace the body of `EventMapFab.kt` with:
```kotlin
package com.gallr.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Persistent floating button on the Map tab. Circular cover-image FAB with a
 * brand-color ring. When [event] changes (multi-event cycling), the cover image
 * and ring color cross-fade. Tap navigates to the currently-shown event.
 */
@Composable
fun EventMapFab(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    val desc = "${event.localizedName(lang)} · ${event.statusEyebrow(today, lang)}"

    Crossfade(
        targetState = event,
        animationSpec = tween(durationMillis = 260),
        modifier = modifier
            .size(60.dp)
            .semantics {
                contentDescription = desc
                liveRegion = LiveRegionMode.Polite
            }
            .clickable(onClick = onTap),
    ) { current ->
        val brand = parseHexColor(current.brandColor)?.let { Color(it) } ?: Color.Black
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(brand)
                .border(2.dp, brand, CircleShape),
        ) {
            if (current.coverImageUrl != null) {
                AsyncImage(
                    model = current.coverImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            }
        }
    }
}
```
NOTE: the `.shadow(4.dp)` from the original is dropped (DESIGN.md — no shadows on the FAB; sanctioned by the Task 7 DESIGN.md revision). The clickable + semantics live on the `Crossfade` container so the tap target stays a stable 60dp circle while content cross-fades.

- [ ] **Step 2: Drive cycling from `MapScreen`**

In `MapScreen.kt`:

(a) Replace the collect on line 63:
```kotlin
    val activeEvent by viewModel.activeEvent.collectAsState()
```
with:
```kotlin
    val activeEvents by viewModel.activeEvents.collectAsState()
```

(b) Replace the FAB block (lines 165–173):
```kotlin
        activeEvent?.let { event ->
            EventMapFab(
                event = event,
                onTap = { onEventTap(event.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
```
with:
```kotlin
        if (activeEvents.isNotEmpty()) {
            val fabIdx by rememberCyclingIndex(activeEvents.size, intervalMillis = 3500L)
            val current = activeEvents[fabIdx]
            EventMapFab(
                event = current,
                lang = lang,
                onTap = { onEventTap(current.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
```

(c) Add imports at the top of `MapScreen.kt`:
```kotlin
import androidx.compose.runtime.getValue
import com.gallr.app.ui.components.rememberCyclingIndex
```
(`getValue` may already be imported — only add if missing. `EventMapFab` is already imported.)

- [ ] **Step 3: (compile after Task 5)** No commit yet; verified in Task 5.

---

## Task 4: List banner — cycling slot, swipe/tap, progress bar, branded chips

Banner stays stateless; `ListScreen` drives cycling + the single-pass gesture + progress bar. Filter chips become one-per-event.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventListBanner.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`

- [ ] **Step 1: Make `EventListBanner` stateless (remove internal click, add semantics)**

In `EventListBanner.kt`, change the `Box` modifier (lines 39–45) from:
```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(brand)
            .clickable(onClick = onTap),
    ) {
```
to:
```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(brand),
    ) {
```
Remove the now-unused `onTap` parameter and the `clickable` import. Update the signature:
```kotlin
@Composable
fun EventListBanner(
    event: Event,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
```
Add a semantics modifier carrying the description (so a screen reader still reads it):
```kotlin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
```
and apply on the `Box`:
```kotlin
            .semantics {
                contentDescription = "$name · $nowOn"
                liveRegion = LiveRegionMode.Polite
            }
```

- [ ] **Step 2: Replace the banner usage in `ListScreen` with a cycling slot**

In `ListScreen.kt`:

(a) Replace the collect on line 94:
```kotlin
    val activeEvent by viewModel.activeEvent.collectAsState()
```
with:
```kotlin
    val activeEvents by viewModel.activeEvents.collectAsState()
```

(b) Replace the banner block (lines 136–144):
```kotlin
        // ── Event banner (Phase 2b) — shown on both sub-tabs when active ──
        val event = activeEvent
        if (event != null) {
            EventListBanner(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
            )
        }
```
with:
```kotlin
        // ── Cycling event banner — shows all active events in one 36dp slot ──
        if (activeEvents.isNotEmpty()) {
            CyclingEventBanner(
                events = activeEvents,
                lang = lang,
                onEventTap = onEventTap,
            )
        }
```

(c) Add a private `CyclingEventBanner` composable at the bottom of `ListScreen.kt` (after `GallrEventFilterChip`):
```kotlin
@Composable
private fun CyclingEventBanner(
    events: List<Event>,
    lang: AppLanguage,
    onEventTap: (String) -> Unit,
) {
    var manualTick by remember { mutableIntStateOf(0) }
    val idx by rememberCyclingIndex(events.size, intervalMillis = 3500L, resetKey = manualTick)
    val current = events[idx]
    val autoCycle = !isReduceMotionOrScreenReaderActive()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(idx, events.size, autoCycle) {
        progress.snapTo(0f)
        if (events.size > 1 && autoCycle) {
            progress.animateTo(1f, animationSpec = tween(durationMillis = 3500, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(events.size) {
                awaitEachGesture {
                    awaitFirstDown()
                    var dx = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        dx += e.changes.sumOf { it.positionChange().x.toDouble() }.toFloat()
                        if (e.changes.none { it.pressed }) break
                    }
                    val threshold = viewConfiguration.touchSlop * 2.5f
                    when {
                        dx < -threshold -> manualTick++
                        dx > threshold -> manualTick++
                        else -> onEventTap(current.id)
                    }
                }
            },
    ) {
        Crossfade(targetState = current, animationSpec = tween(180)) { ev ->
            EventListBanner(event = ev, lang = lang, modifier = Modifier.fillMaxSize())
        }
        if (events.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.value)
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
```
Both swipe directions advance to the next event (the banner is a single forward cycle — back/forward distinction adds no value in a 1-slot 1–3-item rotation; `manualTick++` resets the timer to 0 via the driver's `resetKey`, restarting the visible event at index 0 of the rotation, consistent with decision #6's deterministic reset).

(d) Add imports to `ListScreen.kt`:
```kotlin
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.positionChange
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.rememberCyclingIndex
```
(`Box`, `fillMaxWidth`, `height`, `background`, `Alignment`, `Color`, `pointerInput`, `remember`, `mutableIntStateOf`, `getValue`, `setValue`, `dp` are already imported — verify and add only if missing.)

- [ ] **Step 3: Make the event filter chips one-per-event**

In `ListScreen.kt`, replace the single-event chip block (lines 307–316):
```kotlin
            activeEvent?.let { event ->
                val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                GallrEventFilterChip(
                    selected = filter.eventOnly,
                    onClick = { viewModel.updateFilter { copy(eventOnly = !eventOnly) } },
                    label = event.localizedName(lang),
                    brandColor = brand,
                )
                Spacer(Modifier.width(GallrSpacing.sm))
            }
```
with:
```kotlin
            activeEvents.forEach { event ->
                val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                GallrEventFilterChip(
                    selected = filter.eventOnly,
                    onClick = { viewModel.updateFilter { copy(eventOnly = !eventOnly) } },
                    label = event.localizedName(lang),
                    brandColor = brand,
                )
                Spacer(Modifier.width(GallrSpacing.sm))
            }
```

- [ ] **Step 4: Update the exhibition-card event treatment + empty-state for multi-event**

(a) Replace the per-item treatment lookup (lines 432–442):
```kotlin
                                val treatment = remember(activeEvent, exhibition.eventId, lang) {
                                    activeEvent
                                        ?.takeIf { exhibition.eventId == it.id }
                                        ?.let { event ->
                                            val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                                            EventTreatment(
                                                brandColor = brand,
                                                label = event.ribbonLabel(lang),
                                            )
                                        }
                                }
```
with:
```kotlin
                                val treatment = remember(activeEvents, exhibition.eventId, lang) {
                                    activeEvents
                                        .firstOrNull { exhibition.eventId == it.id }
                                        ?.let { event ->
                                            val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                                            EventTreatment(
                                                brandColor = brand,
                                                label = event.ribbonLabel(lang),
                                            )
                                        }
                                }
```

(b) Replace the event-only empty-state branch (lines 402–404):
```kotlin
                            filter.eventOnly && activeEvent != null ->
                                if (lang == AppLanguage.KO) "${activeEvent!!.nameKo}에 참여하는 전시가 없습니다."
                                else "No exhibitions in ${activeEvent!!.nameEn}."
```
with:
```kotlin
                            filter.eventOnly && activeEvents.isNotEmpty() ->
                                if (lang == AppLanguage.KO) "현재 아트페어에 참여하는 전시가 없습니다."
                                else "No exhibitions in the current art fairs."
```

- [ ] **Step 5: (compile after Task 5)** No commit yet.

---

## Task 5: Featured pager — HorizontalPager in LazyColumn + dots + reveal chip

Restructure Featured into a single `LazyColumn`; pager is the first item (fixed height), dots second, then the existing header + cards.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt` (a11y only)

- [ ] **Step 1: Add a fixed height token**

In `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrSpacing.kt`, add a new object below `GallrSpacing` (the file already has `import androidx.compose.ui.unit.dp`):
```kotlin
/** Fixed dimensions for the Featured event pager. */
object GallrEventCard {
    /** 240dp — hero card height inside the pager (mockup --card-height). */
    val pagerHeight = 240.dp
    /** 26dp — dot-indicator strip height (mockup --dots-height). */
    val dotsHeight = 26.dp
}
```

- [ ] **Step 2: Add a11y description to `EventPromotionCard`**

In `EventPromotionCard.kt`, add semantics to the outer `Box` (lines 43–48). Add imports:
```kotlin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
```
Change:
```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brand)
            .border(1.dp, Color.Black)
            .clickable(onClick = onTap),
    ) {
```
to:
```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brand)
            .border(1.dp, Color.Black)
            .semantics { contentDescription = "$name · $eyebrow" }
            .clickable(onClick = onTap),
    ) {
```

- [ ] **Step 3: Rewrite `FeaturedScreen` body**

Replace the `Column { ... }` body (the entire `Column(modifier = modifier.fillMaxSize()) { ... }` from line 44 to its close at line 122) so the pager lives inside the `LazyColumn`. Replace lines 38–123 with:
```kotlin
    val state by viewModel.featuredState.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvents by viewModel.activeEvents.collectAsState()

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { activeEvents.size })

    // 4s auto-advance — re-arms on settle, skips while dragging, pauses off-screen.
    val autoCycle = !isReduceMotionOrScreenReaderActive()
    LaunchedEffect(pagerState, autoCycle) {
        if (!autoCycle) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collectLatest {
            if (pagerState.pageCount <= 1) return@collectLatest
            delay(4000)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }

    // Reveal chip visible once the pager has scrolled out of view (2+ events only).
    val showChip by remember {
        derivedStateOf { activeEvents.size >= 2 && listState.firstVisibleItemIndex > 0 }
    }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        when (val s = state) {
            is ExhibitionListState.Loading -> {
                Column(modifier = Modifier.padding(horizontal = GallrSpacing.md)) {
                    repeat(3) { SkeletonCard(modifier = Modifier.padding(bottom = GallrSpacing.md)) }
                }
            }

            is ExhibitionListState.Error -> {
                GallrEmptyState(
                    message = if (s.message == "network") {
                        if (lang == AppLanguage.KO) "인터넷 연결을 확인해주세요." else "Check your internet connection."
                    } else {
                        if (lang == AppLanguage.KO) "문제가 발생했습니다. 다시 시도해주세요." else "Something went wrong. Please try again."
                    },
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadFeaturedExhibitions() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is ExhibitionListState.Success -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(GallrSpacing.md),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (activeEvents.isNotEmpty()) {
                            item(key = "event-pager") {
                                if (activeEvents.size == 1) {
                                    EventPromotionCard(
                                        event = activeEvents[0],
                                        lang = lang,
                                        onTap = { onEventTap(activeEvents[0].id) },
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight),
                                    )
                                } else {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight),
                                    ) { page ->
                                        EventPromotionCard(
                                            event = activeEvents[page],
                                            lang = lang,
                                            onTap = { onEventTap(activeEvents[page].id) },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                            if (activeEvents.size > 1) {
                                item(key = "event-pager-dots") {
                                    PagerDots(
                                        count = activeEvents.size,
                                        current = pagerState.currentPage,
                                        modifier = Modifier.fillMaxWidth().height(GallrEventCard.dotsHeight),
                                    )
                                }
                            }
                        }

                        item(key = "featured-header") {
                            Text(
                                text = if (lang == AppLanguage.KO) "추천" else "FEATURED",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(vertical = GallrSpacing.sm),
                            )
                        }

                        if (s.exhibitions.isEmpty()) {
                            item(key = "featured-empty") {
                                GallrEmptyState(
                                    message = if (lang == AppLanguage.KO) "추천 전시가 없습니다." else "No featured exhibitions right now.",
                                    actionLabel = if (lang == AppLanguage.KO) "새로고침" else "Refresh",
                                    onAction = { viewModel.loadFeaturedExhibitions() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = GallrSpacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showChip) {
            RevealChip(
                count = activeEvents.size,
                lang = lang,
                onTap = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = GallrSpacing.sm),
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (active) 18.dp else 6.dp)
                    .background(if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun RevealChip(count: Int, lang: AppLanguage, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (lang == AppLanguage.KO) "${count}개의 아트페어 진행 중" else "$count Art Fairs On Now"
    Row(
        modifier = modifier
            .background(Color.Black)
            .clickable(onClick = onTap)
            .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↑ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
```

- [ ] **Step 4: Fix `FeaturedScreen` imports**

Replace the import block in `FeaturedScreen.kt` so it includes everything the new body uses. The full import set:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gallr.app.accessibility.isReduceMotionOrScreenReaderActive
import com.gallr.app.ui.components.EventPromotionCard
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.components.SkeletonCard
import com.gallr.app.ui.theme.GallrEventCard
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.ExhibitionListState
import com.gallr.app.viewmodel.TabsViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
```

- [ ] **Step 5: Compile the whole module (all screens now migrated)**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. If it fails on an unresolved `activeEvent`, grep for stragglers:
`grep -rn "\.activeEvent\b\|activeEvent " composeApp/src/commonMain` — every hit must now be `activeEvents`.

- [ ] **Step 6: Run ALL unit tests (Tasks 0 + 2 verified together)**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS — including `CyclingIndexTest` (6) and `TabsViewModelActiveEventsTest` (6), and the pre-existing `TabsViewModelSignUpNudgeTest` still green.

- [ ] **Step 7: Compile iOS to catch actual/import drift**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit Tasks 2–5 together (first buildable commit since Task 1)**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt \
        composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/TabsViewModelActiveEventsTest.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventListBanner.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrSpacing.kt
git commit -m "feat(events): multi-event activeEvents list + cycling pager/banner/FAB

Fixes the firstOrNull() bug that dropped 2nd/3rd active events. Featured
gets a HorizontalPager (4s auto-advance, mono dots, reveal chip) inside the
scroll; List banner + Map FAB auto-cycle (3.5s) via rememberCyclingIndex
with swipe/tap (List) and image cross-fade (FAB). Auto-advance respects
the reduced-motion gate. eventOnly filter now matches ANY active event."
```

---

## Task 6: DESIGN.md — sanction functional motion

**Files:**
- Modify: `DESIGN.md`

- [ ] **Step 1: Rewrite the Motion section**

Replace the Motion section (lines 104–108):
```markdown
## Motion
- **Approach:** Minimal-functional
- **Philosophy:** State feedback relies on immediate color/opacity shift. No motion or positional animation.
- **Press duration:** < 100ms for press/active state color shift
- **Transitions:** System defaults only. No custom enter/exit animations.
```
with:
```markdown
## Motion
- **Approach:** Functional-minimal. Motion communicates state and liveness; it is never decorative.
- **Press duration:** < 100ms for press/active state color shift.
- **Sanctioned motion:**
  - Opacity crossfade for content swaps (tab content, cycling event surfaces) — ~150–260ms.
  - Timing-cue indicators (e.g. an auto-cycle progress bar) when content advances on a timer.
  - Auto-advancing carousels (Featured event pager, List banner, Map FAB) — disabled when the OS signals reduced motion or a screen reader is active (see Accessibility).
  - Existing enter/exit + state animations already in use: `AnimatedVisibility` (collapsing filters), `AnimatedContent` fades, list skeleton shimmer, bookmark spring.
- **Avoid:** Gratuitous positional/translate animation that carries no state meaning. Prefer opacity/color over movement.
- **Accessibility:** All timer-driven motion must check `isReduceMotionOrScreenReaderActive()` and fall back to a static, manually-controlled presentation.
```

- [ ] **Step 2: Add a decisions-log row**

In the Decisions-Log table (ends at line 201), add a new row after the last entry:
```markdown
| 2026-06-08 | Sanction functional motion (crossfade, progress cues, auto-cycling event surfaces, gated by reduced-motion) | Communicates liveness for multi-event promotion; aligns the doc with shipped patterns |
```

- [ ] **Step 3: Commit**

```bash
git add DESIGN.md
git commit -m "docs(design): sanction functional motion in DESIGN.md"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full Android + iOS compile + all tests**

```bash
./gradlew :composeApp:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: all PASS / BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm no `activeEvent` (singular) references remain**

Run: `grep -rn "\.activeEvent\b" composeApp/src shared/src | grep -v build | grep -v activeEvents`
Expected: no output (the singular property is fully removed).

- [ ] **Step 3: Manual QA checklist (device/emulator — record results)**

- 0 events: Featured has no pager item; List has no banner; Map has no FAB. (parity)
- 1 event: Featured shows a bare card (no dots/chip); List banner static, tap → detail; FAB static. (parity)
- 2+ events: Featured pager auto-advances every 4s, swipe works + resets timer, dots track page; scroll down → reveal chip appears → tap returns to top. List banner cross-cycles every 3.5s with progress bar; swipe advances + resets; tap (no swipe) → detail. FAB cross-fades image + ring every 3.5s; tap → currently-shown event.
- `eventOnly` filter: toggling any branded chip shows exhibitions from ALL active fairs.
- Tab-switch away and back: timers paused while away, resume cleanly (no mid-animation jump).
- Reduced motion: enable iOS Reduce Motion (or Android touch-exploration/animations-off) → no auto-advance anywhere; manual swipe/tap still works; dots still render.
- iOS near-45° diagonal swipe on the pager doesn't mis-trigger vertical scroll.

- [ ] **Step 4: Mark the spec/plan done**

No code; this is the handoff point to `/ship` (or `superpowers:finishing-a-development-branch`).
