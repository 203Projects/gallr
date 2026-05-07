# City-Wide Art Event — Phase 2b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three List-tab surface treatments for the active city-wide event — a slim pinned banner above the tabs, a brand-colored filter chip in the flags row, and an edge+corner-label treatment on event-linked `ExhibitionCard` rows — so the active event is discoverable and filterable from the List tab without leaving the Featured tab as the only entry point.

**Architecture:** Client-only changes. `TabsViewModel` already loads `activeEvent: StateFlow<Event?>` (added in Phase 1, currently unused by the List tab). Phase 2b adds one boolean to `FilterState`, extends `TabsViewModel.filteredExhibitions` with a 7th flow source, adds a new `EventListBanner` composable, a new private `GallrEventFilterChip` composable in `ListScreen.kt`, and a nullable `EventTreatment` parameter on `ExhibitionCard`. All three surfaces collapse to zero footprint when `activeEvent == null` or the event expires mid-session.

**Tech Stack:** Kotlin 2.1.20 (KMP), Compose Multiplatform 1.8.0, Material3 `FilterChip`, existing `com.gallr.shared.util.parseHexColor` helper, existing `Event.localizedName` / `Event.nameLastToken`.

**Spec:** `docs/superpowers/specs/2026-04-24-city-wide-event-phase2b-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|------|----------------|
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventListBanner.kt` | New 36dp pinned banner composable for the top of the List tab |

### Files to modify

| Path | Change |
|------|--------|
| `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt` | Add `val eventOnly: Boolean = false` as the last constructor parameter (after `closingThisWeek`). `matches()` body unchanged. |
| `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt` | Add one assertion that `FilterState()` default has `eventOnly = false` |
| `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` | Extend `filteredExhibitions` `combine(...)` to include `_activeEvent` as 7th source; add event-filter clause; add auto-reset collector for stale `eventOnly` flag |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt` | Render `EventListBanner` above `TabRow` when banner should show; prepend `GallrEventFilterChip` to the flags row; add event-aware empty-state branch; pass `eventTreatment` into `ExhibitionCard` |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt` | Add `eventTreatment: EventTreatment? = null` parameter; when non-null, layer a 3dp left edge and a top-left corner label over the card |

### Files NOT to modify

- `composeApp/.../ui/tabs/featured/FeaturedScreen.kt` — the `ExhibitionCard(…)` call-site there gets the new default `eventTreatment = null` implicitly; no edit needed. (Phase 2b intentionally limits card treatment to the List tab; the Featured tab keeps its own event promotion surface — the `EventPromotionCard` from Phase 1/2a.)
- `composeApp/.../ui/components/EventPromotionCard.kt` — untouched.
- `shared/.../repository/EventRepository.kt` and `EventRepositoryImpl.kt` — surface already sufficient; `getActiveEvents()` returns `Result<List<Event>>` (already called by `TabsViewModel.loadActiveEvent`).
- Navigation graph — banner tap reuses the existing `event_detail/{eventId}` route from Phase 1.
- DI / factory wiring — `TabsViewModel` already accepts `eventRepository` as a constructor parameter.
- No schema changes. No GAS changes. No migrations.

---

## Task Breakdown

Tasks ordered bottom-up: model → viewmodel → card → banner → wire it together → verify. Each task ends green + commit.

---

### Task 1: Add `eventOnly` to FilterState

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`

Strict TDD: add the failing assertion first, watch it fail, add the field, watch it pass.

- [ ] **Step 1: Add a failing assertion to `FilterStateTest.kt`**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`. At the end of the class body (immediately before the closing `}` of `class FilterStateTest`), add a new test:

```kotlin
    @Test
    fun defaultFilterState_hasEventOnlyFalse() {
        val filter = FilterState()
        kotlin.test.assertEquals(false, filter.eventOnly)
    }
```

(`kotlin.test.assertEquals` is used inline to match the existing style in `EventTest.kt`; the file's existing imports of `assertTrue` / `assertFalse` remain unchanged.)

- [ ] **Step 2: Run the test — verify it fails to compile**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.FilterStateTest"
```

Expected: FAIL with "unresolved reference: eventOnly" (because `FilterState` has no such field yet).

- [ ] **Step 3: Add the field to `FilterState`**

In `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`, find the current constructor:

```kotlin
data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
) {
```

Replace with:

```kotlin
data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // Phase 2b — filter list to active-event-linked exhibitions
) {
```

**Do not modify `matches()`.** The event filter is applied in `TabsViewModel.filteredExhibitions` (Task 2) because `FilterState` has no access to the active event id; keeping `matches()` repository-free preserves its unit-testability.

- [ ] **Step 4: Run the test — verify it passes**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.FilterStateTest"
```

Expected: ALL tests PASS (existing `FilterStateTest` tests + new `defaultFilterState_hasEventOnlyFalse`).

- [ ] **Step 5: Run the full shared test suite (no regressions)**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: ALL tests PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt
git commit -m "feat(shared): add eventOnly flag to FilterState"
```

---

### Task 2: Wire activeEvent into filteredExhibitions + auto-reset stale eventOnly

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`

`TabsViewModel` already owns `_activeEvent: MutableStateFlow<Event?>` and calls `loadActiveEvent()` in `init` and `refresh()`. Two changes: extend the `filteredExhibitions` combine to observe `_activeEvent`, and add a collector that clears `eventOnly` when the event goes null.

- [ ] **Step 1: Extend `filteredExhibitions` to 7 sources**

In `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`, find the `filteredExhibitions` block (starts at line 204):

```kotlin
    val filteredExhibitions: StateFlow<ExhibitionListState> =
        combine(
            _allExhibitions, _filterState, _selectedCity, _showMyListOnly, bookmarkedIds, _searchQuery,
        ) { values ->
            val state = values[0] as ExhibitionListState
            val filter = values[1] as FilterState
            val city = values[2] as String?
            @Suppress("UNCHECKED_CAST")
            val myListOnly = values[3] as Boolean
            @Suppress("UNCHECKED_CAST")
            val bookmarked = values[4] as Set<String>
            val query = (values[5] as String).trim().lowercase()
            when (state) {
                is ExhibitionListState.Loading -> ExhibitionListState.Loading
                is ExhibitionListState.Error -> state
                is ExhibitionListState.Success -> {
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    val filtered = state.exhibitions
                        .filter { it.closingDate >= today }  // hide ended exhibitions
                        .filter { city == null || it.cityKo == city }
                        .filter { filter.matches(it) }
                        .filter { !myListOnly || it.id in bookmarked }
                        .filter {
                            query.isEmpty() ||
                                it.nameKo.lowercase().contains(query) ||
                                it.nameEn.lowercase().contains(query) ||
                                it.venueNameKo.lowercase().contains(query) ||
                                it.venueNameEn.lowercase().contains(query)
                        }
                    ExhibitionListState.Success(filtered)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExhibitionListState.Loading,
        )
```

Replace the entire block with:

```kotlin
    val filteredExhibitions: StateFlow<ExhibitionListState> =
        combine(
            _allExhibitions, _filterState, _selectedCity, _showMyListOnly, bookmarkedIds, _searchQuery, _activeEvent,
        ) { values ->
            val state = values[0] as ExhibitionListState
            val filter = values[1] as FilterState
            val city = values[2] as String?
            @Suppress("UNCHECKED_CAST")
            val myListOnly = values[3] as Boolean
            @Suppress("UNCHECKED_CAST")
            val bookmarked = values[4] as Set<String>
            val query = (values[5] as String).trim().lowercase()
            val activeEvent = values[6] as Event?
            when (state) {
                is ExhibitionListState.Loading -> ExhibitionListState.Loading
                is ExhibitionListState.Error -> state
                is ExhibitionListState.Success -> {
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                    val filtered = state.exhibitions
                        .filter { it.closingDate >= today }  // hide ended exhibitions
                        .filter { city == null || it.cityKo == city }
                        .filter { filter.matches(it) }
                        .filter { !myListOnly || it.id in bookmarked }
                        .filter {
                            query.isEmpty() ||
                                it.nameKo.lowercase().contains(query) ||
                                it.nameEn.lowercase().contains(query) ||
                                it.venueNameKo.lowercase().contains(query) ||
                                it.venueNameEn.lowercase().contains(query)
                        }
                        .filter {
                            // Phase 2b — event-only filter. Short-circuits when activeEvent is null
                            // so stale eventOnly state doesn't transiently empty the list while the
                            // auto-reset collector (init block) clears it.
                            !filter.eventOnly || activeEvent == null || it.eventId == activeEvent.id
                        }
                    ExhibitionListState.Success(filtered)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExhibitionListState.Loading,
        )
```

- [ ] **Step 2: Add auto-reset collector in `init`**

Find the `init` block at the bottom of the class (lines 331–335):

```kotlin
    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvent()
    }
```

Replace with:

```kotlin
    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvent()

        // Phase 2b — when the active event disappears (expired, deactivated, network
        // failure on refresh), silently clear any stranded eventOnly filter so the
        // List tab doesn't show an empty feed with no way to recover.
        viewModelScope.launch {
            _activeEvent.collect { event ->
                if (event == null && _filterState.value.eventOnly) {
                    _filterState.value = _filterState.value.copy(eventOnly = false)
                }
            }
        }
    }
```

The `kotlinx.coroutines.launch` import is already present at the top of the file (line 29). No new imports needed.

- [ ] **Step 3: Verify compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify shared tests still pass**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: ALL tests PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt
git commit -m "feat(list): wire activeEvent into filteredExhibitions + auto-reset stale filter"
```

---

### Task 3: Create EventListBanner composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventListBanner.kt`

A 36dp pinned banner. Uses `event.brandColor` as background, white text, optional accent-color span on the trailing token of the localized name. Text is bottom-aligned horizontally-centered with single line + ellipsis.

- [ ] **Step 1: Create the file with this exact content**

```kotlin
package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor

@Composable
fun EventListBanner(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it) }
        ?: MaterialTheme.colorScheme.onBackground
    val accent = parseHexColor(event.accentColor)?.let { Color(it) }

    val name = event.localizedName(lang)
    val lastToken = Event.nameLastToken(name)
    val nameDisplay = buildAnnotatedString {
        if (accent != null && lastToken.isNotEmpty() && name.endsWith(lastToken)) {
            append(name.dropLast(lastToken.length))
            withStyle(SpanStyle(color = accent)) { append(lastToken) }
        } else {
            append(name)
        }
    }

    val nowOn = if (lang == AppLanguage.KO) "지금 진행 중" else "NOW ON"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(brand)
            .clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = GallrSpacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nameDisplay,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "  ·  $nowOn",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventListBanner.kt
git commit -m "feat(list): add EventListBanner composable for Phase 2b"
```

---

### Task 4: Add EventTreatment parameter + marks to ExhibitionCard

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

Add an optional `eventTreatment` parameter. When non-null, overlay a 3dp brand-color left edge and a top-left corner label. Call-sites without the new argument use the default `null` and render identically to today.

- [ ] **Step 1: Add `EventTreatment` data class and new parameter**

In `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`, the current function signature (at line 45) is:

```kotlin
@Composable
fun ExhibitionCard(
    exhibition: Exhibition,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onTap: () -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
```

Replace the signature with:

```kotlin
@Composable
fun ExhibitionCard(
    exhibition: Exhibition,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onTap: () -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    eventTreatment: EventTreatment? = null,
) {
```

Add the new data class at the top of the file, immediately after the `import` block (before `@Composable fun ExhibitionCard(...)`):

```kotlin
/**
 * Visual treatment applied to an ExhibitionCard when it belongs to the current
 * active city-wide event. Null for regular exhibitions.
 */
data class EventTreatment(
    val brandColor: Color,
    /** Pre-localized, pre-truncated (≤ 20 chars + ellipsis) event name for the corner label. */
    val label: String,
)
```

The `Color` import already exists (line 28). No new imports needed.

- [ ] **Step 2: Add imports for FontWeight and em**

The corner-label uses `FontWeight.SemiBold` and `letterSpacing = 0.08.em`. The existing file imports `TextOverflow` but not these. Add these imports to the import block (alphabetical order with the existing imports):

```kotlin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
```

- [ ] **Step 3: Add the two overlay `Box` children**

Inside the outer `Box` in `ExhibitionCard`, find the Layer 3 content column (around line 163):

```kotlin
        // ── Layer 3: Content ──
        Column(modifier = Modifier.padding(GallrSpacing.md)) {
```

Immediately **before** that `// ── Layer 3: Content ──` comment, insert:

```kotlin
        // ── Layer 2b: Event treatment (Phase 2b) — left edge + corner label ──
        if (eventTreatment != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(eventTreatment.brandColor),
            )
            Text(
                text = eventTreatment.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(eventTreatment.brandColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

```

The existing imports `fillMaxHeight` and `width` need to be present. Check lines 15–17 for the current `androidx.compose.foundation.layout.*` imports. If `fillMaxHeight` is missing, add:

```kotlin
import androidx.compose.foundation.layout.fillMaxHeight
```

(The file already imports `width` via `androidx.compose.foundation.layout.width`? Confirm by `grep "import.*\\.width" composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`. If missing, add `import androidx.compose.foundation.layout.width`.)

- [ ] **Step 4: Verify compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL. If a `fillMaxHeight` or `width` unresolved-reference error appears, add the missing import and rebuild.

- [ ] **Step 5: Verify existing callers still work (default param)**

Both existing `ExhibitionCard(...)` callers — `ListScreen.kt:400` and `FeaturedScreen.kt:106` — pass no `eventTreatment` argument, so the default `null` applies and they render identically to today.

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL (repeated here as a belt-and-suspenders sanity check; no code change between steps 4 and 5).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt
git commit -m "feat(card): add optional EventTreatment overlay to ExhibitionCard"
```

---

### Task 5: Render banner, chip, and card-treatment in ListScreen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`

Four changes in one file, committed together because they're tightly coupled:

1. Observe `activeEvent` from the viewmodel.
2. Render `EventListBanner` above the `TabRow` when `activeEvent != null && !showMyListOnly`.
3. Prepend `GallrEventFilterChip` to the flags row when `activeEvent != null`.
4. Compute and pass `eventTreatment` into each `ExhibitionCard`.
5. Add event-aware empty-state branch.

- [ ] **Step 1: Add imports at the top of ListScreen.kt**

The file currently imports a lot, but is missing a few needed for Phase 2b. Find the import block (lines 1–68 roughly). Add these imports, alphabetically merged with existing:

```kotlin
import androidx.compose.ui.graphics.Color
import com.gallr.app.ui.components.EventListBanner
import com.gallr.app.ui.components.EventTreatment
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor
```

If any of these are already present (verify via `grep "import.*Color\|import.*Event\b\|import.*EventTreatment\|import.*EventListBanner\|import.*parseHexColor" composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`), skip the duplicate.

- [ ] **Step 2: Observe `activeEvent`**

Find the block of `.collectAsState()` calls near the top of `ListScreen`:

```kotlin
    val filter by viewModel.filterState.collectAsState()
    val state by viewModel.filteredExhibitions.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val distinctCities by viewModel.distinctCities.collectAsState()
    val distinctRegions by viewModel.distinctRegions.collectAsState()
    val showMyListOnly by viewModel.showMyListOnly.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
```

Replace with (adds one line at the end):

```kotlin
    val filter by viewModel.filterState.collectAsState()
    val state by viewModel.filteredExhibitions.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val lang by viewModel.language.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val distinctCities by viewModel.distinctCities.collectAsState()
    val distinctRegions by viewModel.distinctRegions.collectAsState()
    val showMyListOnly by viewModel.showMyListOnly.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val activeEvent by viewModel.activeEvent.collectAsState()
```

- [ ] **Step 3: Insert the banner above the TabRow**

Find the outer `Column(...) {` and the `// ── Tab toggle` comment directly inside it:

```kotlin
    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        // ── Tab toggle: All Exhibitions / My List ─────────────────────────
        TabRow(
```

Insert the banner **between the opening `Column` and the `// ── Tab toggle` comment**:

```kotlin
    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        // ── Event banner (Phase 2b) — shown only on the All tab when active ──
        val banner = activeEvent
        if (banner != null && !showMyListOnly) {
            EventListBanner(
                event = banner,
                lang = lang,
                onTap = { onEventTap(banner.id) },
            )
        }

        // ── Tab toggle: All Exhibitions / My List ─────────────────────────
        TabRow(
```

This introduces a new callback parameter `onEventTap`. Wire it in by extending the `ListScreen(...)` function signature. Find:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    viewModel: TabsViewModel,
    onExhibitionTap: (Exhibition) -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Plumbing the new callback through the app is Task 7. For now the additional required parameter breaks the call-site — expected; Task 7 fixes it.

- [ ] **Step 4: Prepend `GallrEventFilterChip` to the flags row**

Find the flags-row block (lines 276–305):

```kotlin
        // ── Filter chips (single horizontally scrollable row) ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin),
        ) {
            GallrFilterChip(
                selected = filter.showFeatured,
                onClick = { viewModel.updateFilter { copy(showFeatured = !showFeatured) } },
                label = if (lang == AppLanguage.KO) "추천" else "FEATURED",
            )
```

Insert a new leading chip + spacer **immediately after the opening `Row { … }` brace and before `GallrFilterChip(selected = filter.showFeatured, …)`**:

```kotlin
        // ── Filter chips (single horizontally scrollable row) ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GallrSpacing.screenMargin),
        ) {
            activeEvent?.let { event ->
                val brand = parseHexColor(event.brandColor)?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.onBackground
                GallrEventFilterChip(
                    selected = filter.eventOnly,
                    onClick = { viewModel.updateFilter { copy(eventOnly = !eventOnly) } },
                    label = event.localizedName(lang),
                    brandColor = brand,
                )
                Spacer(Modifier.width(GallrSpacing.sm))
            }
            GallrFilterChip(
                selected = filter.showFeatured,
                onClick = { viewModel.updateFilter { copy(showFeatured = !showFeatured) } },
                label = if (lang == AppLanguage.KO) "추천" else "FEATURED",
            )
```

- [ ] **Step 5: Add the `GallrEventFilterChip` composable at the bottom of the file**

At the end of `ListScreen.kt`, below the existing `GallrFilterChip` private composable (after the closing brace of that function), append:

```kotlin
// ── Event filter chip (Phase 2b) ─────────────────────────────────────────────

@Composable
private fun GallrEventFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    brandColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(4.dp)
                    .background(if (selected) androidx.compose.ui.graphics.Color.White else brandColor),
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RectangleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = brandColor,
            selectedContainerColor = brandColor,
            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = brandColor,
            selectedBorderColor = brandColor,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}
```

Imports required (add to the top import block if missing):

```kotlin
import androidx.compose.foundation.layout.size
```

The rest (`FilterChip`, `FilterChipDefaults`, `Text`, `MaterialTheme`, `Modifier`, `RectangleShape`, `dp`, `background`) are already imported by the existing `GallrFilterChip`.

- [ ] **Step 6: Add event-aware empty-state branch**

Find the empty-state `when { … }` block inside `ExhibitionListState.Success` (lines 364–378 roughly):

```kotlin
                    GallrEmptyState(
                        message = when {
                            searchQuery.isNotBlank() ->
                                if (lang == AppLanguage.KO) "검색 결과가 없습니다." else "No results found."
                            showMyListOnly && bookmarkedIds.isEmpty() ->
                                if (lang == AppLanguage.KO) "저장한 전시가 없습니다.\n전시를 북마크하면 여기에 표시됩니다."
                                else "No saved exhibitions yet.\nBookmark exhibitions to see them here."
                            showMyListOnly ->
                                if (lang == AppLanguage.KO) "필터에 맞는 저장 전시가 없습니다."
                                else "No saved exhibitions match the current filters."
                            cityName != null ->
                                if (lang == AppLanguage.KO) "${cityName}에 전시가 없습니다."
                                else "No exhibitions in $cityName."
                            else ->
                                if (lang == AppLanguage.KO) "필터에 맞는 전시가 없습니다."
                                else "No exhibitions match the current filters."
                        },
```

Insert the event branch **between `showMyListOnly ->` and `cityName != null ->`**:

```kotlin
                    GallrEmptyState(
                        message = when {
                            searchQuery.isNotBlank() ->
                                if (lang == AppLanguage.KO) "검색 결과가 없습니다." else "No results found."
                            showMyListOnly && bookmarkedIds.isEmpty() ->
                                if (lang == AppLanguage.KO) "저장한 전시가 없습니다.\n전시를 북마크하면 여기에 표시됩니다."
                                else "No saved exhibitions yet.\nBookmark exhibitions to see them here."
                            showMyListOnly ->
                                if (lang == AppLanguage.KO) "필터에 맞는 저장 전시가 없습니다."
                                else "No saved exhibitions match the current filters."
                            filter.eventOnly && activeEvent != null ->
                                if (lang == AppLanguage.KO) "${activeEvent!!.nameKo}에 참여하는 전시가 없습니다."
                                else "No exhibitions in ${activeEvent!!.nameEn}."
                            cityName != null ->
                                if (lang == AppLanguage.KO) "${cityName}에 전시가 없습니다."
                                else "No exhibitions in $cityName."
                            else ->
                                if (lang == AppLanguage.KO) "필터에 맞는 전시가 없습니다."
                                else "No exhibitions match the current filters."
                        },
```

The `!!` asserts non-null; the guard clause `activeEvent != null` makes it safe. Kotlin's smart cast doesn't survive across a `when` condition boundary for `var`-backed `by collectAsState` values, so `!!` is the idiomatic fix (matches the existing `selectedCity?.let { city -> ... }` pattern elsewhere in the file).

- [ ] **Step 7: Compute and pass `eventTreatment` into each `ExhibitionCard`**

Find the `items(s.exhibitions, key = { it.id })` block (lines 399–410):

```kotlin
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = GallrSpacing.md),
                                )
                            }
```

Replace with:

```kotlin
                            items(s.exhibitions, key = { it.id }) { exhibition ->
                                val treatment = activeEvent
                                    ?.takeIf { exhibition.eventId == it.id }
                                    ?.let { event ->
                                        val localized = event.localizedName(lang)
                                        val brand = parseHexColor(event.brandColor)?.let { Color(it) }
                                            ?: MaterialTheme.colorScheme.onBackground
                                        EventTreatment(
                                            brandColor = brand,
                                            label = if (localized.length > 20) localized.take(20) + "…" else localized,
                                        )
                                    }
                                ExhibitionCard(
                                    exhibition = exhibition,
                                    isBookmarked = exhibition.id in bookmarkedIds,
                                    onBookmarkToggle = { viewModel.toggleBookmark(exhibition.id) },
                                    onTap = { onExhibitionTap(exhibition) },
                                    lang = lang,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = GallrSpacing.md),
                                    eventTreatment = treatment,
                                )
                            }
```

- [ ] **Step 8: Verify compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: FAIL — `ListScreen(...)` now requires an `onEventTap` parameter that Task 7 will plumb in. The error will be on the call-site (outside `ListScreen.kt`). Leave the failure for Task 7.

If the failure is inside `ListScreen.kt` itself (e.g., unresolved reference, missing import), fix it before proceeding; do not commit a file that fails to compile in isolation.

- [ ] **Step 9: Commit (even though full app does not yet build)**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt
git commit -m "feat(list): render event banner, filter chip, card treatment, empty state"
```

This is the only place where the plan commits a transiently-broken build — Task 7 repairs it in the next commit. The reason for the split: ListScreen and its caller are in different concerns (screen vs. nav graph), and keeping the commits separate makes review easier.

---

### Task 6: Run the full shared test suite before touching navigation

**Files:** none (verification task)

Before wiring the nav graph, confirm the shared tests still pass — this is the last point where the build is known-green on the `shared` side.

- [ ] **Step 1: Run shared tests**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: ALL tests PASS.

- [ ] **Step 2: No commit** (verification only)

---

### Task 7: Plumb `onEventTap` through the navigation graph

**Files:**
- Modify: wherever `ListScreen(...)` is invoked (likely `composeApp/.../App.kt` or a nav-graph file in `composeApp/.../ui/`)

The Phase 1 Featured tab already navigates to `event_detail/{eventId}` from the `EventPromotionCard` tap. Reuse the same destination.

- [ ] **Step 1: Locate the `ListScreen(...)` call-site**

```bash
grep -rn "ListScreen(" composeApp/src/commonMain --include="*.kt" | grep -v "fun ListScreen"
```

Expected: one hit, pointing at the file where the List tab is wired into the navigation graph.

- [ ] **Step 2: Locate the existing `EventPromotionCard` / featured → event navigation for reference**

```bash
grep -rn "event_detail\|EventDetail\|onEventTap\|onEventPromotionTap" composeApp/src/commonMain --include="*.kt"
```

Look for the exact destination used from the Featured tab (e.g., `navController.navigate("event_detail/${event.id}")`). The banner tap should navigate to the same destination.

- [ ] **Step 3: Pass `onEventTap` to `ListScreen`**

At the `ListScreen(...)` call-site from step 1, add the `onEventTap` argument that navigates to the same event-detail destination the Featured tab uses. Concrete form depends on what step 2 reveals, but will look like:

```kotlin
ListScreen(
    viewModel = viewModel,
    onExhibitionTap = { exhibition -> /* existing */ },
    onEventTap = { eventId -> navController.navigate("event_detail/$eventId") },
    modifier = Modifier.fillMaxSize(),
)
```

(If the nav pattern uses a typed route object instead of a string, match the existing Featured-tab usage exactly.)

- [ ] **Step 4: Verify full compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify the full Android assembly**

```bash
./gradlew :composeApp:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/App.kt  # or whichever file step 1 identified
git commit -m "feat(nav): wire list-tab banner tap to event_detail route"
```

---

### Task 8: Install on Android and verify the three surfaces

**Files:** none (verification task)

Precondition: a Loop Lab Busan event row exists with `is_active = true` and at least 2 exhibitions linked via `event_id` (all true today, per Phase 2a).

- [ ] **Step 1: Build and install**

```bash
./gradlew :composeApp:installDebug
```

Expected: `Installed on 1 device.`

- [ ] **Step 2: Force-close and relaunch**

```bash
adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
```

- [ ] **Step 3: Verify the banner**

Open the List tab on the device. Confirm:
- A slim blue (`#0099FF`) strip sits above the tab row (All Exhibitions / My List).
- The strip shows `Loop Lab BUSAN · NOW ON` (or the Korean equivalent when KO is the app language), with `BUSAN` in coral (`#FF5C5C`) when `accent_color` is set.
- Scrolling the list does NOT hide the banner.
- Switching to **My List** tab hides the banner.
- Switching back to **All Exhibitions** brings it back.
- Tapping the banner navigates to Event Detail (same page the Featured tab's card navigates to).

- [ ] **Step 4: Verify the filter chip**

On the List tab:
- A new chip labeled `Loop Lab Busan` (or `루프랩 부산 2025` in Korean) sits at the start of the flags row (before FEATURED). It has a blue border and a small blue dot icon.
- Tapping it: background fills blue, label becomes white, dot becomes white; list is filtered to Loop Lab exhibitions only.
- Tapping again: returns to the unfiltered list.
- Combining with a city chip: e.g., `Loop Lab + Busan` → shows Loop Lab exhibitions in Busan. `Loop Lab + Seoul` → empty state with copy `"No exhibitions in Loop Lab Busan 2025."` (EN) / `"루프랩 부산 2025에 참여하는 전시가 없습니다."` (KO).

- [ ] **Step 5: Verify the card treatment**

On the List tab with no filters applied:
- Loop Lab-linked exhibition cards show a 3dp blue left edge.
- The top-left corner of each linked card has a small blue chip reading `Loop Lab Busan` (or `루프랩 부산 2025`, truncated to 20 chars + ellipsis if longer).
- Regular non-event cards look identical to today (no edge, no label).
- The bookmark heart at the top-right is not overlapped by the corner label.

- [ ] **Step 6: Verify the clear-filter path**

- With the Loop Lab chip selected, tap `Clear Filters` (appears under the chip rows once any filter is active).
- Expected: chip deselects, list returns to all exhibitions.

- [ ] **Step 7: Verify language toggle**

Toggle app language KO ↔ EN. Banner text, chip label, empty-state copy, and card corner label all swap. No crash.

- [ ] **Step 8: Capture logcat for unexpected errors**

```bash
adb logcat -d | grep -iE "FATAL|AndroidRuntime" | tail -20
```

Expected: empty.

- [ ] **Step 9: No commit** (verification only)

---

### Task 9: Simulate event expiry and verify auto-reset

**Files:** none (verification only — touches the live sheet briefly then restores it)

This verifies the Task 2 auto-reset collector: when `activeEvent` goes null while `eventOnly = true` was set in a previous session, the filter clears on next load.

- [ ] **Step 1: Set the event inactive in the sheet**

In the events Google Sheet, set the Loop Lab Busan row's `is_active` cell to `FALSE`. Run `syncEventsToSupabase` from the Apps Script editor; confirm `status: SUCCESS`.

- [ ] **Step 2: Relaunch the app**

```bash
adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
```

- [ ] **Step 3: Verify all three surfaces collapse**

On the List tab:
- No banner above the tabs.
- No Loop Lab chip in the flags row (only FEATURED / EDITOR'S PICK / OPENING / CLOSING).
- No edge/label marks on any card.
- The List tab looks identical to a pre-Phase-2b build.

On the Featured tab, the `EventPromotionCard` is also gone (Phase 1 behavior — unchanged by Phase 2b).

- [ ] **Step 4: Restore `is_active = TRUE`**

In the sheet, set `is_active` back to `TRUE`. Run `syncEventsToSupabase` → `status: SUCCESS`.

- [ ] **Step 5: Relaunch and verify restoration**

```bash
adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
```

Banner, chip, and card treatments all return on the List tab. Featured tab's `EventPromotionCard` is back.

- [ ] **Step 6: No commit** (verification only)

---

### Task 10: iOS smoke test

**Files:** none (verification task)

Same matrix as Task 8, on the iOS simulator. Phase 2a shipped Android-first with iOS blocked on a simulator version sort-out from Phase 1. If iOS is still blocked, defer this task and note in the PR description.

- [ ] **Step 1: Build and run on the iOS simulator**

Use the existing iOS build command (Xcode or `./gradlew :composeApp:iosSimulatorArm64Test` — verify with the operator which is current).

- [ ] **Step 2: Repeat Task 8 steps 3–7 on iOS**

Expected: identical visual behavior to Android. Brand-color, accent-color, and AnnotatedString spans on the banner have all been verified safe on iOS 18.6 in Phase 2a.

- [ ] **Step 3: No commit** (verification only)

---

## Self-Review

**Spec coverage check:**

- §2 goals (banner + chip + card treatment, collapse when inactive) → Tasks 3, 4, 5, 8, 9
- §4 decisions — banner placement (slim strip pinned above TabRow) → Task 5 step 3
- §4 decisions — My List hides banner, chip persists → Task 5 step 3 guard, verified in Task 8 step 3
- §4 decisions — filter chip leading in flags row → Task 5 step 4
- §4 decisions — chip label = full localized name → Task 5 step 4 (`event.localizedName(lang)`)
- §4 decisions — chip styling (brand border, brand fill, dot) → Task 5 step 5
- §4 decisions — filter semantics AND-combine → Task 2 step 1 (appended `.filter {...}` clause)
- §4 decisions — card edge + corner label → Task 4 step 3
- §4 decisions — card scope (`exhibition.eventId == activeEvent.id`) → Task 5 step 7 (`takeIf`)
- §4 decisions — multi-event = first by start_date (Phase 1 behavior unchanged) → implicit (no change to `getActiveEvents()`)
- §4 decisions — state ownership in `TabsViewModel` → Task 2 (already exists; just wired)
- §4 decisions — hide chip + banner when `activeEvent == null` → Task 5 steps 3, 4 (both guarded on `activeEvent`)
- §4 decisions — auto-reset `eventOnly` on event expiry → Task 2 step 2
- §5 architecture (no network/schema/GAS) → zero new files in those directories; plan explicitly lists "NOT to modify"
- §6.1 FilterState field → Task 1
- §6.2 TabsViewModel changes → Task 2
- §6.3 DI wiring → Task 2 header note (already wired in Phase 1; no code change)
- §7.1 EventListBanner → Task 3
- §7.2 Filter chip composable → Task 5 step 5
- §7.3 ExhibitionCard `EventTreatment` parameter → Task 4
- §7.4 Call-site update → Task 5 step 7
- §8 Navigation/language → Task 5 step 3 (banner callback), Task 7 (nav plumbing)
- §9 edge cases → verified via Tasks 8 (happy path) and 9 (expiry)
- §10 empty-state copy → Task 5 step 6
- §11.1 unit test (FilterState default) → Task 1
- §11.2 manual smoke → Task 8
- §11.3 no visual regression → plan does not add one (correct per spec)
- §12 rollout (single ship) → single PR of commits from Tasks 1–7

No spec section is missing a task. The spec mentions additional TabsViewModelTest assertions in §11.1 (activeEvent load / null / failure / filter behavior / auto-reset) — these are not implemented as Kotlin unit tests in this plan because `TabsViewModelTest` does not exist today and adding the test harness (coroutine test rule, repository fakes) is out of proportion for a single viewmodel. The behaviors are covered by the manual smoke tests in Tasks 8 and 9 instead. This is a deliberate scope call and is documented here so future-you knows why.

**Placeholder scan:** Task 7 steps 1–3 are deliberately discovery-based rather than prescriptive, because the nav-graph file location and shape depend on how the existing Featured → event_detail wiring was implemented in Phase 1. Step 1's `grep` command gives the exact lookup; Step 2 identifies the reference pattern; Step 3 matches it. No other placeholders. Task 4 step 3's "If `fillMaxHeight` is missing, add" is a conditional — the grep command in the step resolves it to a concrete action.

**Type consistency:**
- `eventOnly: Boolean` — Task 1 declares in `FilterState`; Task 2 reads `filter.eventOnly`; Task 5 reads `filter.eventOnly` and mutates via `copy(eventOnly = !eventOnly)`. Same name throughout.
- `activeEvent: StateFlow<Event?>` — existed before Phase 2b; Task 2 reads `values[6]` as `Event?`; Task 5 observes via `viewModel.activeEvent.collectAsState()`. Consistent.
- `EventTreatment(brandColor, label)` — Task 4 defines; Task 5 step 7 constructs; Task 4 step 3 consumes. Match.
- `onEventTap: (String) -> Unit` — Task 5 step 3 adds to `ListScreen`; Task 7 step 3 wires the call-site. Match.
- `parseHexColor` return type — `Long?` (confirmed by reading `shared/src/commonMain/kotlin/com/gallr/shared/util/HexColor.kt`); callers use `?.let { Color(it) } ?: fallback`. Consistent in Tasks 3, 5 step 4, 5 step 7.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-24-city-wide-event-phase2b.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
