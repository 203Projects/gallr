# City-Wide Art Event — Phase 2c Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Map-tab surface treatments for the active city-wide event — per-pin brand-color recoloring (correct-by-event for multi-event simultaneity), a 56dp square FAB with stacked-text event label promoting the first-by-`start_date` event, and navigation wiring — completing the Phase 2 surface arc.

**Architecture:** Client-only changes. `TabsViewModel` gains a second event StateFlow (`activeEventsById: Map<String, Event>`) alongside the existing singular `activeEvent`; map-pin projection (`Exhibition.toMapPin`) gains an `eventsById` parameter so each projected pin carries its own event's brand-color hex; platform `MapView` code decodes the hex into a per-color marker bitmap/image cache; a new `EventMapFab` composable overlays the Map tab's `MapView` via a wrapping `Box`. No backend, schema, or GAS changes.

**Tech Stack:** Kotlin 2.1.20 (KMP), Compose Multiplatform 1.8.0, Material3, existing `com.gallr.shared.util.parseHexColor`, existing `Event.localizedName(lang)`, NaverMap Android Compose SDK, NMapsMap iOS SDK (via cinterop).

**Spec:** `docs/superpowers/specs/2026-04-24-city-wide-event-phase2c-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|------|----------------|
| `shared/src/commonMain/kotlin/com/gallr/shared/util/FabLabel.kt` | Pure string helper: derive FAB stacked label from a localized event name + `AppLanguage`. Lives in `shared/util` (not composeApp) because (a) it's UI-agnostic string manipulation, (b) `composeApp/src/commonTest` doesn't exist today, and (c) `shared/src/commonTest` already has the test infra. |
| `shared/src/commonTest/kotlin/com/gallr/shared/util/FabLabelTest.kt` | Unit tests for the 8 `fabLabel` cases enumerated in spec §7.2. |
| `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt` | Unit tests for `Exhibition.toMapPin(lang, eventsById)` mapping — 5 cases per spec §7.1. |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt` | New Compose composable for the Map-tab FAB — 56dp square, brand color background, white stacked text via `fabLabel`. |

### Files to modify

| Path | Change |
|------|--------|
| `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt` | Add `eventId: String? = null` and `brandColorHex: String? = null` as the last two constructor parameters; extend `Exhibition.toMapPin(lang)` to `toMapPin(lang, eventsById: Map<String, Event> = emptyMap())`. |
| `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` | Rename `loadActiveEvent()` → `loadActiveEvents()` at all 4 sites; add `_activeEventsById` + `activeEventsById` flows; update success/failure handlers to populate both; extend `myListMapPins` and `allMapPins` `combine(...)` to include `_activeEventsById`. |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt` | Add `onEventTap: (String) -> Unit` parameter (3rd, matches Phase 2b pattern); observe `activeEvent`; wrap outer `Column(...)` in a `Box` to host the FAB overlay aligned `BottomEnd` with 16dp padding. |
| `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt` | Generalize `createAccentMarkerBitmap()` → `createMarkerBitmap(colorArgb: Int)`; add file-private `ExhibitionMapPin.brandColorArgb()` extension; replace single `markerIcon` `remember` with a per-color `iconCache: MutableMap<Int, OverlayImage>`; marker loop consults the cache per location. |
| `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt` | Generalize `createAccentMarkerImage()` → `createMarkerImage(red, green, blue)`; add file-private `Int.rgbComponents()` + `ExhibitionMapPin.brandColorArgb()` extensions; replace single `markerImage` `remember` with a per-color `imageCache: MutableMap<Int, NMFOverlayImage>`; marker construction consults the cache. |
| `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` | Add one line to the `MapScreen(...)` call-site: `onEventTap = { id -> selectedEventId = id },` (matches Phase 2b pattern). |

### Files NOT to modify

- `shared/.../repository/EventRepository.kt` and `EventRepositoryImpl.kt` — `getActiveEvents()` already returns `Result<List<Event>>`; Phase 2c takes the full list instead of `.firstOrNull()`. No API change.
- `composeApp/.../ui/tabs/map/MapView.kt` (the `expect` declaration + `MapLocation` / `groupPinsByLocation`) — unchanged. The `expect`/`actual` signature stays; only platform `actual` bodies change.
- Navigation graph structure (`event_detail/{eventId}` route, Phase 1 `EventDetailScreen`) — unchanged; Phase 2c just plumbs one more caller into the existing `selectedEventId` state pattern.
- `FeaturedViewModel`, Featured / List surfaces — unchanged.

---

## Task Breakdown

Tasks are ordered bottom-up: util → model → viewmodel → fab composable → map screen → android platform → ios platform → nav plumbing → verification. Each task ends green + commit. TDD where the task adds testable logic; plain refactor for the platform bitmap generalizations (visually verified).

---

### Task 1: Add `fabLabel` helper + tests

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/util/FabLabel.kt`
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/util/FabLabelTest.kt`

Strict TDD: write the 8 assertions first, watch them fail, implement `fabLabel`, watch them pass.

- [ ] **Step 1: Write the failing test file**

Create `shared/src/commonTest/kotlin/com/gallr/shared/util/FabLabelTest.kt` with:

```kotlin
package com.gallr.shared.util

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class FabLabelTest {

    @Test
    fun `KO single token returns same token`() {
        assertEquals("루프랩", fabLabel("루프랩", AppLanguage.KO))
    }

    @Test
    fun `KO multi token returns first token only`() {
        assertEquals("루프랩", fabLabel("루프랩 부산 2025", AppLanguage.KO))
    }

    @Test
    fun `KO empty input returns empty string`() {
        assertEquals("", fabLabel("", AppLanguage.KO))
    }

    @Test
    fun `EN single token returns uppercased token`() {
        assertEquals("BIENNALE", fabLabel("Biennale", AppLanguage.EN))
    }

    @Test
    fun `EN two tokens returns uppercased and newline joined`() {
        assertEquals("LOOP\nLAB", fabLabel("Loop Lab", AppLanguage.EN))
    }

    @Test
    fun `EN three plus tokens returns only first two tokens`() {
        assertEquals("LOOP\nLAB", fabLabel("Loop Lab Busan 2025", AppLanguage.EN))
    }

    @Test
    fun `EN empty input returns empty string`() {
        assertEquals("", fabLabel("", AppLanguage.EN))
    }

    @Test
    fun `EN extra whitespace is collapsed before token selection`() {
        assertEquals("LOOP\nLAB", fabLabel("  Loop   Lab  ", AppLanguage.EN))
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.util.FabLabelTest"`

Expected: FAIL with "unresolved reference: fabLabel" (implementation file doesn't exist yet).

- [ ] **Step 3: Create the implementation file**

Create `shared/src/commonMain/kotlin/com/gallr/shared/util/FabLabel.kt` with:

```kotlin
package com.gallr.shared.util

import com.gallr.shared.data.model.AppLanguage

/**
 * Derives the stacked text label shown inside the Map-tab Event FAB.
 *
 * - [AppLanguage.KO]: returns the first whitespace-separated token of [localizedName], as-is.
 * - [AppLanguage.EN]: returns the first two whitespace-separated tokens, uppercased and
 *   joined with a newline for stacked display. Returns a single uppercased token when the
 *   name has only one word. Returns the empty string for blank input.
 *
 * Examples:
 *   fabLabel("루프랩 부산 2025", KO) = "루프랩"
 *   fabLabel("Loop Lab Busan 2025", EN) = "LOOP\nLAB"
 *   fabLabel("Biennale", EN) = "BIENNALE"
 */
fun fabLabel(localizedName: String, lang: AppLanguage): String {
    val tokens = localizedName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (lang) {
        AppLanguage.KO -> tokens.firstOrNull().orEmpty()
        AppLanguage.EN -> tokens.take(2).joinToString("\n").uppercase()
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.util.FabLabelTest"`

Expected: PASS. All 8 assertions green.

- [ ] **Step 5: Run the full shared suite — no regressions**

Run: `./gradlew :shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. All pre-existing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/util/FabLabel.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/util/FabLabelTest.kt
git commit -m "feat(shared): add fabLabel helper for Map-tab FAB text"
```

---

### Task 2: Add `eventId` and `brandColorHex` to `ExhibitionMapPin`; extend `toMapPin` + tests

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt`
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt`

Strict TDD: add the test file with all 5 cases (all failing initially — compile error), then add the fields and the extended `toMapPin` overload, watch them pass.

- [ ] **Step 1: Write the failing test file**

Create `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt` with:

```kotlin
package com.gallr.shared.data.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExhibitionMapPinTest {

    private val loopLabEvent = Event(
        id = "loop-lab-busan-2025",
        nameKo = "루프랩 부산 2025",
        nameEn = "Loop Lab Busan 2025",
        descriptionKo = "",
        descriptionEn = "",
        locationLabelKo = "부산 전역",
        locationLabelEn = "Across Busan",
        startDate = LocalDate(2025, 4, 18),
        endDate = LocalDate(2025, 5, 10),
        brandColor = "#0099FF",
        accentColor = "#FF5C5C",
        ticketUrl = null,
        isActive = true,
    )

    private fun exhibition(
        eventId: String? = null,
        latitude: Double? = 37.5665,
        longitude: Double? = 126.9780,
    ) = Exhibition(
        id = "x1",
        nameKo = "전시",
        nameEn = "Exhibition",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "강남구",
        regionEn = "Gangnam",
        openingDate = LocalDate(2026, 4, 1),
        closingDate = LocalDate(2026, 5, 1),
        isFeatured = false,
        isEditorsPick = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        eventId = eventId,
    )

    @Test
    fun `toMapPin with empty eventsById leaves brandColorHex null`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(AppLanguage.KO, emptyMap())
        assertEquals("loop-lab-busan-2025", pin?.eventId)
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with matching event writes brandColorHex from event`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(
            AppLanguage.KO,
            mapOf(loopLabEvent.id to loopLabEvent),
        )
        assertEquals("#0099FF", pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with non matching event leaves brandColorHex null`() {
        val pin = exhibition(eventId = "loop-lab-busan-2025").toMapPin(
            AppLanguage.KO,
            mapOf("other-event" to loopLabEvent.copy(id = "other-event")),
        )
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin with null eventId leaves eventId and brandColorHex null`() {
        val pin = exhibition(eventId = null).toMapPin(
            AppLanguage.KO,
            mapOf(loopLabEvent.id to loopLabEvent),
        )
        assertNull(pin?.eventId)
        assertNull(pin?.brandColorHex)
    }

    @Test
    fun `toMapPin returns null when latitude missing`() {
        val pin = exhibition(latitude = null).toMapPin(AppLanguage.KO, emptyMap())
        assertNull(pin)
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.ExhibitionMapPinTest"`

Expected: FAIL. Compile errors on references to `eventId` / `brandColorHex` on `ExhibitionMapPin`, and on the two-argument `toMapPin(lang, eventsById)` overload (which doesn't exist yet).

- [ ] **Step 3: Modify `ExhibitionMapPin.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt`.

Replace the current `data class` block (lines 9-22):

```kotlin
data class ExhibitionMapPin(
    val id: String,
    val name: String,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val openingDate: LocalDate,
    val closingDate: LocalDate,
) {
    fun localizedDateRange(lang: AppLanguage): String = when (lang) {
        AppLanguage.KO -> "${formatKo(openingDate)} – ${formatKo(closingDate)}"
        AppLanguage.EN -> formatEnRange(openingDate, closingDate)
    }
}
```

with:

```kotlin
data class ExhibitionMapPin(
    val id: String,
    val name: String,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val openingDate: LocalDate,
    val closingDate: LocalDate,
    val eventId: String? = null,         // Phase 2c — carried from Exhibition.eventId
    val brandColorHex: String? = null,   // Phase 2c — "#RRGGBB" resolved at projection time, or null
) {
    fun localizedDateRange(lang: AppLanguage): String = when (lang) {
        AppLanguage.KO -> "${formatKo(openingDate)} – ${formatKo(closingDate)}"
        AppLanguage.EN -> formatEnRange(openingDate, closingDate)
    }
}
```

In the same file, replace the `Exhibition.toMapPin(lang)` extension (current body starts around line 39):

```kotlin
fun Exhibition.toMapPin(lang: AppLanguage): ExhibitionMapPin? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    return ExhibitionMapPin(
        id = id,
        name = localizedName(lang),
        venueName = localizedVenueName(lang),
        latitude = lat,
        longitude = lng,
        openingDate = openingDate,
        closingDate = closingDate,
    )
}
```

with:

```kotlin
fun Exhibition.toMapPin(
    lang: AppLanguage,
    eventsById: Map<String, Event> = emptyMap(),
): ExhibitionMapPin? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    val event = eventId?.let { eventsById[it] }
    return ExhibitionMapPin(
        id = id,
        name = localizedName(lang),
        venueName = localizedVenueName(lang),
        latitude = lat,
        longitude = lng,
        openingDate = openingDate,
        closingDate = closingDate,
        eventId = eventId,
        brandColorHex = event?.brandColor,
    )
}
```

The default `eventsById = emptyMap()` preserves the single-argument signature for any existing caller (the viewmodel call-sites will be upgraded in Task 3).

- [ ] **Step 4: Run the test — verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.ExhibitionMapPinTest"`

Expected: PASS. All 5 assertions green.

- [ ] **Step 5: Run the full shared suite — no regressions**

Run: `./gradlew :shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt
git commit -m "feat(shared): add eventId+brandColorHex to ExhibitionMapPin; extend toMapPin"
```

---

### Task 3: Add `activeEventsById` flow and rewire map pins in `TabsViewModel`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`

Five inter-dependent edits in one file, committed together: add the new StateFlow field, rename `loadActiveEvent` → `loadActiveEvents` at all 4 sites, populate both flows in success/failure, extend `myListMapPins` `combine`, extend `allMapPins` `combine`.

- [ ] **Step 1: Add the new state flow fields**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`.

Find lines 87-88:

```kotlin
    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent
```

Replace with:

```kotlin
    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent

    private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
    val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById
```

- [ ] **Step 2: Rename `loadActiveEvent` → `loadActiveEvents` and populate both flows**

Find lines 90-99 (the current `loadActiveEvent` body):

```kotlin
    private fun loadActiveEvent() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events -> _activeEvent.value = events.firstOrNull() }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvent: ${it.message}")
                    _activeEvent.value = null
                }
        }
    }
```

Replace with:

```kotlin
    private fun loadActiveEvents() {
        viewModelScope.launch {
            eventRepository.getActiveEvents()
                .onSuccess { events ->
                    _activeEventsById.value = events.associateBy { it.id }
                    _activeEvent.value = events.firstOrNull()
                }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveEvents: ${it.message}")
                    _activeEventsById.value = emptyMap()
                    _activeEvent.value = null
                }
        }
    }
```

- [ ] **Step 3: Update the two call-sites (in `refresh()` and `init`)**

Still in `TabsViewModel.kt`:

Find line 326 (inside `refresh()`):

```kotlin
        loadActiveEvent()
```

Replace with:

```kotlin
        loadActiveEvents()
```

Find line 341 (inside `init`):

```kotlin
        loadActiveEvent()
```

Replace with:

```kotlin
        loadActiveEvents()
```

After this step, the file contains **zero** references to `loadActiveEvent` (singular). Verify with:

```bash
grep -n "loadActiveEvent\b" composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt
```

Expected: empty output.

- [ ] **Step 4: Extend `myListMapPins` `combine` to include `_activeEventsById`**

Find the `myListMapPins` block (around line 251-260):

```kotlin
    val myListMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, bookmarkedIds, language) { state, bookmarked, lang ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.id in bookmarked }
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Replace with:

```kotlin
    val myListMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, bookmarkedIds, language, _activeEventsById) { state, bookmarked, lang, eventsById ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.id in bookmarked }
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Note: the typed 4-arg `combine` overload returns a 4-arg destructured lambda (Kotlin `combine` has typed overloads for 2-5 sources); no `values[...]` indexing needed.

- [ ] **Step 5: Extend `allMapPins` `combine` similarly**

Find the `allMapPins` block (around line 262-270):

```kotlin
    val allMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, language) { state, lang ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Replace with:

```kotlin
    val allMapPins: StateFlow<List<ExhibitionMapPin>> =
        combine(_allExhibitions, language, _activeEventsById) { state, lang, eventsById ->
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            (state as? ExhibitionListState.Success)
                ?.exhibitions
                ?.filter { it.closingDate >= today }
                ?.mapNotNull { it.toMapPin(lang, eventsById) }
                ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

- [ ] **Step 6: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify shared tests still pass**

Run: `./gradlew :shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt
git commit -m "feat(map): load all active events into TabsViewModel for per-pin coloring"
```

---

### Task 4: Create `EventMapFab` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt`

Single new file. Visual verification comes at the device-smoke stage (Task 9). No per-composable unit test — `fabLabel` is already tested in Task 1; the composable is thin glue.

- [ ] **Step 1: Create the file**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt` with:

```kotlin
package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.fabLabel
import com.gallr.shared.util.parseHexColor

/**
 * Persistent floating button shown on the Map tab when an event is active.
 * 56dp square, brand-color background, white stacked text label derived via
 * [fabLabel]. Tap invokes [onTap], which the caller wires to the Event Detail
 * route.
 */
@Composable
fun EventMapFab(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
    val label = fabLabel(event.localizedName(lang), lang)

    Box(
        modifier = modifier
            .shadow(elevation = 4.dp, shape = RectangleShape)
            .size(56.dp)
            .background(brand)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp,
                letterSpacing = 0.04.em,
            ),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt
git commit -m "feat(map): add EventMapFab composable for Phase 2c"
```

---

### Task 5: Integrate FAB into `MapScreen` and add `onEventTap` parameter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

Four edits: signature gains `onEventTap`, observe `activeEvent`, wrap outer `Column` in `Box`, render FAB when `activeEvent != null`. Like Phase 2b's Task 5, this commit leaves the full-app compile transiently broken because the call-site in `App.kt` doesn't yet pass `onEventTap` — Task 7 fixes.

- [ ] **Step 1: Add imports**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`.

Append these to the existing import block (alphabetical order; skip any already present — use `grep` to verify before adding):

```kotlin
import androidx.compose.foundation.layout.Box
import com.gallr.app.ui.components.EventMapFab
```

Verification command:

```bash
grep -nE "^import (androidx\\.compose\\.foundation\\.layout\\.Box|com\\.gallr\\.app\\.ui\\.components\\.EventMapFab)$" composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt
```

Expected after edit: both imports present.

- [ ] **Step 2: Extend the `MapScreen` function signature**

Find (at line 48-54):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 3: Observe `activeEvent`**

Find the block of `collectAsState` observations near the top of the `MapScreen` body (lines 55-58):

```kotlin
    val mapMode by viewModel.mapDisplayMode.collectAsState()
    val myListPins by viewModel.myListMapPins.collectAsState()
    val allPins by viewModel.allMapPins.collectAsState()
    val lang by viewModel.language.collectAsState()
```

Replace with (adds one line at the end):

```kotlin
    val mapMode by viewModel.mapDisplayMode.collectAsState()
    val myListPins by viewModel.myListMapPins.collectAsState()
    val allPins by viewModel.allMapPins.collectAsState()
    val lang by viewModel.language.collectAsState()
    val activeEvent by viewModel.activeEvent.collectAsState()
```

- [ ] **Step 4: Wrap the outer `Column` in a `Box` and render the FAB overlay**

Find the outer Column (line 77) and its closing brace. The current structure is:

```kotlin
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            // … existing body …
        )
        HorizontalDivider(…)

        if (mapMode == MapDisplayMode.MY_LIST && myListPins.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(GallrSpacing.screenMargin)) {
                Text(…)
            }
        }

        MapView(…)
    }
```

…followed by the `selectedPin?.let { … }` and `selectedLocation?.let { … }` dialog/bottom-sheet blocks at the top-level of the composable function.

**Edit:** replace the outer `Column(modifier = modifier.fillMaxSize()) {` opener and its closing `}` (the one immediately after `MapView(...)`) with a `Box` wrapping a `Column` plus the FAB overlay. Concretely:

Find:

```kotlin
    Column(modifier = modifier.fillMaxSize()) {
```

Replace with:

```kotlin
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
```

Find the `}` that closes the outer Column — immediately after `MapView(...)`, before `selectedPin?.let { pin ->`. The structure in the current file is:

```kotlin
        MapView(
            locations = locations,
            // …
        )
    }

    // ── Single exhibition dialog ────────
    selectedPin?.let { pin ->
```

Replace the `    }` (the outer Column close) with:

```kotlin
        }
        activeEvent?.let { event ->
            EventMapFab(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
```

Concretely the transformation converts:

```kotlin
    Column(modifier = modifier.fillMaxSize()) {
        // … existing content …
        MapView(…)
    }
    // selectedPin and selectedLocation blocks follow at fn-body level
```

into:

```kotlin
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // … existing content …
            MapView(…)
        }
        activeEvent?.let { event ->
            EventMapFab(
                event = event,
                lang = lang,
                onTap = { onEventTap(event.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
    // selectedPin and selectedLocation blocks follow at fn-body level (unchanged)
```

`Alignment` and `padding` symbols are already imported at the top of the file — no new imports required for those. (The `Alignment.BottomEnd` reference uses the existing `androidx.compose.ui.Alignment` import.)

- [ ] **Step 5: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: **FAIL** with a single error at `App.kt:267` (or thereabouts) — "No value passed for parameter 'onEventTap'". That is the intended failure mode: `ListScreen`'s equivalent issue was handled by Phase 2b's nav task, and `MapScreen` now has the same situation. Task 7 will plumb the caller.

**IMPORTANT:** If the compile fails inside `MapScreen.kt` itself (e.g., unresolved reference, mismatched braces), fix inside `MapScreen.kt` before committing — do NOT commit a file that fails to compile in isolation. The ONLY acceptable failure at this step is the `App.kt` caller error.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt
git commit -m "feat(map): render EventMapFab overlay and wire onEventTap on MapScreen"
```

This commit is intentionally left in a transiently-broken state for full-app compile; Task 7 resolves. Rationale matches Phase 2b's same pattern: separating the screen change from the nav-plumbing keeps each commit small and focused.

---

### Task 6: Generalize Android marker bitmap for per-color caching

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`

Refactor `createAccentMarkerBitmap()` to `createMarkerBitmap(colorArgb: Int)`, add a file-private `ExhibitionMapPin.brandColorArgb()` helper, replace the single `markerIcon` `remember` with a per-color `iconCache`, and update the marker loop to pick a per-location color.

- [ ] **Step 1: Add imports**

Open `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`.

Add these imports (alphabetical merge with the existing block; verify each with `grep` before adding):

```kotlin
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.util.parseHexColor
```

Verification:

```bash
grep -nE "^import (com\\.gallr\\.shared\\.data\\.model\\.ExhibitionMapPin|com\\.gallr\\.shared\\.util\\.parseHexColor)$" composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt
```

- [ ] **Step 2: Rename `createAccentMarkerBitmap` → `createMarkerBitmap(colorArgb)` and parameterize the fill color**

Find (lines 29-54):

```kotlin
private fun createAccentMarkerBitmap(): Bitmap {
    val w = 48
    val h = 64
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_ARGB }

    // Circle head
    val radius = w / 2f
    canvas.drawCircle(radius, radius, radius, paint)

    // Pointed tail
    val path = Path().apply {
        moveTo(0f, radius)
        lineTo(radius, h.toFloat())
        lineTo(w.toFloat(), radius)
        close()
    }
    canvas.drawPath(path, paint)

    // White inner dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(radius, radius, radius * 0.35f, dotPaint)

    return bitmap
}
```

Replace with:

```kotlin
private fun createMarkerBitmap(colorArgb: Int): Bitmap {
    val w = 48
    val h = 64
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }

    // Circle head
    val radius = w / 2f
    canvas.drawCircle(radius, radius, radius, paint)

    // Pointed tail
    val path = Path().apply {
        moveTo(0f, radius)
        lineTo(radius, h.toFloat())
        lineTo(w.toFloat(), radius)
        close()
    }
    canvas.drawPath(path, paint)

    // White inner dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(radius, radius, radius * 0.35f, dotPaint)

    return bitmap
}

/**
 * Resolves an ARGB int from a pin's hex brandColor, or null if missing/malformed.
 * parseHexColor returns a Long with 0xFF alpha pre-applied; signed narrowing to Int
 * preserves the bit pattern for Android's Paint.color consumption.
 */
private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }
```

- [ ] **Step 3: Replace the single `markerIcon` cache with a per-color `iconCache`**

Find (inside the composable, around line 68):

```kotlin
    val markerIcon = remember { OverlayImage.fromBitmap(createAccentMarkerBitmap()) }
```

Replace with:

```kotlin
    val iconCache = remember { mutableMapOf<Int, OverlayImage>() }
```

- [ ] **Step 4: Update the marker loop to pick a per-location color**

Find the `locations.forEach { location -> Marker(...) }` block inside `NaverMap` (lines 82-93):

```kotlin
        locations.forEach { location ->
            Marker(
                state = MarkerState(position = LatLng(location.latitude, location.longitude)),
                captionText = location.label,
                icon = markerIcon,
                onClick = {
                    onLocationTap(location)
                    true
                },
            )
        }
```

Replace with:

```kotlin
        locations.forEach { location ->
            // Mixed-event locations (multiple pins at same coords with different eventIds):
            // the first pin's color wins. Tap opens the existing bottom sheet which lists
            // every pin individually, so no information is lost.
            val pinColorArgb = location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB
            val icon = iconCache.getOrPut(pinColorArgb) {
                OverlayImage.fromBitmap(createMarkerBitmap(pinColorArgb))
            }
            Marker(
                state = MarkerState(position = LatLng(location.latitude, location.longitude)),
                captionText = location.label,
                icon = icon,
                onClick = {
                    onLocationTap(location)
                    true
                },
            )
        }
```

- [ ] **Step 5: Verify compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: **FAIL** with the same pre-existing `App.kt:267` "No value passed for parameter 'onEventTap'" error from Task 5. No new errors.

If an Android-specific compile error appears (e.g., unresolved `brandColorArgb` or `parseHexColor`), fix before committing.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt
git commit -m "feat(map-android): per-color marker bitmap cache for event pins"
```

---

### Task 7: Generalize iOS marker image for per-color caching

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`

iOS mirror of Task 6. `createAccentMarkerImage()` becomes `createMarkerImage(red, green, blue)`; add `Int.rgbComponents()` + `ExhibitionMapPin.brandColorArgb()` helpers; replace `markerImage` `remember` with a `imageCache`; marker `update` lambda consults the cache.

- [ ] **Step 1: Add imports**

Open `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`.

Add these imports (alphabetical merge; verify before adding):

```kotlin
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.util.parseHexColor
```

Verification:

```bash
grep -nE "^import (com\\.gallr\\.shared\\.data\\.model\\.ExhibitionMapPin|com\\.gallr\\.shared\\.util\\.parseHexColor)$" composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt
```

- [ ] **Step 2: Add `ACCENT_ARGB` constant and helper extensions**

Add these file-private definitions immediately after the existing `INITIAL_ZOOM` constant (around line 37):

```kotlin
// Default pin color when no event is linked or the hex is malformed.
// #FF5400 with alpha 0xFF — matches the Android ACCENT_ARGB.
private const val ACCENT_ARGB: Int = 0xFFFF5400.toInt()

private fun Int.rgbComponents(): Triple<Double, Double, Double> {
    val r = ((this shr 16) and 0xFF) / 255.0
    val g = ((this shr 8) and 0xFF) / 255.0
    val b = (this and 0xFF) / 255.0
    return Triple(r, g, b)
}

private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }
```

- [ ] **Step 3: Rename `createAccentMarkerImage` → `createMarkerImage(r, g, b)`**

Find (lines 56-89):

```kotlin
@OptIn(ExperimentalForeignApi::class)
private fun createAccentMarkerImage(): UIImage {
    val w = 32.0
    val h = 44.0
    val radius = w / 2.0

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, UIScreen.mainScreen.scale)

    val accent = UIColor(red = 1.0, green = 0.325, blue = 0.0, alpha = 1.0) // #FF5400

    // Circle head
    accent.setFill()
    val circle = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, w, w))
    circle.fill()

    // Pointed tail
    val tail = UIBezierPath()
    tail.moveToPoint(CGPointMake(0.0, radius))
    tail.addLineToPoint(CGPointMake(radius, h))
    tail.addLineToPoint(CGPointMake(w, radius))
    tail.closePath()
    tail.fill()

    // White inner dot
    UIColor.whiteColor.setFill()
    val dot = UIBezierPath.bezierPathWithOvalInRect(
        CGRectMake(radius - radius * 0.35, radius - radius * 0.35, radius * 0.7, radius * 0.7)
    )
    dot.fill()

    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image!!
}
```

Replace with:

```kotlin
@OptIn(ExperimentalForeignApi::class)
private fun createMarkerImage(red: Double, green: Double, blue: Double): UIImage {
    val w = 32.0
    val h = 44.0
    val radius = w / 2.0

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, UIScreen.mainScreen.scale)

    val color = UIColor(red = red, green = green, blue = blue, alpha = 1.0)

    // Circle head
    color.setFill()
    val circle = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, w, w))
    circle.fill()

    // Pointed tail
    val tail = UIBezierPath()
    tail.moveToPoint(CGPointMake(0.0, radius))
    tail.addLineToPoint(CGPointMake(radius, h))
    tail.addLineToPoint(CGPointMake(w, radius))
    tail.closePath()
    tail.fill()

    // White inner dot
    UIColor.whiteColor.setFill()
    val dot = UIBezierPath.bezierPathWithOvalInRect(
        CGRectMake(radius - radius * 0.35, radius - radius * 0.35, radius * 0.7, radius * 0.7)
    )
    dot.fill()

    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image!!
}
```

- [ ] **Step 4: Replace the single `markerImage` cache with a per-color `imageCache`**

Find (inside the composable, around line 105):

```kotlin
    val markerImage = remember { NMFOverlayImage.overlayImageWithImage(createAccentMarkerImage()) }
```

Replace with:

```kotlin
    val imageCache = remember { mutableMapOf<Int, NMFOverlayImage>() }
```

- [ ] **Step 5: Update the marker `update` lambda to consult the cache**

Find the marker-creation block inside `update = { _ -> ... }` (lines 129-145):

```kotlin
            update = { _ ->
                val mapView = mapRef[0] ?: return@UIKitView
                activeMarkers.forEach { it.mapView = null }
                activeMarkers.clear()
                locations.forEach { location ->
                    val marker = NMFMarker()
                    marker.position = NMGLatLng.latLngWithLat(location.latitude, lng = location.longitude)
                    marker.captionText = location.label
                    marker.iconImage = markerImage
                    marker.touchHandler = { _ ->
                        onLocationTap(location)
                        true
                    }
                    marker.mapView = mapView
                    activeMarkers.add(marker)
                }
            },
```

Replace with:

```kotlin
            update = { _ ->
                val mapView = mapRef[0] ?: return@UIKitView
                activeMarkers.forEach { it.mapView = null }
                activeMarkers.clear()
                locations.forEach { location ->
                    // Mixed-event locations (multiple pins at same coords with different eventIds):
                    // the first pin's color wins. Tap opens the existing bottom sheet which lists
                    // every pin individually, so no information is lost.
                    val pinColorArgb = location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB
                    val image = imageCache.getOrPut(pinColorArgb) {
                        val (r, g, b) = pinColorArgb.rgbComponents()
                        NMFOverlayImage.overlayImageWithImage(createMarkerImage(r, g, b))
                    }
                    val marker = NMFMarker()
                    marker.position = NMGLatLng.latLngWithLat(location.latitude, lng = location.longitude)
                    marker.captionText = location.label
                    marker.iconImage = image
                    marker.touchHandler = { _ ->
                        onLocationTap(location)
                        true
                    }
                    marker.mapView = mapView
                    activeMarkers.add(marker)
                }
            },
```

- [ ] **Step 6: Verify compile for iOS**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Expected: BUILD SUCCESSFUL. (The `App.kt` pre-existing compile break is in commonMain; the iOS-specific Kotlin compile target compiles the iosMain source set + its commonMain dependencies and does surface the same `App.kt` issue at link time only, so `compileKotlinIosSimulatorArm64` may or may not surface it depending on Gradle config — if it does, that's still the pre-existing break from Task 5, not an iOS-specific regression. If a NEW iOS error appears, fix before committing.)

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt
git commit -m "feat(map-ios): per-color marker image cache for event pins"
```

---

### Task 8: Plumb `onEventTap` through navigation (`App.kt`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

One-line addition to the `MapScreen(...)` call-site, mirroring Phase 2b's Task 7 edit for `ListScreen`.

- [ ] **Step 1: Add `onEventTap` to the `MapScreen(...)` call**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`.

Find the `MapScreen(...)` invocation (around line 267, inside the `when (tab) { 2 -> ... }` branch):

```kotlin
                            2 -> MapScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                modifier = Modifier.padding(innerPadding),
                            )
```

Replace with (adds one line between `onExhibitionTap` and `modifier`):

```kotlin
                            2 -> MapScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                modifier = Modifier.padding(innerPadding),
                            )
```

Indentation must match the surrounding lines exactly.

- [ ] **Step 2: Verify full-app compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: BUILD SUCCESSFUL. The Task 5 transient break is now resolved.

- [ ] **Step 3: Verify full Android assembly**

Run: `./gradlew :composeApp:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify iOS compile**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(nav): wire map-tab FAB tap to event_detail route"
```

---

### Task 9: Android device smoke test

**Files:** none (verification task; no commit)

Precondition: a Loop Lab Busan (or equivalent) event row exists with `is_active = true` and at least 2 exhibitions linked via `event_id` with non-null lat/lng. All true today as of Phase 2b merge.

- [ ] **Step 1: Build and install**

Run: `./gradlew :composeApp:installDebug`

Expected: `Installed on 1 device.`

- [ ] **Step 2: Force-close and relaunch**

Run: `adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`

- [ ] **Step 3: Verify pin recoloring on MY LIST sub-tab**

On the device, open the Map tab. The MY LIST sub-tab should be selected by default.
- Bookmarked Loop Lab exhibitions (pre-existing bookmarks from Phase 2b smoke) render as **blue pins** (`#0099FF`).
- Bookmarked non-event exhibitions render as **orange pins** (`#FF5400`, unchanged from pre-2c).

If no bookmarks exist: open the List tab, bookmark at least one Loop Lab exhibition + one non-event exhibition, return to Map → MY LIST.

- [ ] **Step 4: Verify pin recoloring on ALL sub-tab**

Switch to the ALL sub-tab. The full map should show a mix of blue (event-linked) and orange (regular) pins.

- [ ] **Step 5: Verify FAB on both sub-tabs**

- FAB is visible at bottom-right on both MY LIST and ALL sub-tabs.
- Label reads `루프랩` (Korean app) or `LOOP\nLAB` stacked (English app).

- [ ] **Step 6: Verify FAB tap navigates to Event Detail**

Tap the FAB. Expected: navigates to the Phase 1 Event Detail screen showing Loop Lab Busan. Back → returns to Map tab with the previous sub-tab state preserved.

- [ ] **Step 7: Verify language toggle**

Toggle app language (Profile → Settings, or wherever the language switch lives). The FAB label should swap: KO `루프랩` ↔ EN `LOOP\nLAB`. Pin colors are unchanged — the brand color is language-independent.

- [ ] **Step 8: Verify tapping a blue (event-linked) pin opens the existing dialog / bottom sheet**

Tap a blue pin. Either the single-pin AlertDialog or the multi-pin ModalBottomSheet appears — identical behavior to pre-2c, just with the blue marker underneath. Tap through to VIEW DETAILS → Exhibition Detail opens normally.

- [ ] **Step 9: Capture logcat for unexpected errors**

Run: `adb logcat -d | grep -iE "FATAL|AndroidRuntime" | tail -20`

Expected: empty.

- [ ] **Step 10: No commit** (verification only)

---

### Task 10: iOS simulator smoke test

**Files:** none (verification task; no commit)

- [ ] **Step 1: Build for iOS simulator**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -derivedDataPath ./build/DerivedData build
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 2: Install on the booted simulator**

Locate the booted simulator UDID:

```bash
xcrun simctl list devices booted | head -5
```

If no simulator is booted, boot one:

```bash
xcrun simctl boot "iPhone 16 Pro"
```

Install the app:

```bash
APP_PATH=/Users/hanshin/Documents/Projects/gallr/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl install booted "$APP_PATH"
xcrun simctl launch booted com.gallr.app
```

- [ ] **Step 3: Repeat Task 9 steps 3-8 on the simulator**

Blue event pins, orange regular pins, FAB visible on both sub-tabs, FAB label matches the app language, tap → Event Detail, taps on blue pins open the existing dialog/bottom sheet.

Verify in both light and dark mode (switch via `xcrun simctl ui booted appearance dark` / `light`). Brand color is event-owned so both should render identically.

- [ ] **Step 4: No commit** (verification only)

---

## Self-Review

**1. Spec coverage (cross-check against `docs/superpowers/specs/2026-04-24-city-wide-event-phase2c-design.md`):**

- §2 goals: per-pin brand-color recoloring → Tasks 2 (model), 3 (viewmodel), 6+7 (platform). FAB visible bottom-right, both sub-tabs → Tasks 4+5. Multi-event correctness → Task 3 (`activeEventsById` is a map, Task 2's `toMapPin` reads per-pin). Collapse when no event → Tasks 4+5 (FAB gated on `activeEvent?.let`), Tasks 6+7 (default ACCENT_ARGB fallback when `brandColorHex == null`).
- §4 decisions: all reflected in the corresponding tasks.
- §5.1 `ExhibitionMapPin` model → Task 2.
- §5.2 `TabsViewModel` changes → Task 3.
- §5.3 `EventMapFab` composable → Task 4. (`fabLabel` helper → Task 1 in `shared/util` — noted deviation from spec which put it in `EventMapFab.kt`; rationale: `composeApp/commonTest` source set doesn't exist and adding it is out of scope for a single helper.)
- §5.4 `MapScreen` integration → Task 5.
- §5.5 Android `MapView` → Task 6.
- §5.6 iOS `MapView` → Task 7.
- §5.7 `App.kt` nav → Task 8.
- §6 edge cases: all covered. Fallback behavior on malformed `brand_color` → Tasks 4 (FAB → `Color.Black`) + 6/7 (pins → `ACCENT_ARGB`). Event-expiry → Task 3 (`loadActiveEvents` failure clears both flows). Multi-event at same coord → Tasks 6+7 (`location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB` with explanatory comment).
- §7 testing: §7.1 → Task 2. §7.2 → Task 1. §7.3 (`TabsViewModelTest` extension) — **intentionally omitted** per spec §7.3's own note ("matching Phase 2b, `TabsViewModelTest` infra is not introduced in Phase 2c either"). §7.4 manual smoke → Tasks 9+10. §7.5 no visual regression — consistent with decision.
- §8 rollout: single ship. Plan produces a single branch (`032-city-wide-event-phase2c`) ready for one PR.
- §9 open items: `EventStateHolder` explicitly out of scope; logo asset out of scope; pin clustering out of scope. Plan does not address any — correct.

No spec requirement lacks a task.

**Deviation note (one):** Spec §5.3 places the `fabLabel` helper inside `EventMapFab.kt` as `internal fun fabLabel(...)`. The plan moves it to `shared/src/commonMain/kotlin/com/gallr/shared/util/FabLabel.kt` as `fun fabLabel(...)` (public in shared) because `composeApp` has no commonTest source set configured and introducing one is out of plan scope. The spec's §7.2 test cases are preserved verbatim in Task 1. The helper function name and semantics are identical.

**2. Placeholder scan:** None. Every step has exact file paths, exact commands, exact expected output, and complete code. Task 5's "if compile fails inside `MapScreen.kt` itself" branch is a guard, not a placeholder — it explicitly names which failure mode is acceptable and which requires a pre-commit fix.

**3. Type consistency:**
- `eventId: String?` — Task 2 declares on `ExhibitionMapPin`; Task 2 sets it in `toMapPin`; Task 2's test asserts on it.
- `brandColorHex: String?` — Task 2 declares on `ExhibitionMapPin`; Task 2 sets it from `event?.brandColor`; Tasks 6/7 read it via `ExhibitionMapPin.brandColorArgb()`.
- `activeEventsById: StateFlow<Map<String, Event>>` — Task 3 declares; Task 3's `combine` sources read it.
- `activeEvent: StateFlow<Event?>` — unchanged throughout; Task 5's `MapScreen` observes it.
- `fabLabel(name: String, lang: AppLanguage): String` — Task 1 declares; Task 4 consumes.
- `createMarkerBitmap(colorArgb: Int): Bitmap` and `createMarkerImage(r: Double, g: Double, b: Double): UIImage` — Tasks 6/7 declare; same tasks' marker loops consume.
- `ACCENT_ARGB` — Android has it as `0xFFFF5400.toInt()` already (line 23 of `MapView.android.kt`); Task 7 adds the same constant on iOS with identical value + documented parity.
- `onEventTap: (String) -> Unit` — Task 5 adds to `MapScreen` signature; Task 8 wires from `App.kt`. Matches Phase 2b's `ListScreen` pattern exactly (same callback name, same wiring shape `{ id -> selectedEventId = id }`).
- `ExhibitionMapPin.brandColorArgb(): Int?` — declared twice (once in Task 6 for Android, once in Task 7 for iOS) because each platform file has its own copy per expect/actual convention. Bodies are identical (`brandColorHex?.let { parseHexColor(it)?.toInt() }`). Consistent.

Types and signatures are consistent across tasks. No rename drift.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-24-city-wide-event-phase2c.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
