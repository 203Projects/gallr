# City-Wide Art Event — Phase 2c: Map-Tab Surface Treatments

**Date:** 2026-04-24
**Status:** Spec — pending implementation plan
**Phase:** 2c of a sub-phased Phase 2 (2a/2b/2c)
**Predecessors:**
- Phase 1 — `docs/superpowers/specs/2026-04-22-city-wide-biennale-phase1-design.md` (shipped, PR #39)
- Phase 2a — `docs/superpowers/specs/2026-04-24-city-wide-event-phase2a-design.md` (shipped, part of PR #40)
- Phase 2b — `docs/superpowers/specs/2026-04-24-city-wide-event-phase2b-design.md` (shipped, PR #40)

## 1. Background

Phase 1 shipped the Featured card + Event Detail screen; Phase 2a added hero images and stabilized sync; Phase 2b added List-tab banner, chip, and card edge/label. Phase 2c completes the Phase 2 surface arc by adding Map-tab treatments so users discover event-linked exhibitions on the Map tab as readily as on the List tab.

Three carry-forward items from Phase 2b are addressed here:
- **Map pin recoloring** for event-linked exhibitions
- **Map FAB** that navigates to Event Detail
- **Multi-event pin resolution** (Phase 2b explicitly deferred this because pin color is the surface where "wrong color = wrong event" is a data-correctness bug, not a polish miss)

Two carry-forward items are deliberately NOT addressed:
- **Logo asset shape** — no real asset available; Phase 2c uses a text-based stacked label inside the FAB
- **Shared `EventStateHolder` abstraction** — `TabsViewModel` already owns `activeEvent` and drives Map, List, and (via `FeaturedViewModel` → DI reuse) Featured; extraction isn't needed

No backend, schema, or GAS changes. Pure client-side.

## 2. Goals

- Event-linked exhibition pins on the Map tab render in the event's `brand_color` (same circle silhouette, just recolored) so users can identify event exhibitions at a glance in both MY LIST and ALL sub-tabs.
- A persistent FAB in the bottom-right of the Map tab, visible whenever an event is active (on both sub-tabs), provides a one-tap entry to Event Detail regardless of scroll/zoom state.
- When multiple events are simultaneously active, each pin takes its own event's brand color (correct-by-event). The FAB continues to promote the first-by-`start_date` event, matching Phase 1/2b conventions.
- All three surfaces collapse to zero footprint when no event is active.

## 3. Non-Goals

- Real logo image asset — deferred until an operator supplies one; the FAB uses a stacked text fallback per spec §5.3
- Map-FAB variants (expanded pill, icon-only, etc.) — single-style 56dp square
- FAB expand/collapse animations or scroll-based hide-on-scroll — the FAB is static
- Pin clustering — future polish; Phase 1 carried "biennale vs. single-venue fair distinction affects pin clustering" forward but it's not time-sensitive
- Bottom-sheet updates — the existing multi-pin bottom sheet (Phase 1) is unchanged
- Animated pin-color transitions on event transitions — pins recolor on recomposition; no crossfade
- Changes to the existing pin-grouping-by-coordinates logic in `MapView.kt` — unchanged

## 4. Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Pin style | Same circle+tail silhouette, `event.brandColor` replaces the orange `#FF5400` | Minimal visual change; preserves users' "pin = exhibition location" mental model. Event-linkage reads as "this pin is blue" — sufficient signal without overstating. |
| Pin inner dot | White (unchanged from regular pins) | Phase 1/2b treatments mix brand + accent colors; duplicating on the pin's tiny center dot adds noise. White center keeps the pin readable at small sizes and preserves consistency. |
| FAB shape | 56dp square, 0dp radius | Matches gallr's design-system convention (rest of the app has no rounded corners). |
| FAB content | Stacked text label, first 1-2 tokens of `event.localizedName(lang)` — KO single token, EN two tokens uppercased and newline-joined | No real logo asset exists. Text label follows the "exhibition name pattern" (KO as-is, EN uppercased) that other chips in the app use. |
| FAB placement | Bottom-right, 16dp margin, anchored to the Map tab viewport (not scrolled with map) | Standard FAB convention; accessible without repositioning the map. |
| FAB visibility | Shown on BOTH sub-tabs (MY LIST + ALL) | FAB is an event promotion surface; hiding on MY LIST creates a "where did it go?" moment when toggling. Consistent with Phase 2b's chip that also stays on both List sub-tabs. |
| FAB navigation | Tap → `event_detail/{eventId}` (Phase 1 route) | Reuses Phase 2b's `onEventTap` plumbing pattern exactly. |
| FAB elevation | 4dp shadow | Material convention; ensures FAB stands off the map tiles without overstated weight. |
| Multi-event resolution | Per-pin: `exhibition.eventId` → `activeEventsById[eventId].brandColor` | Guarantees correct color per exhibition when multiple events are active. Phase 2b card treatment already uses `exhibition.eventId`; pins extend the same pattern. |
| `activeEventsById` exposure | Single `StateFlow<Map<String, Event>>` derived in `TabsViewModel`; no separate list exposed | One shape, one consumer path; avoids future callers picking the wrong projection. Cheap to derive via `.associateBy { it.id }`. |
| `activeEvent` (singular) | Unchanged — still first-by-`start_date`, drives banner/chip/card-treatment/FAB | Matches Phase 1/2b convention; multi-event disambiguation is a per-pin concern only. |
| Platform marker bitmap cache | Per-color (ARGB int) cache in the Composable via `remember { mutableMapOf(...) }` | Avoids regenerating the same bitmap per render; naturally scoped to the composition; cleared on recomposition if color set changes. |
| Mixed-event pins at same coordinate | First-pin's color wins for the marker bitmap; bottom sheet shows all individually | Rare edge case. Tap-through disambiguates. Documented in code comment. |
| `Exhibition.toMapPin` extension signature | New second parameter `eventsById: Map<String, Event> = emptyMap()` (default preserves existing callers) | Backward-compatible; tests and any other caller that doesn't need event coloring continue to work unchanged. |
| Malformed `brand_color` | `parseHexColor` returns null → pin falls back to default orange; FAB falls back to `Color.Black` | Mirrors Phase 2b's fallback convention; consistent behavior across all event surfaces. |

## 5. Implementation

### 5.1 Shared — `ExhibitionMapPin` model

**File:** `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt`

Add two nullable fields as the last constructor parameters (last-param convention matches Phase 2a `coverImageUrl` + Phase 2b `eventOnly`):

```kotlin
data class ExhibitionMapPin(
    val id: String,
    val name: String,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val openingDate: LocalDate,
    val closingDate: LocalDate,
    val eventId: String? = null,         // NEW — preserves callers
    val brandColorHex: String? = null,   // NEW — "#RRGGBB" or null
)
```

Extend `Exhibition.toMapPin(lang)` to take an optional `eventsById` map. The existing single-parameter call-site in tests remains valid via default-arg:

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

### 5.2 ViewModel — `TabsViewModel.activeEventsById` and pin flow rewiring

**File:** `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`

Rename `loadActiveEvent()` → `loadActiveEvents()` (still `private` per Phase 2b cleanup). Populate both the singular `_activeEvent` (first) and the new `_activeEventsById` (all):

```kotlin
private val _activeEvent = MutableStateFlow<Event?>(null)
val activeEvent: StateFlow<Event?> = _activeEvent

private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById

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

Callers in `init` and `refresh()` update to the new name.

Phase 2b's auto-reset collector (clears `eventOnly` when `_activeEvent` transitions to null) is unchanged — it reads `_activeEvent` only.

Update `myListMapPins` and `allMapPins` to include `_activeEventsById` as a source and pass it into `toMapPin(lang, eventsById)`:

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

### 5.3 UI — `EventMapFab` composable

**New file:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventMapFab.kt`

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
import com.gallr.shared.util.parseHexColor

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

/**
 * FAB label derivation:
 * - KO: first whitespace-separated token of the localized name, as-is (no uppercase).
 * - EN: first two whitespace-separated tokens, uppercased and newline-joined for stacked display.
 *
 * Examples:
 *   "루프랩 부산 2025" (KO) → "루프랩"
 *   "Loop Lab Busan 2025" (EN) → "LOOP\nLAB"
 *   "Biennale" (EN, single token) → "BIENNALE"
 */
internal fun fabLabel(localizedName: String, lang: AppLanguage): String {
    val tokens = localizedName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (lang) {
        AppLanguage.KO -> tokens.firstOrNull().orEmpty()
        AppLanguage.EN -> tokens.take(2).joinToString("\n").uppercase()
    }
}
```

`fabLabel` is `internal` so it's testable from commonTest without opening it publicly.

### 5.4 UI — `MapScreen` integration

**File:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

Signature gains `onEventTap: (String) -> Unit` (3rd parameter, matching Phase 2b's `ListScreen`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,  // NEW
    modifier: Modifier = Modifier,
)
```

Observe `activeEvent` by adding one `.collectAsState()`:

```kotlin
val activeEvent by viewModel.activeEvent.collectAsState()
```

Wrap the existing `Column(modifier = modifier.fillMaxSize()) { ... }` in an outer `Box` to host the FAB overlay. The existing `Column` becomes the first child, unchanged. The FAB is a second conditional child aligned bottom-end:

```kotlin
Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // existing TabRow, divider, empty state, MapView — unchanged
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

### 5.5 Platform — `MapView.android.kt` per-color bitmap cache

**File:** `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`

Generalize `createAccentMarkerBitmap()` to take a color arg:

```kotlin
private fun createMarkerBitmap(colorArgb: Int): Bitmap {
    val w = 48
    val h = 64
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }

    val radius = w / 2f
    canvas.drawCircle(radius, radius, radius, paint)

    val path = Path().apply {
        moveTo(0f, radius)
        lineTo(radius, h.toFloat())
        lineTo(w.toFloat(), radius)
        close()
    }
    canvas.drawPath(path, paint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(radius, radius, radius * 0.35f, dotPaint)

    return bitmap
}
```

Add a file-private extension alongside `createMarkerBitmap`:

```kotlin
// File-scope (private). Uses com.gallr.shared.util.parseHexColor (add the import if
// it isn't already present in MapView.android.kt).
private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }
```

Inside `@Composable actual fun MapView(...)`, cache per-color icons via a `remember` map so re-renders reuse bitmaps. The existing single-icon `markerIcon` remember is replaced by the cache:

```kotlin
// Replaces:
//   val markerIcon = remember { OverlayImage.fromBitmap(createAccentMarkerBitmap()) }
val iconCache = remember { mutableMapOf<Int, OverlayImage>() }

NaverMap(
    modifier = modifier,
    cameraPositionState = cameraPositionState,
    properties = properties,
) {
    locations.forEach { location ->
        // Mixed-event locations (multiple pins at same coords with different eventIds):
        // first pin's color wins for the shared marker. Tap opens the bottom sheet which
        // lists each exhibition individually. Rare edge case.
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
}
```

`ACCENT_ARGB` (the existing `0xFFFF5400.toInt()` constant) remains as the regular-pin default and the fallback for any pin whose `brandColorHex` is missing or malformed.

### 5.6 Platform — `MapView.ios.kt` per-color image cache

**File:** `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`

Generalize `createAccentMarkerImage()` to take RGB components:

```kotlin
@OptIn(ExperimentalForeignApi::class)
private fun createMarkerImage(red: Double, green: Double, blue: Double): UIImage {
    val w = 32.0
    val h = 44.0
    val radius = w / 2.0

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, UIScreen.mainScreen.scale)
    val color = UIColor(red = red, green = green, blue = blue, alpha = 1.0)

    color.setFill()
    UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, w, w)).fill()

    val tail = UIBezierPath()
    tail.moveToPoint(CGPointMake(0.0, radius))
    tail.addLineToPoint(CGPointMake(radius, h))
    tail.addLineToPoint(CGPointMake(w, radius))
    tail.closePath()
    tail.fill()

    UIColor.whiteColor.setFill()
    UIBezierPath.bezierPathWithOvalInRect(
        CGRectMake(radius - radius * 0.35, radius - radius * 0.35, radius * 0.7, radius * 0.7)
    ).fill()

    val image = UIGraphicsGetImageFromCurrentImageContext()!!
    UIGraphicsEndImageContext()
    return image
}
```

iOS cache mirrors Android's per-color lookup, but the integration shape is not the NaverMap Compose DSL used on Android — it's the `UIKitView` + imperative `NMFMarker` / `NMFMapView` API used in `MapView.ios.kt` today. The cache and helpers land as private file-scope definitions; the marker render loop (already in the file) consults the cache once per location:

```kotlin
// File-scope helpers (co-located with createMarkerImage):

private fun Int.rgbComponents(): Triple<Double, Double, Double> {
    val r = ((this shr 16) and 0xFF) / 255.0
    val g = ((this shr 8) and 0xFF) / 255.0
    val b = (this and 0xFF) / 255.0
    return Triple(r, g, b)
}

private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }

// Inside the Composable, create the cache once per composition:
val imageCache = remember { mutableMapOf<Int, NMFOverlayImage>() }

// Where the existing code sets the marker's iconImage, substitute:
val argb = location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB
val image = imageCache.getOrPut(argb) {
    val (r, g, b) = argb.rgbComponents()
    NMFOverlayImage.overlayImageWithImage(createMarkerImage(r, g, b))
}
marker.iconImage = image
```

`ACCENT_ARGB` is the existing `0xFFFF5400.toInt()` constant used for regular pins. The helpers are file-private extensions so they don't pollute the KMP API surface.

Note for implementer: the exact substitution point depends on the current marker-creation code in `MapView.ios.kt`. The cache must be consulted inside the loop that creates `NMFMarker` instances from `locations`; the default (ACCENT_ARGB) branch must continue to match the pre-Phase-2c visual.

### 5.7 Navigation — `App.kt` call-site update

**File:** `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt:267`

One line added to match the Phase 2b pattern for `ListScreen`:

```kotlin
2 -> MapScreen(
    viewModel = viewModel,
    onExhibitionTap = { selectedExhibition = it },
    onEventTap = { id -> selectedEventId = id },  // NEW
    modifier = Modifier.padding(innerPadding),
)
```

## 6. Edge Cases

| Scenario | Behavior |
|---|---|
| No active event | FAB hidden; all pins orange. Map tab identical to pre-Phase-2c. |
| Active event, MY LIST sub-tab | FAB visible; brand-colored pins for bookmarked event exhibitions, orange for others. |
| Active event, zero pins linked | FAB visible (promotes the event regardless). Tap → Event Detail (Phase 1 handles empty sections). |
| Two events active | Each pin gets its own color via `activeEventsById[eventId]`. FAB shows first-by-`start_date`. |
| Pin at location with mixed `eventId`s | First pin's color wins for the marker bitmap. Tap opens multi-pin bottom sheet, shows each individually. Documented in code. |
| Malformed `brand_color` | Pin → orange fallback; FAB → `Color.Black` fallback. Mirrors Phase 2b. |
| Event without `accent_color` | FAB unaffected (no accent span on FAB label). Phase 2b surfaces unchanged. |
| Event expires mid-session | `loadActiveEvents()` on next refresh returns shorter list → `activeEventsById` drops the event → pins recolor to orange; FAB hides if `activeEvent` becomes null. |
| Language switch | FAB label recomputes (`루프랩` ↔ `LOOP\nLAB`); pin colors unchanged. |
| FAB overlaps pin at bottom-right | User pans map. FAB is viewport-anchored. Standard FAB tradeoff. |
| Tab switch Map → elsewhere → back | FAB re-renders on return; no stale state. |
| Cold start, slow network | FAB and pin colors appear once `loadActiveEvents()` completes. Before: no FAB, pins all orange. No spinner. |
| Dark theme | FAB brand color unchanged (event-owned). Pins unchanged. Phase 2b parity. |

## 7. Testing

### 7.1 Unit tests (commonTest, `shared` module)

**New file:** `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt`

Assertions:
- `toMapPin(lang, emptyMap())` on an exhibition with `eventId = "loop-lab-busan-2025"` returns a pin with `eventId = "loop-lab-busan-2025", brandColorHex = null` (empty map means no color data available).
- `toMapPin(lang, mapOf("loop-lab-busan-2025" to sampleEvent))` returns a pin with `brandColorHex = sampleEvent.brandColor`.
- `toMapPin(lang, mapOf("other-event" to sampleEvent))` on an exhibition with `eventId = "loop-lab-busan-2025"` returns `brandColorHex = null` (no match).
- `toMapPin(lang, eventsById)` on an exhibition with `eventId = null` returns `eventId = null, brandColorHex = null`.
- `toMapPin(lang, eventsById)` returns `null` when `latitude` or `longitude` is null (regression check of existing behavior).

### 7.2 Unit tests (commonTest, `composeApp` module)

**New file:** `composeApp/src/commonTest/kotlin/com/gallr/app/ui/components/FabLabelTest.kt`

Assertions on `fabLabel(localizedName, lang)`:
- KO, `"루프랩"` → `"루프랩"`
- KO, `"루프랩 부산 2025"` → `"루프랩"`
- KO, `""` → `""`
- EN, `"Biennale"` → `"BIENNALE"`
- EN, `"Loop Lab"` → `"LOOP\nLAB"`
- EN, `"Loop Lab Busan 2025"` → `"LOOP\nLAB"`
- EN, `""` → `""`
- EN, `"  Loop   Lab  "` (extra whitespace) → `"LOOP\nLAB"`

### 7.3 `TabsViewModelTest` extensions

Optional (Phase 2b explicitly deferred this test infra; Phase 2c stays consistent). If added opportunistically:
- `activeEventsById` empty on init before `loadActiveEvents()` resolves
- `activeEventsById` populated correctly from repository success
- `activeEventsById` cleared to empty on repository failure
- `activeEvent` still `.firstOrNull()` (regression check)
- `myListMapPins` and `allMapPins` project `brandColorHex` onto matching exhibitions

Scope note: matching Phase 2b, `TabsViewModelTest` infra is not introduced in Phase 2c either. Manual smoke (§7.4) is the coverage.

### 7.4 Manual smoke test

Android (priority):
1. Loop Lab Busan active, 2+ linked exhibitions, open Map → MY LIST. Blue Loop Lab pins for bookmarked event exhibitions; orange for other bookmarks. FAB at bottom-right showing `루프랩` (KO) / `LOOP\nLAB` (EN).
2. Switch to ALL → FAB still visible; map shows mixed orange and blue pins.
3. Tap FAB → Event Detail opens (Phase 1 route).
4. Back → returns to Map tab; sub-tab state preserved.
5. Toggle language → FAB label swaps; pin colors unchanged.
6. Tap a blue pin → AlertDialog (single pin) or bottom sheet (multi-pin) opens, identical to pre-Phase-2c behavior.
7. Set `is_active = false` on event row, sync, relaunch → FAB gone, all pins orange, Map tab identical to pre-Phase-2c.
8. Dark theme → FAB and pin colors render identically; map tile theme unaffected.

iOS:
9. Repeat 1-8 on iPhone 16 Pro simulator (iOS 18.6). Verify NMapsMap iOS SDK accepts per-marker color bitmap.

### 7.5 Visual regression

None automated. Consistent with Phase 2a/2b (no screenshot-test framework in the project).

## 8. Rollout

Single ship. Client-only, no migration or GAS redeploy.

1. Land the PR (shared model, ViewModel, new FAB composable, MapScreen integration, both platform MapView updates, App.kt call-site).
2. Build and ship iOS + Android.
3. Verify manual smoke on both platforms post-install.

Rollback: revert the PR. No data dependency changes; older app builds continue to work with the existing data shape.

## 9. Open Items Carried Forward

- **Real logo image asset.** The FAB currently renders a stacked text label. When an operator supplies a real Loop Lab Busan logo, a future small change will swap the `Text` child in `EventMapFab` for an `Image` (or `Icon` for single-color assets) while keeping the 56dp square envelope.
- **Pin clustering for biennale-scale events.** Carried forward from Phase 1 as "biennale vs. single-venue fair distinction affects map pin clustering." Not actionable until a biennale-scale event is on the roadmap; single-city events like Loop Lab Busan don't need clustering.
- **Shared `EventStateHolder` abstraction.** Phase 2b intentionally duplicates `activeEvent` load between `FeaturedViewModel` and `TabsViewModel`. Phase 2c doesn't add a third consumer (the Map tab uses `TabsViewModel` already), so the abstraction still isn't justified. Revisit if a future phase adds a consumer that can't reasonably live under `TabsViewModel`.
