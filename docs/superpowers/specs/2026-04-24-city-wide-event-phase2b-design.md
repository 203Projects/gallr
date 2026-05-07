# City-Wide Art Event — Phase 2b: List-Tab Surface Treatments

**Date:** 2026-04-24
**Status:** Spec — pending implementation plan
**Phase:** 2b of a sub-phased Phase 2 (2a/2b/2c)
**Predecessors:**
- Phase 1 — `docs/superpowers/specs/2026-04-22-city-wide-biennale-phase1-design.md` (shipped, PR #39)
- Phase 2a — `docs/superpowers/specs/2026-04-24-city-wide-event-phase2a-design.md` (sync stability + hero image)

## 1. Background

Phase 1 shipped the Featured-tab promoted card and Event Detail page, backed by a new `events` table and `exhibitions.event_id` FK. Phase 2a stabilized the events sync (upsert + diff-delete) and added a hero image to the Featured promoted card.

Phase 2b adds List-tab surface treatments so the active event is discoverable and filterable from the List tab — not only from Featured. The three surfaces (banner, filter chip, card edge + corner label) were called out as non-goals in both predecessor specs.

No backend or sync changes. Phase 2b is pure client-side consumption of data that Phase 1 + 2a already populate.

## 2. Goals

- Users on the List tab see a slim pinned banner identifying the active event and providing a tap target to Event Detail.
- Users can filter the list to event-linked exhibitions only via a brand-colored filter chip in the existing flags row.
- Event-linked exhibitions in the feed are visually distinguishable from regular exhibitions without relying on the user having memorized the brand color.
- When no event is active (or the event expires mid-session), all three surfaces collapse with zero residual footprint.

## 3. Non-Goals

- Map pin recoloring, map FAB, logo asset (Phase 2c)
- Purchase Tickets UI surface (Phase 2 polish — `events.ticket_url` remains unrendered)
- Multi-event simultaneous display (Phase 2 polish)
- Past-events archive (out of scope)
- Animated banner transitions, marquee text, or auto-cycling content
- A dedicated event tab or navigation destination beyond Phase 1's `event_detail` route
- Golden/screenshot visual-regression tests (no framework currently configured)

## 4. Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Banner placement | Slim strip (~36dp) pinned above `TabRow` at top of List tab | Minimal vertical cost; preserves habitual position of tabs/search/chips; stays visible while scrolling so returning users can still enter Event Detail. |
| Banner visibility | Active event only; hidden on My List sub-tab | Banner is a promotion surface; My List is a saved-items view and shouldn't be interrupted. |
| Filter chip placement | Leading entry in the existing flags row (FEATURED / EDITOR'S PICK / OPENING / CLOSING) | Reads as an editorial toggle alongside peers; doesn't disturb geography filters; no new row of vertical space. |
| Filter chip label | Localized full event name (`event.localizedName(lang)`) | No separate short-name field exists on `Event`. Truncate with ellipsis if needed — chip row is already `horizontalScroll`. |
| Filter chip styling | Brand-colored border (unselected) / brand fill + white label (selected); 4dp dot marker leading the label | Visually distinct from monochrome peers; signals event-specific affordance without redefining the base chip component. |
| Filter semantics | `filter.eventOnly = true` AND-combines with other filters; excludes non-event exhibitions | Consistent with how FEATURED / EDITOR'S PICK chips already behave. |
| Card treatment | 3dp brand-color left edge + small brand-color top-left corner label | Edge is a passive signal that survives scrolling; label is explicit. Together redundant-but-cheap. Matches Phase 1's original proposal. |
| Card treatment scope | Applies when `exhibition.eventId == activeEvent.id`; other events' linked cards render normally | Phase 2b is single-event; Phase 2c handles multi-event resolution. |
| Multi-event handling | First active event by `start_date` — matches Phase 1 | Defers multi-event resolution to Phase 2c; avoids partial solution. |
| State ownership | `TabsViewModel` gets its own `activeEvent: StateFlow<Event?>` | `EventRepository.getActiveEvents()` is a single lightweight call; two viewmodels each holding a copy is simpler than introducing a shared state-holder abstraction. Phase 2c may revisit. |
| Chip hydration when no event | Chip and banner hidden entirely when `activeEvent == null` | Dead UI adds noise; there's nothing meaningful to filter by. |
| Event expiry mid-session | `TabsViewModel` resets `eventOnly = false` when `activeEvent` transitions non-null → null | Prevents stranded filter state showing empty list. |
| No backend changes | None | All required data (active event, `event_id`, `brand_color`, `accent_color`) already shipped in Phase 1. |

## 5. Architecture & Data Flow

No network changes. No schema changes. No GAS changes.

`TabsViewModel` gains one dependency (`EventRepository`) and one `StateFlow<Event?>` field, loaded via the same `getActiveEvents().firstOrNull()` call `FeaturedViewModel` uses. Both viewmodels fetch independently; cost is one additional `/rest/v1/events` request per app cold start. Acceptable given the row count (single-digit events ever) and that Phase 1 has already proven the call in production.

Filtering happens client-side as the last AND clause in `TabsViewModel.filteredExhibitions`, after existing filters.

## 6. State Changes

### 6.1 `shared/.../data/model/FilterState.kt`

Add one field as the last constructor parameter (matches Phase 2a's `Event.coverImageUrl` convention), plus one `matches()` override to keep event-filtering logic collocated with the other flag filters.

**Important:** `FilterState.matches(exhibition)` today does not have access to the active event — the active event lives in `TabsViewModel`. So `eventOnly` cannot be evaluated inside `matches()` alone. Two options:

- **(chosen)** Keep `matches()` signature unchanged. Apply `eventOnly` filtering in `TabsViewModel.filteredExhibitions` as a separate filter step after `filter.matches(it)`. Rationale: `FilterState` stays free of repository state; the `eventOnly` clause is inherently viewmodel-scoped because it joins filter state with `activeEvent`.
- (rejected) Extend `matches(exhibition, activeEventId: String?)` — changes existing callers and tests.

Schema change:

```kotlin
data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // NEW — Phase 2b
) {
    fun matches(exhibition: Exhibition): Boolean {
        // ...existing body unchanged — eventOnly is not evaluated here
    }
}
```

`hasActiveFilters` in `ListScreen.kt` (`filter != FilterState()`) participates automatically because the default is `false`.

### 6.2 `composeApp/.../viewmodel/TabsViewModel.kt`

`TabsViewModel` already accepts `eventRepository: EventRepository` as a constructor dependency (wired through the factory at the bottom of the file). No DI change.

Add state:

```kotlin
private val _activeEvent = MutableStateFlow<Event?>(null)
val activeEvent: StateFlow<Event?> = _activeEvent.asStateFlow()
```

Load on init and expose a refresh hook:

```kotlin
init {
    // ...existing init...
    loadActiveEvent()

    // Auto-reset eventOnly when event disappears
    viewModelScope.launch {
        _activeEvent.collect { event ->
            if (event == null && _filterState.value.eventOnly) {
                _filterState.value = _filterState.value.copy(eventOnly = false)
            }
        }
    }
}

private fun loadActiveEvent() {
    viewModelScope.launch {
        runCatching {
            _activeEvent.value = eventRepository.getActiveEvents().firstOrNull()
        }.onFailure {
            _activeEvent.value = null
            // log but do not surface — List tab still works without event data
        }
    }
}

fun refresh() {
    // ...existing exhibition refresh...
    loadActiveEvent()
}
```

Extend `filteredExhibitions` — it uses the vararg `combine(...) { values -> ... }` form today with 6 flows. Add `_activeEvent` as a seventh source and apply the event filter alongside existing filters:

```kotlin
val filteredExhibitions: StateFlow<ExhibitionListState> =
    combine(
        _allExhibitions, _filterState, _selectedCity, _showMyListOnly,
        bookmarkedIds, _searchQuery, _activeEvent, // NEW
    ) { values ->
        val state = values[0] as ExhibitionListState
        val filter = values[1] as FilterState
        val city = values[2] as String?
        @Suppress("UNCHECKED_CAST") val myListOnly = values[3] as Boolean
        @Suppress("UNCHECKED_CAST") val bookmarked = values[4] as Set<String>
        val query = (values[5] as String).trim().lowercase()
        val activeEvent = values[6] as Event?
        when (state) {
            is ExhibitionListState.Loading -> ExhibitionListState.Loading
            is ExhibitionListState.Error -> state
            is ExhibitionListState.Success -> {
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val filtered = state.exhibitions
                    .filter { it.closingDate >= today }
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
                    .filter { // NEW — Phase 2b
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

Rationale for `activeEvent == null` short-circuit: when the event expires between viewmodel initialization and the filter application, the auto-reset collector (above) will drop `eventOnly` asynchronously. The short-circuit prevents the list from transiently becoming empty before the reset lands.

`clearAllFilters()` already resets `_filterState.value = FilterState()`; `eventOnly` defaults to false so no code change needed.

## 7. UI Surfaces

### 7.1 Pinned banner

**New file:** `composeApp/.../ui/components/EventListBanner.kt`

Placed **above** the `TabRow` in `ListScreen.kt`. Hidden when:
- `activeEvent == null`, OR
- `showMyListOnly == true` (banner is a promotion surface; My List shouldn't be interrupted).

```
Box(
  modifier = Modifier
    .fillMaxWidth()
    .height(36.dp)
    .background(event.brandColor)
    .clickable { onTap() },
) {
  Row(
    modifier = Modifier
      .align(Alignment.Center)
      .padding(horizontal = GallrSpacing.screenMargin),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = nameDisplay, // AnnotatedString with optional accent-color span on trailing word
      color = Color.White,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    Text(
      text = " · ",
      color = Color.White.copy(alpha = 0.75f),
      style = MaterialTheme.typography.labelMedium,
    )
    Text(
      text = if (lang == AppLanguage.KO) "지금 진행 중" else "NOW ON",
      color = Color.White.copy(alpha = 0.85f),
      style = MaterialTheme.typography.labelMedium,
    )
  }
}
```

Tap → `navigate("event_detail/${event.id}")` — Phase 1 route.

`event.brandColor` resolves via `parseHexColor(...)?.let { Color(it) } ?: MaterialTheme.colorScheme.onBackground`. Malformed hex → monochrome fallback, banner still renders.

Accent-color span on the trailing whitespace-separated token of `event.localizedName(lang)` mirrors the Phase 1 `EventPromotionCard` treatment and uses the same `AnnotatedString` approach that was verified safe on iOS 18.6.

### 7.2 Filter chip

**Modification:** `composeApp/.../ui/tabs/list/ListScreen.kt` — the flags row (around line 276).

Prepend a new chip before FEATURED, rendered only when `activeEvent != null`:

```kotlin
activeEvent?.let { event ->
    val brandColor = parseHexColor(event.brandColor)
        ?.let { Color(it) }
        ?: MaterialTheme.colorScheme.onBackground // graceful fallback
    GallrEventFilterChip(
        selected = filter.eventOnly,
        onClick = { viewModel.updateFilter { copy(eventOnly = !eventOnly) } },
        label = event.localizedName(lang),
        brandColor = brandColor,
    )
    Spacer(Modifier.width(GallrSpacing.sm))
}
```

**New private composable** in `ListScreen.kt` (sibling to `GallrFilterChip`):

```kotlin
@Composable
private fun GallrEventFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    brandColor: Color,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Box(
                Modifier
                    .size(4.dp)
                    .background(if (selected) Color.White else brandColor)
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        shape = RectangleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = brandColor,
            selectedContainerColor = brandColor,
            selectedLabelColor = Color.White,
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

Hex-parsing helper: reuse `com.gallr.shared.util.parseHexColor(String?): Long?` — the same helper used by `EventPromotionCard` in Phase 1. It returns `null` for malformed input, which enables the graceful fallback above.

### 7.3 Exhibition card surface treatment

**Modification:** `composeApp/.../ui/components/ExhibitionCard.kt`.

Add two parameters:

```kotlin
@Composable
fun ExhibitionCard(
    exhibition: Exhibition,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onTap: () -> Unit,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    eventTreatment: EventTreatment? = null, // NEW
)

data class EventTreatment(
    val brandColor: Color,
    val label: String, // already-localized event name, pre-truncated to ≤20 chars + ellipsis
)
```

When `eventTreatment != null`, two new `Box` children are added to the outer `Box`, placed after the existing image/scrim layers and before the content `Column`:

1. **Left edge** — thin vertical bar anchored to the start edge:
   ```kotlin
   Box(
       modifier = Modifier
           .align(Alignment.CenterStart)
           .fillMaxHeight()
           .width(3.dp)
           .background(eventTreatment.brandColor),
   )
   ```

2. **Corner label** — small chip anchored to the top-start corner:
   ```kotlin
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
   ```

**Spatial safety:** the corner label is anchored top-start; the bookmark heart is inside the content `Column` at top-end. They occupy opposite corners and do not overlap, so Z-order is not load-bearing. The existing content `Column` has `padding(GallrSpacing.md)` — both new boxes sit outside that padding, so they do not push or overlap text. The 3dp left edge sits on top of the image (if any), which is the intent — it should be a consistent signal regardless of whether the card is image-backed.

### 7.4 Call-site update

In `ListScreen.kt`, the `ExhibitionCard(…)` invocation gains:

```kotlin
eventTreatment = activeEvent
    ?.takeIf { exhibition.eventId == it.id }
    ?.let { event ->
        val localized = event.localizedName(lang)
        val brand = parseHexColor(event.brandColor)
            ?.let { Color(it) }
            ?: MaterialTheme.colorScheme.onBackground
        EventTreatment(
            brandColor = brand,
            label = if (localized.length > 20) localized.take(20) + "…" else localized,
        )
    },
```

## 8. Navigation & Language

Navigation: single reuse of Phase 1's `event_detail/{eventId}` route from the banner tap. No new routes.

Language: `Event.localizedName(lang)`, banner's "NOW ON"/"지금 진행 중", empty-state copy, and card corner label all swap on `LanguageRepository` state changes; all observed via `collectAsState()` in existing viewmodel wiring.

## 9. Edge Cases

| Scenario | Behavior |
|---|---|
| No active event | Banner, chip, card treatments hidden. List tab identical to today. |
| Active event, user on My List | Banner hidden; chip still visible (filter state is shared). Chip AND-combines with bookmark filter. |
| Active event, zero linked exhibitions | Banner and chip shown; chip tap yields tailored empty state (`§10`). |
| User had `eventOnly=true`, event expires mid-session | Next viewmodel init / pull-to-refresh sets `activeEvent=null`; collector resets `eventOnly=false`; chip + banner disappear. |
| No `accent_color` on event | Banner name renders entirely white (no accent span). Chip is unaffected. |
| Malformed `brand_color` | Fall back to `MaterialTheme.colorScheme.onBackground`; log warning. Banner, chip, card marks still render. Matches Phase 1's `EventPromotionCard`. |
| Card has `eventId` but image load fails | Card falls back to non-image layout (existing behavior) AND still shows edge + corner label. |
| Multiple events active | First by `start_date` (matches Phase 1). Other events' linked cards render normally. |
| Language switch | Banner, chip label, empty state, corner label all recompose. |
| Chip label overflow on narrow screens | Flag chip row already uses `horizontalScroll`; user scrolls horizontally. |
| Corner label overflow | Truncated to 20 chars + ellipsis. "Loop Lab Busan" = 14 chars — fits. |
| Banner tap while offline | Phase 1 `EventDetailScreen` already handles offline load via standard error state. |
| Dark theme | Brand color is fixed by the event, not theme-derived; renders identically. Card scrim/text logic untouched. |
| Pull-to-refresh on List tab | Re-fetches both exhibitions and active event. |

## 10. Empty-State Copy

`ListScreen.kt` branches empty-state messages in order: search → `showMyListOnly && bookmarks empty` → `showMyListOnly` → `cityName != null` → generic fallback.

Insert the new event branch **between the `showMyListOnly` branch and the `cityName` branch**. This ordering makes `filter.eventOnly` the primary signal whenever the event filter is on (the user's most recent action is tapping the brand-colored chip) but still lets My List's "no bookmarks" copy take precedence when the bookmark list is empty.

```kotlin
// after the showMyListOnly branches, before cityName branch
filter.eventOnly && activeEvent != null ->
    if (lang == AppLanguage.KO) "${activeEvent.nameKo}에 참여하는 전시가 없습니다."
    else "No exhibitions in ${activeEvent.nameEn}."
```

The `Clear Filters` action label stays as defined — `clearAllFilters()` resets `eventOnly` to false and the banner re-shows the full feed.

## 11. Testing

### 11.1 Unit tests (commonTest)

**`shared` — `FilterStateTest.kt`:**
- Assert `FilterState()` default has `eventOnly = false`.

**`composeApp` — `TabsViewModelTest.kt`** (new file if not present, or extend):
- `activeEvent` populates from repository on init.
- `activeEvent` resolves to `null` on repository empty list.
- `activeEvent` resolves to `null` on repository failure (no crash).
- `filteredExhibitions` with `eventOnly = true` and `activeEvent != null` returns only rows where `eventId == activeEvent.id`.
- `filteredExhibitions` with `eventOnly = true` and `activeEvent == null` is a no-op (returns same list as `eventOnly = false`).
- `eventOnly` auto-resets when `activeEvent` transitions non-null → null.
- `clearAllFilters()` resets `eventOnly` to false.

No new `EventRepository` tests — Phase 1 covers `getActiveEvents()`.

### 11.2 Manual smoke test

1. Loop Lab Busan active with 2+ linked exhibitions — open List tab → banner at top, Loop Lab chip leading flags row, linked cards show edge + corner label.
2. Tap banner → Event Detail opens (Phase 1 route).
3. Tap Loop Lab chip → list filters to event exhibitions. Tap again → un-filters.
4. Loop Lab + Busan city chip → AND logic works (shows only Busan event exhibitions).
5. Loop Lab + Seoul → empty state with tailored copy.
6. Switch to My List tab → banner hidden, chip still visible, chip filters bookmarked event exhibitions.
7. Toggle language → banner, chip label, empty state, corner label all swap.
8. Set `is_active = false` on event row, sync, relaunch → banner gone, chip gone, cards render without marks.
9. Dark theme → brand color renders identically; monochrome surrounds adapt.
10. Pull-to-refresh → re-fetches event; if event expired server-side, banner/chip disappear after refresh.

### 11.3 Visual regression

Not automated. No screenshot-test framework is configured in the project, and adding one is out of scope for Phase 2b.

## 12. Rollout

Single ship. All changes are client-only; no migration, no GAS redeployment, no coordinated operator steps.

1. Land the PR containing `FilterState` + `TabsViewModel` + `ListScreen` + `ExhibitionCard` + `EventListBanner` changes.
2. Build and ship the app (Android + iOS).
3. Verify manual smoke test steps on both platforms post-install.

Rollback: revert the PR. No data dependency changes mean there is no stale-schema risk on older app builds.

## 13. Open Items Carried Forward to Phase 2c

- Multi-event simultaneous display (map pin recoloring will need a per-event resolver).
- Logo asset shape (needed for map FAB).
- Whether a shared `EventStateHolder` is worth extracting (Phase 2b intentionally duplicates the `activeEvent` fetch between `FeaturedViewModel` and `TabsViewModel`; Phase 2c may revisit if the map viewmodel also needs it).
