# Multi-Event Banner / Pager — Design

**Date:** 2026-06-08
**Priority:** P1 (bug fix + feature)
**Base branch:** `develop`
**Status:** Approved design → ready for implementation plan
**Source artifacts:** `260608-multi-event-banner-pager-p1.md` (spec), `260608-multi-event-banner-pager-mockup.html` (mockup)

This design supersedes the source spec where they differ; divergences are called out inline. It folds in 16 adversarially-verified findings from an engineering design review and 12 locked product decisions.

---

## 1. Problem & Root Cause

When 2+ events are simultaneously active, only one surfaces in the app. The root cause is **`TabsViewModel` alone** — `loadActiveEvents()` calls `events.firstOrNull()` and exposes a single `activeEvent: StateFlow<Event?>`. The data layer is already correct: `EventRepository.getActiveEvents()` returns *all* visible events (`isActive && today <= endDate`, via `Event.isVisibleOn`), sorted by `startDate`.

**Terminology reconciliation:** the source spec says `is_pinned = TRUE`. There is no `is_pinned` column. Event visibility is gated by the existing `is_active` column + the date check in `Event.isVisibleOn()`. No Sheet / Apps Script / DTO / migration change is required.

---

## 2. Locked Decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Event display order | Keep repo's existing `sortedBy { startDate }`. **No data-layer change.** No ordering column (matches spec's "out of scope"). |
| 2 | Auto-cycle interval | List banner + Map FAB = **3.5s**; Featured pager auto-advance = **4s**. |
| 3 | Featured dot indicator | **Neutral mono** — active = black (`onBackground`), inactive = light grey. Not brand color. |
| 4 | Featured pager placement | **Inside the scroll** (first item of the `LazyColumn`, scrolls away naturally) + a reveal chip pinned at top to jump back. Not sticky. |
| 5 | Timer lifecycle | **Pause off-screen** (composition-scoped effects). |
| 6 | Wrap behavior | **Wrap-around** (modulo) on all 3 surfaces. |
| 7 | List filter chips | **One boolean `eventOnly` + N brand-colored chips** (each chip toggles the same any-event boolean). Predicate `==` → `in`. |
| 8 | Map FAB | **Keep the circular cover-image FAB** (diverges from mockup's square label). Cycle by cross-fading the cover image + brand ring color. |
| 9 | Motion / DESIGN.md | **Revise DESIGN.md** to formally sanction functional motion (crossfade, timing cues) for a rich UI/UX. |
| 10 | Reduced motion | **Build the `expect/actual` accessibility gate now** — auto-advance disabled when reduce-motion / screen-reader is active. |
| 11 | Map pin restyling | **Out of scope.** Only the FAB changes. (Markers stay as shipped.) |
| 12 | Per-event filtering | One boolean only (see #7). No per-event `Set<eventId>` filter — that's scope creep. |

---

## 3. Architecture

Three layers, cleanly separated, each independently testable:

1. **ViewModel** owns the *data*: `activeEvents: StateFlow<List<Event>>`. Knows nothing about cycling.
2. **Cycling driver** owns *which one shows + the timer*. UI-only. Index math extracted to **pure functions** (testable without a Compose harness); the timer lives in a composition-scoped effect (so it pauses off-screen).
3. **Stateless presentation components** (`EventPromotionCard`, `EventListBanner`, `EventMapFab`) each render **one** event. Internals unchanged; the caller picks which event.

Featured uses Foundation's `HorizontalPager` (its own `PagerState`) rather than the cycling driver, because a pager needs swipe physics + page snapping the driver doesn't model. List + Map share the cycling driver.

---

## 4. ViewModel (`TabsViewModel.kt`)

### State change
```kotlin
// Replace
private val _activeEvent = MutableStateFlow<Event?>(null)
val activeEvent: StateFlow<Event?> = _activeEvent
// With
private val _activeEvents = MutableStateFlow<List<Event>>(emptyList())
val activeEvents: StateFlow<List<Event>> = _activeEvents
```
`loadActiveEvents()`: `_activeEvent.value = events.firstOrNull()` → `_activeEvents.value = events`. `_activeEventsById` populated exactly as today (`events.associateBy { it.id }`).

### `filteredExhibitions` combine (the one cast that changes)
The 7-flow `combine` already uses the **vararg/Array overload** (coroutines 1.9.0 has no `Function7`), so swapping the 7th input keeps `T = Any?` — the call type-checks unchanged. **Do not** convert to a typed named overload (none exists for 7 inputs).

**Reuse `_activeEventsById` as the 7th input** (no new flow): its `keys` *is* `activeEventIds`, and it is populated/cleared in lockstep with the active list inside `loadActiveEvents`, so emptiness semantics are preserved exactly.

- 7th flow input: `_activeEvent` → `_activeEventsById`.
- Cast: `val activeEvent = values[6] as Event?` → `@Suppress("UNCHECKED_CAST") val activeEventIds = (values[6] as Map<String, Event>).keys` (erased generic ⇒ unchecked cast, matching the existing `@Suppress` style on `values[3]`/`values[4]`).
- Predicate: `!filter.eventOnly || activeEvent == null || it.eventId == activeEvent.id`
  → `!filter.eventOnly || activeEventIds.isEmpty() || it.eventId in activeEventIds`
  (`isEmpty()` reproduces the old `== null` short-circuit / transient-empty guard exactly; `Exhibition.eventId: String?` in `Set<String>` is well-typed.)
- Update the inline comment ("activeEvent is null" → "the active set is empty").

### Auto-reset collector (init)
Collect the active set instead of `_activeEvent`; clear `eventOnly` **only on empty**:
```kotlin
viewModelScope.launch {
    _activeEventsById.collect { byId ->
        if (byId.isEmpty() && _filterState.value.eventOnly) {
            _filterState.value = _filterState.value.copy(eventOnly = false)
        }
    }
}
```
A **shrink 2→1 must NOT clear** (set still non-empty); the surviving event's exhibitions stay matched.

### Map pins — no change
`myListMapPins` / `allMapPins` already consume `_activeEventsById` (unchanged). Confirmed no edit needed.

### Drift caveat
The new `activeEvents` is exposed alongside the still-present `_activeEvent`. Driving the **filter** off `_activeEventsById.keys` (not `_activeEvent`) is deliberate — it sidesteps single-vs-list drift. `_activeEvent` is removed entirely once all four screens migrate to `activeEvents` (no remaining consumer).

### Empty-state message (`ListScreen`)
The per-event name is ambiguous across multiple events, so it becomes generic:
- KO: `현재 아트페어에 참여하는 전시가 없습니다.`
- EN: `No exhibitions in the current art fairs.`

---

## 5. Cycling driver (`ui/components/CyclingIndex.kt`)

### Pure, testable core (satisfies the TDD mandate)
The project has **zero Compose UI test infra** (commonTest = `kotlin.test` + `kotlinx.coroutines.test` only). So the math is pure functions, unit-tested under `runTest`; the composable is a thin, untested wrapper.

```kotlin
/** Next index with wrap. count <= 0 → 0. */
internal fun nextCyclingIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current + 1).mod(count)

/** Safe read: always in [0, count). Uses Int.mod, never %. */
internal fun clampCyclingIndex(raw: Int, count: Int): Int =
    if (count <= 0) 0 else raw.mod(count)
```

### Composable wrapper (one effect, modulo-at-read)
```kotlin
@Composable
fun rememberCyclingIndex(
    count: Int,
    intervalMillis: Long = 3500L,
    resetKey: Any? = null,
): State<Int> {
    val raw = remember { mutableIntStateOf(0) }
    val clamped = remember(count) { derivedStateOf { clampCyclingIndex(raw.intValue, count) } }
    val autoCycle = !isReduceMotionOrScreenReaderActive()   // §8
    LaunchedEffect(count, intervalMillis, resetKey, autoCycle) {
        raw.intValue = 0                       // resetKey change returns to a deterministic page (decision #6)
        if (count <= 1 || !autoCycle) return@LaunchedEffect
        while (true) { delay(intervalMillis); raw.intValue = nextCyclingIndex(raw.intValue, count) }
    }
    return clamped
}
```
**Why this shape (review B1/B2):**
- **One** effect + `derivedStateOf` read with `Int.mod` ⇒ the read is provably in `[0, count)` even on the frame a list shrinks. The earlier two-effect (separate clamp) design had a real IndexOutOfBounds window. Consumers always index `events[clamped]`, never `events[raw]`.
- `count <= 1` ⇒ timer no-op (single/zero event = today's behavior).
- `LaunchedEffect` is composition-scoped ⇒ pauses off-screen (decision #5) for free, because tabs leave composition via `App.kt`'s `when(tab)` / `AnimatedContent`.
- `resetKey` change resets `raw` to 0 ⇒ a swipe/tap restarts cycling from a deterministic position (decision #6). Modulo alone would not.

`count` is **dynamic** — driven from the live `activeEvents.size`, which grows/shrinks at runtime.

---

## 6. Featured tab (`FeaturedScreen.kt`)

**Structural change:** today a single `EventPromotionCard` sits in a `Column` above a separate `LazyColumn`. It becomes a **single `LazyColumn`** whose first item(s) are the pager + dots, so they scroll away naturally (decision #4).

### Pager — Foundation 1.8.0 (verified STABLE)
- Imports: `androidx.compose.foundation.pager.HorizontalPager`, `androidx.compose.foundation.pager.rememberPagerState`.
- **No `@OptIn(ExperimentalFoundationApi::class)`** — `HorizontalPager`, `rememberPagerState`, `PagerState.currentPage/targetPage/settledPage`, suspend `animateScrollToPage` are all stable in foundation 1.8.0. (Keep the existing `@OptIn(ExperimentalMaterial3Api::class)` for `PullToRefreshBox`.)
- `rememberPagerState(pageCount = { activeEvents.size })` — `pageCount` is a **required trailing lambda**; the `Int` overload is gone. `PagerState` self-clamps `currentPage` on shrink.
- `HorizontalPager(state = pagerState, …)` — no `pageCount` param on the composable.

### Pager as a LazyColumn item (fixed height — load-bearing)
A pager has unbounded intrinsic height inside a `LazyColumn` (items measured with infinite max height). It **must** be a direct `item {}` with an explicit fixed height; **never** wrapped in a `verticalScroll`/`Column`-with-scroll.
```kotlin
LazyColumn(state = listState, …) {
    if (activeEvents.isNotEmpty()) {
        item(key = "event-pager") {
            if (activeEvents.size == 1) {
                EventPromotionCard(event = activeEvents[0], lang = lang,
                    onTap = { onEventTap(activeEvents[0].id) },
                    modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight))
            } else {
                HorizontalPager(state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(GallrEventCard.pagerHeight)) { page ->
                    EventPromotionCard(event = activeEvents[page], lang = lang,
                        onTap = { onEventTap(activeEvents[page].id) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (activeEvents.size > 1) item(key = "event-pager-dots") { DotRow(pagerState, Modifier.height(26.dp)) }
    }
    item(key = "featured-header") { /* the existing "FEATURED / 추천" label */ }
    items(exhibitions, key = { it.id }) { /* existing ExhibitionCard */ }
}
```
Orthogonal nested scroll (horizontal pager in vertical list) resolves automatically — no extra nested-scroll config. The card height comes from a **token**, not a literal. (Shipped at **180dp** — the mockup's 240dp read too tall vs the original content-wrapped card; the pager needs a fixed height so 180dp keeps it close to the original look.)

### Auto-advance (4s) — key on `settledPage`, guard on scroll
```kotlin
LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collectLatest {
        if (pagerState.pageCount <= 1 || isReduceMotionOrScreenReaderActive()) return@collectLatest
        delay(4000)
        if (!pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
        }
    }
}
```
- Key on `settledPage`, **not** `currentPage` (which flips at the 50% drag threshold and would thrash the timer).
- `collectLatest` re-arming on settle *is* the locked "reset on interaction" — it re-arms on settle, and the `isScrollInProgress` guard makes the touch-down edge safe. (Functionally equivalent to the mockup's `clearInterval`-on-pointerdown.)
- `% pageCount` ⇒ wrap (decision #6). Composition-scoped ⇒ pauses off-screen (#5).
- This `isScrollInProgress` guard is **pager-specific** — do not copy it to List/Map (those use `rememberCyclingIndex`).

### Dots (neutral mono, decision #3)
26dp strip; one dot per page; inactive = 6dp grey square, active = 18dp-wide black bar (matches mockup geometry, consistent with the sharp/geometric language). Driven by `pagerState.currentPage`.

### Reveal chip
Black chip pinned at the top, visible only when `activeEvents.size >= 2` **and** the pager has scrolled out of view (`listState.firstVisibleItemIndex > 0` or the pager item is past). Tapping animates the list back to top.
- Leading `↑` glyph as a separate `Icon`/`Text` in the Row, **not** inside the localized string.
- Label: KO `${n}개의 아트페어 진행 중`, EN `$n Art Fairs On Now` (KO `개의` matches the house counter at `MapScreen` `"${count}개의 전시"`; `진행 중` matches `Event.statusEyebrow`). Gated on `n >= 2`, so the EN singular "Art Fair" is unreachable; KO needs no plural rule.

### Single / zero event parity
- 1 event: bare `EventPromotionCard`, no pager, no dots, no chip, no timer — byte-for-byte today's look.
- 0 events: no pager item at all (slot disappears).

---

## 7. List tab (`ListScreen.kt` + `EventListBanner.kt`)

### Cycling banner
- `EventListBanner` stays **stateless**, 36dp, renders **one** event. `ListScreen` drives `val idx by rememberCyclingIndex(activeEvents.size, 3500, resetKey = manualTick)` and renders `EventListBanner(activeEvents[idx])`.
- **Remove `EventListBanner`'s internal `Modifier.clickable(onTap)`** (line 44) so tap is not double-wired; `ListScreen` owns the gesture.

### Swipe-vs-tap — single gesture pass (no layered detectors)
Layering `clickable` + a drag detector races (drag consumes events → tap leaks/starves). `detectHorizontalDragGestures` only fires after slop, so its "else → navigate" branch is dead for a pure tap. Use **one** `pointerInput` with `awaitEachGesture`:
```kotlin
Modifier.pointerInput(activeEvents.size) {
    awaitEachGesture {
        awaitFirstDown()
        var dx = 0f
        do {
            val e = awaitPointerEvent(); dx += e.changes.sumOf { it.positionChange().x.toDouble() }.toFloat()
        } while (e.changes.any { it.pressed })
        val threshold = viewConfiguration.touchSlop * SWIPE_FACTOR     // ~2–3×
        when {
            dx < -threshold -> { manualTick++; /* next */ }
            dx >  threshold -> { manualTick++; /* prev */ }
            else -> onEventTap(activeEvents[idx].id)                   // pure tap → detail
        }
    }
}
```
- The mockup's 28px is a CSS-px literal; raw `touchSlop` (~6.5dp) fires too early. Use `touchSlop * SWIPE_FACTOR` (~2–3×) — a conscious calibration, not a silent 1:1.
- `awaitEachGesture` (total-travel-on-up) is also more iOS-robust (iOS reports taps as fast micro-drags). Note: the review corrected the earlier "codebase avoids `clickable` due to an iOS `onClick` bug" framing — that's false (`clickable.onClick` works on iOS; issue #3417 is about `collectIsPressedAsState`). We hand-roll here purely for swipe/tap disambiguation.
- A manual swipe bumps `manualTick`, which is the cycling driver's `resetKey` ⇒ restarts both countdown and progress bar.

### Progress bar (single clock)
A 2dp full-width white (~35% alpha) bar at the slot's bottom, keyed on the **same** index the driver returns (single source of truth) — never a free-running `infiniteRepeatable` (second clock ⇒ drift/desync on swipe):
```kotlin
val progress = remember { Animatable(0f) }
LaunchedEffect(idx, activeEvents.size, autoCycle) {
    progress.snapTo(0f)
    if (activeEvents.size > 1 && autoCycle) progress.animateTo(1f, tween(3500, easing = LinearEasing))
}
```
Cross-surface phase alignment (List vs Map both 3.5s) is explicitly **not** required.

### Banner swap transition
Opacity-only `Crossfade` (~150–200ms tween, matching `App.kt`). **No `translateY` slide** (gratuitous, no precedent). Off-screen: all effects die with composition ⇒ pause together (#5).

### Filter chips (decision #7)
Render **one chip per active event** (each in its `brandColor`), all toggling the same `eventOnly` boolean. The existing single `GallrEventFilterChip` generalizes to a loop over `activeEvents`.

### Single / zero parity
1 event: no cycle, no progress bar, no swipe (tap still navigates) — today's behavior. 0 events: no banner slot.

---

## 8. Map tab (`MapScreen.kt` + `EventMapFab.kt`) — circular cover-image FAB (decision #8)

Diverges from the mockup's square label. The FAB **keeps** its 60dp `CircleShape` + `AsyncImage` cover and cycles by **cross-fading the cover image + brand ring color** across active events.

- `EventMapFab` stays **stateless** (caller passes the current event). `MapScreen` drives `val idx by rememberCyclingIndex(activeEvents.size, 3500)` and passes `activeEvents[idx]`.
- Cross-fade (~260ms): `Crossfade(targetState = event)` so the new cover image + ring color fade in as the old fades out. Fallback to the solid brand circle when `coverImageUrl == null` (as today).
- **Tap-only** (no swipe — too small a target). Tap navigates to the **currently shown** event's detail.
- Keep size at the shipped **60dp** (mockup's 56 is moot now that we keep the circle; both clear the 44dp min). The spec's "no shape/size changes" file-table row is satisfied by keeping the circle; the body's square-label mandate is intentionally dropped per decision #8.
- **Accessibility fix (pre-existing):** replace `contentDescription = null` (EventMapFab.kt:48) with a real description on the interactive container: `"{localizedName} · {statusEyebrow}"` (e.g. "KIAF Seoul 2026, NOW ON"), updated as it cycles. Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`.
- Map markers/pins: **out of scope** (decision #11).

---

## 9. Accessibility & reduced motion (decision #10)

### Reduced-motion / screen-reader gate (`expect/actual`)
CMP 1.8.0 commonMain has no reduce-motion or screen-reader flag. Add:
```kotlin
// commonMain
@Composable expect fun isReduceMotionOrScreenReaderActive(): Boolean
```
- **Android actual:** `AccessibilityManager.isTouchExplorationEnabled` OR `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f`.
- **iOS actual:** `UIAccessibility.isReduceMotionEnabled` OR `UIAccessibility.isVoiceOverRunning`.

When true: auto-advance timers do **not** start (treated like `count <= 1` for the timer only). All events still render; dots show; the user swipes/taps manually. This is the WCAG 2.2.2 story and makes the motion choice defensible (motion gated behind "not reduced").

### Semantics
- Real `contentDescription` on the interactive container of pager pages, banner, and FAB (not the decorative `AsyncImage`). Fix `EventPromotionCard.kt:54` and `EventMapFab.kt:48` `null` descriptions.
- `liveRegion = LiveRegionMode.Polite` on the cycling banner + FAB (symbols available in CMP 1.8.0 commonMain).
- The 36dp banner is a known full-width below-44dp exception (don't resize); tap target covers full 36dp × full width.

---

## 10. DESIGN.md revision (decision #9)

DESIGN.md's Motion section currently claims "No motion or positional animation. System defaults only" — already contradicted by shipped `AnimatedVisibility`, `AnimatedContent` fades, spring bounces, and an infinite shimmer. Revise it to formally sanction **functional motion** for a rich UI/UX:

- Rewrite **Motion** to: state feedback uses fast opacity/color shifts; functional motion (crossfade, timing-cue progress indicators, auto-advancing carousels) is sanctioned when it communicates state or liveness; decorative/positional motion is still avoided unless it carries meaning.
- Add a **Decisions-Log** row: `2026-06-08 | Sanction functional motion (crossfade, progress cues, auto-cycling event surfaces) | Communicates liveness for multi-event promotion; aligns the doc with shipped patterns`.
- Note the `CircleShape` FAB stays a sanctioned exception (already documented for avatars).

---

## 11. Test plan (TDD)

Idiom (existing): `UnconfinedTestDispatcher` + `Dispatchers.setMain/resetMain` (`@BeforeTest`/`@AfterTest`) + `runTest(dispatcher)` + `backgroundScope.launch { flow.collect {} }` + `advanceUntilIdle()`.

**Doc-accuracy note:** the source spec's "12 existing VM tests" refers to the Editor VMs; the only `TabsViewModel` test in commonTest is `TabsViewModelSignUpNudgeTest`. This plan adds a new `TabsViewModelActiveEventsTest` and does not edit the (unrelated) editor tests.

### VM tests (`TabsViewModelActiveEventsTest`)
The fake repo must support a **mutable/settable** active-event list (the existing `FakeEventRepository` hardcodes `emptyList()`; case 4 needs a non-empty→empty transition via `vm.refresh()`).
1. **Two events** → `activeEvents` contains BOTH ids (the literal regression). `_activeEventsById.keys` as a sanity anchor.
2. **Zero events** → `activeEvents` empty; derived single slot empty.
3. **eventOnly across 2 events** → exhibitions linked to *either* event survive; one linked to a non-active event is excluded (proves `==` → `in`).
4. **Auto-reset on empty** → `eventOnly = true`, list collapses to empty via `refresh()` → `eventOnly == false`.
5. **Shrink 2→1** → `eventOnly` STAYS true (no spurious clear); survivor's exhibitions match; removed event's drop out.
6. **Transient-empty guard** → with `eventOnly = true` and a momentarily-empty active set, `filteredExhibitions` is not emptied by the predicate.
7. (cheap) **One event** → exactly one id; today's behavior.
- **Skip** the 3-event case (scaling restatement) and a VM-layer ordering test (already covered by `EventRepositoryTest.kt`; the VM consumes the pre-sorted list verbatim).

### Cycling-driver tests (pure functions — `CyclingIndexTest`)
Only the pure index math is unit-tested (no Compose UI harness exists). The timer + `resetKey` reset are exercised by **manual QA** (the wrapper is a thin, untested shell around the tested functions).
- `count = 3` → `nextCyclingIndex` walks 0→1→2→0 (wrap).
- `count <= 0` → `nextCyclingIndex` returns 0 (defensive).
- `clampCyclingIndex(raw, count)` always in `[0, count)` across shrink (e.g. count 2→1 at raw=1 returns 0) and grow (1→3); negative-`raw` defence via `Int.mod`.
- Timer no-op for `count <= 1`, off-screen pause, and `resetKey`→page-0 restart: **manual QA only** (timer lives in the composition-scoped effect).

### Manual / QA-only
- Pause-when-off-screen (#5): switch tabs, confirm halt + sane resume.
- Featured `PagerState` clamp/grow on shrink: swipe to last, drop 2→1, confirm no crash/blank + auto-advance resumes.
- iOS near-45° diagonal swipe on the pager (pointer slop differs from Android).
- Reduced-motion: enable iOS Reduce Motion / Android touch-exploration → confirm no auto-advance, manual swipe/tap still works.

---

## 12. Files Touched

| File | Change |
|---|---|
| `TabsViewModel.kt` | `_activeEvent` → `_activeEvents`; combine 7th input → `_activeEventsById`; predicate `==`→`in`; auto-reset on empty; generic empty-state string; remove `_activeEvent` after migration |
| `ui/components/CyclingIndex.kt` | **New** — `nextCyclingIndex`/`clampCyclingIndex` pure fns + `rememberCyclingIndex` wrapper |
| `FeaturedScreen.kt` | Restructure to single `LazyColumn`; `HorizontalPager` first item (fixed height); mono dots; 4s auto-advance; reveal chip |
| `ListScreen.kt` | Cycling `EventListBanner` slot; single-pass swipe/tap gesture; progress bar; crossfade; N branded filter chips |
| `EventListBanner.kt` | Remove internal `clickable`; stays stateless |
| `MapScreen.kt` | Drive FAB cycling via `rememberCyclingIndex`; pass current event |
| `EventMapFab.kt` | Cross-fade cover image + ring color; real `contentDescription`; `liveRegion`; keep 60dp circle |
| `EventPromotionCard.kt` | Real `contentDescription` (remove `null`); render inside pager |
| `accessibility/ReduceMotion.kt` (+ android/ios actuals) | **New** — `expect/actual isReduceMotionOrScreenReaderActive()` |
| `DESIGN.md` | Revise Motion section + decisions-log row |
| `TabsViewModelActiveEventsTest.kt`, `CyclingIndexTest.kt` | **New** tests; update/extend `FakeEventRepository` to be mutable |

### Out of scope
Google Sheet / Apps Script; the `events` schema (no `is_pinned`, no ordering column); `EventDetailScreen`; map markers/pins; per-event independent filtering.
