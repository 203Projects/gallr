# 040 — Guest Editor

**Date:** 2026-05-12
**Priority:** P1
**Source:** `gallr_feature_request/260424-feature-request-guest-editor-p1.md`

## Problem

gallr surfaces curated exhibitions through `Featured` and `Editor's Picks`, both maintained by the internal team. There is no way to bring in an external editorial voice (curator, art professional, influencer) with their own selection. The app cannot grow through partnerships or borrow credibility from trusted art-world figures.

## Outcome

A guest editor's curated list of exhibitions is surfaced as a named filter chip ("[Name]'s Picks"). Tapping the chip reveals an editorial banner (label + name + title + bio) above the filtered list. Past guest editors and their tagged exhibitions are preserved for historical reference. The feature is invisible when no active editor exists.

## Scope

In scope (MVP):
- New `guest_editors` table in Supabase with bilingual name/title/bio + active flag + date range.
- New nullable `guest_editor_id` FK column on `exhibitions`.
- Apps Script sync supports the new column and validates the FK like `event_id`.
- New repository + DTO + domain model for guest editors.
- `FilterState` carries `showGuestPick: Boolean` plus a companion `activeGuestEditorId: String?`.
- Dynamic chip at the leftmost position of the filter row, only when an active editor exists.
- Editorial banner shown between filter row and exhibition list when the chip is selected.
- Mutual exclusivity: tapping the guest-pick chip clears every other filter; tapping any other filter clears guest-pick.
- Bilingual UI (EN + KO) for chip label, banner label, and empty state.

Out of scope (deferred to post-MVP per source feature request):
- "Past Guest Editors" browsing section.
- Guest-editor avatars / photos.
- Multiple simultaneous active guest editors.
- Guest editors self-managing their list.
- Guest editors as registered app users.

## Decisions

The following decisions were resolved during brainstorming and are binding for this spec:

1. **Editor source of truth:** admin (you/gallrmap) populates `guest_editors` directly via SQL in Supabase Studio. The Apps Script sync does NOT touch this table.
2. **Tagging:** exhibitions are linked via a `guest_editor_id` column in the existing gallr Google Sheet (mirrors the `event_id` workflow).
3. **Identifier:** human-readable slug (e.g. `minjung-kim`). Used as the table PK.
4. **Conflict rule:** when multiple `is_active = true` rows exist, the app picks the one with the most recent `active_from` date.
5. **Banner layout:** spec-direction "left-border accent" — white card, 3 px black left border, monospace "GUEST EDITOR" label, serif name (Playfair Display per design system), bilingual title, italic bio.
6. **Chip styling:** matches existing `GallrFilterChip` (white/border unselected → solid black/white text selected). No checkmark; no special accent — visual consistency with the rest of the filter row.
7. **Banner placement:** between filter chips and exhibition list. Banner scrolls with the list (not sticky).
8. **Banner entry animation:** `expandVertically() + fadeIn()` ~250 ms, ease-out, using Compose's `AnimatedVisibility`. Exit is `shrinkVertically() + fadeOut()` ~200/150 ms.
9. **Filter interaction:** tapping the guest-pick chip clears all other filters; tapping any other filter (region, featured, editor's picks, opening-this-week, closing-this-week, active-event) clears guest-pick. Mutually exclusive with everything.
10. **Banner dismissal:** always shown while chip is selected; deselect chip to hide. One piece of state, no close button.
11. **Empty state:** when chip is selected and no exhibitions match, show banner + empty-state copy. EN: `No exhibitions in this list`. KO: `선택된 전시가 없습니다`.
12. **Network error:** chip and banner are never rendered. Silent fail. No retry UI.
13. **Refresh policy:** fetch active editor once on app launch; re-fetch on pull-to-refresh of the list tab.

## Data model

### New table

```sql
create table guest_editors (
  id          text primary key,           -- slug, e.g. 'minjung-kim'
  name_ko     text not null,
  name_en     text not null default '',
  title_ko    text not null,
  title_en    text not null default '',
  bio_ko      text not null,
  bio_en      text not null default '',
  is_active   boolean not null default false,
  active_from date    not null default current_date,
  active_to   date,
  created_at  timestamptz default now(),
  updated_at  timestamptz default now()
);

alter table guest_editors enable row level security;
create policy "guest_editors are readable by anyone"
  on guest_editors for select using (true);
-- No insert/update policies — admin writes via service role through Supabase Studio.

create index guest_editors_active_idx
  on guest_editors (is_active, active_from desc);
```

### New column

```sql
alter table exhibitions
  add column guest_editor_id text references guest_editors(id) on delete set null;

create index exhibitions_guest_editor_idx
  on exhibitions (guest_editor_id) where guest_editor_id is not null;
```

### Active-editor query (used by the app)

```sql
select * from guest_editors
where is_active = true
  and (active_to is null or active_to >= current_date)
order by active_from desc
limit 1;
```

PostgREST equivalent (issued by the app via Ktor):

```
GET /rest/v1/guest_editors
  ?is_active=eq.true
  &or=(active_to.is.null,active_to.gte.{today})
  &order=active_from.desc
  &limit=1
```

## Sync pipeline (`gas/SyncExhibitions.gs`)

Three additions:

1. **`KNOWN_COLUMNS`** (currently lines 287-305): append `'guest_editor_id'` to the array. Without this addition, the next 5-minute sync deletes and re-inserts every exhibition row without the new column, wiping `guest_editor_id` back to NULL — the known KNOWN_COLUMNS trap.

2. **`fetchKnownGuestEditorIds()`**: new function mirroring `fetchKnownEventIds()` (line 57). Fetches all `id`s from `guest_editors` once per sync run. Returns a `{id: true}` set, or `null` if the fetch fails (validation disabled, sync proceeds).

3. **`buildRecord` FK branch**: in the column-mapping loop (around line 377 where `event_id` is handled), add an identical branch for `guest_editor_id`:
   ```javascript
   if (header === 'guest_editor_id') {
     var gid = String(raw || '').trim();
     record[header] = gid || null;
     return;
   }
   ```
   Plus a pre-validation step in the per-row loop (around line 159 where `event_id` is validated): if `guest_editor_id` is set and not in `knownGuestEditorIds`, skip the row with reason `Row N: guest_editor_id "<slug>" not found in guest_editors table — insert editor row first`.

The `guest_editors` table itself is never synced. Admin inserts rows directly via Supabase Studio SQL.

## Shared module

### New files

- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt`
  ```kotlin
  data class GuestEditor(
      val id: String,
      val nameKo: String,
      val nameEn: String,
      val titleKo: String,
      val titleEn: String,
      val bioKo: String,
      val bioEn: String,
  ) {
      fun localizedName(lang: AppLanguage): String
      fun localizedTitle(lang: AppLanguage): String
      fun localizedBio(lang: AppLanguage): String
  }
  ```
  `localized*` follow the existing `Exhibition.kt` pattern: EN falls back to KO when EN is empty.

- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt`
  `@Serializable` with `@SerialName` for every snake_case column. `toDomain()` returns a `GuestEditor`. Does NOT carry `is_active`/`active_from`/`active_to`/`created_at`/`updated_at` into the domain model — those exist only as DB filter inputs.

- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt`
  ```kotlin
  interface GuestEditorRepository {
      suspend fun getActiveGuestEditor(): Result<GuestEditor?>
  }
  ```
  Returns `Result.success(null)` when no active editor exists (normal state). Returns `Result.failure` on network/parse error; UI treats both as "no editor" (silent fail).

- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt`
  Delegates to a new method on the existing API client.

### Modified files

- `Exhibition.kt`: add `val guestEditorId: String? = null` near `eventId`. No other changes.
- `ExhibitionDto.kt`: add `@SerialName("guest_editor_id") val guestEditorId: String? = null`; pass through in `toDomain()`.
- `FilterState.kt`: add two fields:
  ```kotlin
  val showGuestPick: Boolean = false,
  val activeGuestEditorId: String? = null,
  ```
  Add matching clause inside `matches()`:
  ```kotlin
  val guestPickMatch = !showGuestPick ||
      (activeGuestEditorId != null && exhibition.guestEditorId == activeGuestEditorId)
  return regionsMatch && featuredMatch && picksMatch && weekMatch && guestPickMatch
  ```
  `activeGuestEditorId` is not user-controlled — the ViewModel writes it once when the active-editor fetch resolves. The field lives on `FilterState` so `matches()` can be self-contained.
- The network client file that hosts `fetchExhibitions()` (likely `ExhibitionApiClient.kt`; exact filename verified during implementation): add `suspend fun fetchActiveGuestEditor(): GuestEditor?` that issues the PostgREST query above and returns the first (or null) result.

## composeApp (UI)

### New file

- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/GuestEditorBanner.kt`
  Single composable rendering the chosen layout:
  - White surface background.
  - 3 dp solid black left border (using `Modifier.drawBehind` or a `Box` with a left-aligned colored `Spacer` — implementation detail).
  - Inner padding: `GallrSpacing.md` on top/right/bottom, `GallrSpacing.md` after the border on the left.
  - First line: monospace small-caps label, `if (lang == KO) "게스트 에디터" else "GUEST EDITOR"`.
  - Second line: editor's localized name in the existing serif display style used elsewhere in the app (Playfair Display per `DESIGN.md`).
  - Third line: editor's localized title in body-medium with `onSurfaceVariant` color.
  - Fourth line: editor's localized bio in body-medium italic, with `GallrSpacing.sm` top padding.

  Token names referenced above (`GallrSpacing.md/sm`, monospace label style, serif display style) are placeholders for whatever the codebase already uses for analogous treatments. Implementation must match existing tokens — no new design tokens introduced by this spec.

### Modified file

- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`

  At lines 290-329 (the filter chip Row): insert a new chip block as the **first** child, before the existing `activeEvent?.let { … }` block:
  ```kotlin
  activeGuestEditor?.let { editor ->
      GallrFilterChip(
          selected = filter.showGuestPick,
          onClick = { viewModel.toggleGuestPick() },
          label = if (lang == AppLanguage.KO) "${editor.nameKo}의 픽"
                  else "${editor.localizedName(lang)}'s Picks".uppercase(),
      )
      Spacer(Modifier.width(GallrSpacing.sm))
  }
  ```
  When `activeGuestEditor` is null (no editor, fetch failed, all editors inactive), the chip is not composed at all.

  Between the filter chip Row (line 329) and the action button Row (line 332): insert the banner wrapped in `AnimatedVisibility`:
  ```kotlin
  AnimatedVisibility(
      visible = filter.showGuestPick && activeGuestEditor != null,
      enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
      exit  = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150)),
  ) {
      activeGuestEditor?.let { GuestEditorBanner(it, lang) }
  }
  ```

  Empty-state copy: when `filter.showGuestPick` is on and the filtered list is empty, the existing "no exhibitions match" empty-state slot (whatever the file already uses for region/featured empty cases) shows the bilingual copy. If no centralized empty-state slot exists in the file yet, add one near the existing card list with a `when` selecting the appropriate empty-state string.

### ViewModel (file backing `ListScreen.kt`)

- New state: `val activeGuestEditor: StateFlow<GuestEditor?>`. Initial value `null`. Updated once on app launch from `GuestEditorRepository.getActiveGuestEditor()`, again on pull-to-refresh of the list tab.
- When the active-editor fetch resolves: `filterState.update { it.copy(activeGuestEditorId = editor?.id) }`. This keeps `FilterState.matches()` self-contained.
- New method `toggleGuestPick()`:
  ```kotlin
  fun toggleGuestPick() = updateFilter {
      val turningOn = !showGuestPick
      if (turningOn) FilterState(
          activeGuestEditorId = activeGuestEditorId,  // preserve ambient context
          showGuestPick = true,
      ) else copy(showGuestPick = false)
  }
  ```
- Every other filter setter (region toggle, featured toggle, editor's-picks toggle, opening-this-week toggle, closing-this-week toggle, event-only toggle) gets `showGuestPick = false` appended to its `copy()` call.
- `clearAllFilters()`: verify it resets `showGuestPick` to `false` as well; if it uses `FilterState(activeGuestEditorId = activeGuestEditorId)` (preserving ambient context), no change needed.
- Pull-to-refresh handler: call `getActiveGuestEditor()` in parallel with the existing exhibition refresh.

## Bilingual strings

| Element | Korean | English |
|---|---|---|
| Filter chip | `[이름]의 픽` (mixed case — Korean has no case convention) | `[Name]'s Picks` uppercased to `[NAME]'S PICKS` (matches existing chip style: `FEATURED`, `EDITOR'S PICKS`) |
| Banner label | `게스트 에디터` | `GUEST EDITOR` |
| Empty state | `선택된 전시가 없습니다` | `No exhibitions in this list` |

All three live inline in `ListScreen.kt`/`GuestEditorBanner.kt` via the existing `if (lang == AppLanguage.KO) … else …` pattern. No new resource files.

## Acceptance criteria

Mapped from the source feature request:

1. With no active editor row in DB → no chip rendered, no banner rendered, app behaves identically to current main.
2. With one active editor row + two exhibitions tagged with that editor's slug → chip shown leftmost; tap reveals banner + filters list to those two exhibitions.
3. Tap any other chip while guest-pick is selected → guest-pick clears, banner hides; the other chip becomes the only active filter.
4. Tap guest-pick while another chip is selected → other chip clears, guest-pick becomes the only active filter.
5. Active editor exists but no exhibitions tagged → chip visible; tap shows banner with empty-state copy below.
6. Pull-to-refresh after admin flips `is_active = false` on the editor row → chip and banner disappear; filter row reverts to current chips.
7. Network failure on the active-editor fetch → chip and banner never appear; no error UI.
8. Long editor name (e.g. 25+ characters) → chip truncates with ellipsis; banner shows full name.
9. EN and KO behave identically across all the above.

## Test plan

### Shared module unit tests (`commonTest`)

- `GuestEditorLocalizationTest.kt`: `localizedName/Title/Bio` returns EN when EN is non-empty; falls back to KO when EN is empty; returns KO unconditionally for KO language.
- `FilterStateGuestPickTest.kt` (or extend `FilterStateTest.kt`):
  - `showGuestPick = false` → all exhibitions pass.
  - `showGuestPick = true, activeGuestEditorId = "X", exhibition.guestEditorId = "X"` → passes.
  - `showGuestPick = true, activeGuestEditorId = "X", exhibition.guestEditorId = "Y"` → fails.
  - `showGuestPick = true, activeGuestEditorId = "X", exhibition.guestEditorId = null` → fails.
  - `showGuestPick = true, activeGuestEditorId = null, exhibition.guestEditorId = "X"` → fails (defensive: chip shouldn't be tappable in this state).
  - Existing `FilterStateTest` cases still pass (regression guard).

### Manual smoke test (for PR description)

Listed in the source feature request and Section 5 of the brainstorming output. Repeated here for the PR:

1. With no row in `guest_editors`: launch app → no chip, no banner.
2. Insert one `guest_editors` row + tag two exhibitions in the sheet, wait 5 min → leftmost chip reads `[Name]'s Picks`.
3. Tap chip → banner slides down (~250 ms), list narrows to two exhibitions.
4. Toggle other filters → mutual exclusivity holds (guest-pick clears; other chips clear when guest-pick is tapped).
5. Untag both exhibitions, wait 5 min, tap chip → empty-state copy shown.
6. Set `is_active = false`, pull-to-refresh → chip + banner gone.
7. Disable network → chip + banner never appear.
8. Switch EN ↔ KO at each step → identical behavior, correct localized strings.

## Out of scope reminders

- No avatar field on `guest_editors`. Deferred to post-MVP.
- No "Past Guest Editors" section. Deferred.
- No banner close button. The chip toggle is the only control.
- No DB constraint enforcing single-active. App tolerates multiple actives via the "most recent `active_from`" rule.

## Risk

- **Sync trap (KNOWN_COLUMNS):** must ship the Apps Script change in the same PR as the migration. If only the migration ships, the next sync wipes nothing (the column starts at NULL and stays NULL) — no data loss, just no effect. If only the Apps Script change ships, the script references a column that doesn't exist — Supabase rejects the insert with a 400 and the sync fails closed. Order: migration first, Apps Script change in the same PR, deploy together.
- **Editor onboarding ordering:** admin must `INSERT INTO guest_editors` BEFORE tagging exhibitions in the sheet, or the sync skips those rows with a clear log message. This mirrors the existing `event_id` discipline.
- **Reversibility:** drop the column on `exhibitions`, drop the `guest_editors` table, revert Kotlin + Apps Script changes. Fully removable in one migration.
- **Blast radius outside this feature:** zero changes to existing exhibition rendering, the date-label logic, or the existing filter math for region/featured/editor's-picks/this-week. New behavior is fully additive.

## Verification after implementation

- `./gradlew :shared:testDebugUnitTest` passes (new + regression tests green).
- Manual smoke test above completes on Android.
- Manual smoke test above completes on iOS (subject to the pre-existing `CityRegionFilterTest.kt` iOS compile issue noted during Feature 1 — does not block this PR; tracked separately).
- Apps Script logs from one sync run after deploying show `KNOWN_COLUMNS` includes `guest_editor_id` and no FK skip messages for exhibitions whose editor row exists.
