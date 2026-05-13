# 041 — Editor Hub

**Date:** 2026-05-12
**Priority:** P1
**Source:** Continuation of `040-guest-editor` (v1.5.0). Mockup approved during preliminary discussion; this spec captures the agreed design.

## Problem

The app currently surfaces editorial curation through two independent concepts:
- **Editor's Picks** — a Boolean column `exhibitions.is_editors_pick` controlling a filter chip ("Editor's Picks"). Curated by the gallr team.
- **Guest Editor** — an FK column `exhibitions.guest_editor_id` referencing a `guest_editors` table, surfaced as a dynamic chip ("[Name]'s Picks") with an editorial banner. Curated by partner curators, one active editor at a time.

Two parallel systems for the same conceptual primitive: "this exhibition was selected by an editor." The user sees two chips with overlapping semantics. The schema has two separate ways to express the same idea. Past guest editors disappear from the UI when the active editor flips, even though their picks are preserved in the DB.

## Outcome

A unified **Editors hub**: one filter-row chip (`Editors ›`) that acts as a navigation portal — not a filter. Tapping the chip pushes a selector screen showing all editors (gallr-team and guest, active and past) as tiles. Tapping a tile pushes a dedicated editor detail screen with the same banner + filtered list UI as v1.5.

Under the hood: one `editors` table (renamed from `guest_editors`), one `editor_id` FK on `exhibitions` (replacing both `is_editors_pick` and `guest_editor_id`), and one seed row `gallr-editors` representing the gallr team's house identity.

## Decisions

Locked during brainstorming:

1. **Full schema unification.** `is_editors_pick: Boolean` + `guest_editor_id: text` collapse into a single `editor_id: text` FK on `exhibitions`. The migration backfills existing data and drops the old columns.
2. **Past editors section.** Selector has two sections: "Currently curating" (active editors) and "Past editors" (inactive). Hierarchy preserves the archive without dominating the view.
3. **Full-screen selector + dedicated editor detail page.** Two-tap navigation: chip → selector → editor detail. Selector and detail are real screens in the nav stack, not banners or sheets.
4. **`guest_editors` renamed to `editors`.** The seed row `gallr-editors` is inserted as part of the migration. Existing `isEditorsPick = true` exhibitions backfill to `editor_id = 'gallr-editors'`.
5. **`is_featured` stays alone.** The Featured tab (separate UX, separate cadence) remains unchanged. Only `is_editors_pick` and `guest_editor_id` are folded into the editor model.
6. **Editors chip behaves identically on All Exhibitions and My List sub-tabs.** Same selector, same detail page, regardless of sub-tab. The chip is always a portal, not a filter.
7. **Old columns drop in the same migration.** `is_editors_pick` and `guest_editor_id` are removed atomically with the backfill. Rollback requires reverting the migration; backup before applying.
8. **Standard back-stack navigation.** Back from editor detail → selector. Back from selector → List tab. Chip on List has no selected state — it's always a portal.
9. **Apps Script workflow change documented in `gas/README.md` + CHANGELOG.** Admin (you) renames the sheet column from `guest_editor_id` to `editor_id`, deletes the `is_editors_pick` column, bulk-fills `gallr-editors` for previously flagged rows. Formula tip provided.
10. **Multiple simultaneous active guest editors allowed.** The v1.5 "one active at a time" rule was a single-banner workaround; the tile-based selector naturally supports multiple. No data-layer enforcement.

## Information architecture

### Before (v1.5.1)

List tab filter row has 5 chips:
1. `[Name]'s Picks` (conditional — only when an active guest editor exists)
2. `Featured`
3. `Editor's Picks`
4. `Opening This Week`
5. `Closing This Week`

Tapping `Editor's Picks` toggles `FilterState.showEditorsPick = true` and filters the list to `exhibition.isEditorsPick == true`. Tapping `[Name]'s Picks` toggles `FilterState.showGuestPick = true` and renders an inline banner. The two are mutually exclusive with each other and with the rest of the filter row.

### After (v1.6.0)

List tab filter row has 4 chips:
1. `Featured`
2. `Editors ›` (always visible — the disclosure caret indicates navigation)
3. `Opening This Week`
4. `Closing This Week`

Tapping `Editors ›` pushes `EditorSelectorScreen`. The chip has no selected state. The screen lists all editors as tiles:

- **Currently curating** section: the hero `gallr Editors` tile (left-border accent) at position 0, followed by any active guest editors sorted by `active_from desc`.
- **Past editors** section (hidden when empty): inactive editors sorted reverse-chronologically by `active_to` (most recently inactive first).

Tapping a tile pushes `EditorDetailScreen`. The screen renders the editor banner (same layout as v1.5's `GuestEditorBanner`, now renamed to `EditorBanner`) and the filtered exhibition list below. Banner adds an optional meta line showing the active-window dates + exhibition count.

Back arrow on detail → selector. Back arrow on selector → List tab.

## Data model

### Migration `017_unify_editors.sql`

```sql
-- Migration 017 — Unify editors (spec 041)
-- Rename guest_editors → editors. Seed the hardcoded gallr-editors row.
-- Replace exhibitions.is_editors_pick + guest_editor_id with a single editor_id FK.
--
-- ROLLBACK: Restore from backup. This migration drops two columns; reverting
-- the migration restores them as NULL — the original boolean/FK data is lost
-- unless restored from backup. Take a backup before applying.

alter table guest_editors rename to editors;

alter index guest_editors_active_idx rename to editors_active_idx;
alter policy "active guest_editors are readable by anyone" on editors
  rename to "active editors are readable by anyone";

-- Hardcoded gallr-editors seed row. Always active, immutable identity.
insert into editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from, active_to
) values (
  'gallr-editors',
  'gallr 에디터즈', 'gallr Editors',
  '하우스 에디터', 'House Editor',
  'gallr 팀이 선정한 상시 큐레이션.', 'Always-on selection by the gallr team.',
  true, '2026-01-01', null
);

-- New unified FK column on exhibitions.
alter table exhibitions
  add column editor_id text references editors(id) on delete set null;

-- Backfill: existing isEditorsPick=true rows point at the seed row.
update exhibitions set editor_id = 'gallr-editors' where is_editors_pick = true;

-- Backfill: existing guest_editor_id rows migrate to editor_id.
-- If a row had both is_editors_pick=true AND guest_editor_id, this overwrites
-- the gallr-editors assignment with the more specific guest editor — correct,
-- since tagging a specific guest is more deliberate than the team-pick flag.
update exhibitions set editor_id = guest_editor_id where guest_editor_id is not null;

-- Drop the two redundant columns.
alter table exhibitions drop column is_editors_pick;
alter table exhibitions drop column guest_editor_id;

-- Replace the partial index.
drop index if exists exhibitions_guest_editor_idx;
create index exhibitions_editor_idx
  on exhibitions (editor_id) where editor_id is not null;
```

### Domain model

`shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt` (renamed from `GuestEditor.kt`):

```kotlin
data class Editor(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val titleKo: String,
    val titleEn: String,
    val bioKo: String,
    val bioEn: String,
    val isActive: Boolean,
    val activeFrom: LocalDate,
    val activeTo: LocalDate?,
) {
    val isHouseEditor: Boolean get() = id == HOUSE_EDITOR_ID

    fun localizedName(lang: AppLanguage): String
    fun localizedTitle(lang: AppLanguage): String
    fun localizedBio(lang: AppLanguage): String

    /** True when today falls inside [activeFrom, activeTo] and is_active is set. */
    fun isCurrentlyActive(today: LocalDate): Boolean =
        isActive && (activeTo == null || activeTo >= today) && activeFrom <= today

    companion object {
        const val HOUSE_EDITOR_ID = "gallr-editors"
    }
}
```

Three new fields vs `GuestEditor` (v1.5): `isActive`, `activeFrom`, `activeTo`. These were dropped in v1.5 because the UI only needed one active editor at a time. Now the selector classifies editors into active/past sections, so the fields graduate into the domain model.

`isHouseEditor` is a computed flag — `gallr-editors` is the only sentinel ID, hardcoded in a companion-object constant.

`isCurrentlyActive(today)` is the classification predicate used by the selector ViewModel to split editors into the two sections.

### Exhibition model changes

`shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`:
- Field `guestEditorId: String?` renames to `editorId: String?`.
- Field `isEditorsPick: Boolean` is **removed**.

`shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`:
- `@SerialName("guest_editor_id") val guestEditorId: String?` renames to `@SerialName("editor_id") val editorId: String?`.
- `@SerialName("is_editors_pick") val isEditorsPick: Boolean` is removed.
- `toDomain()` updates accordingly.

### EditorDto

`shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt` (renamed):
- Same `@SerialName` mappings as `GuestEditorDto`.
- `toDomain()` now carries `isActive`, `activeFrom`, `activeTo` through into the domain model (they were dropped before).
- Date string fields (`active_from`, `active_to`) parse via `LocalDate.parse(...)`.

## Repository + API client

### EditorRepository

```kotlin
interface EditorRepository {
    /** All editors. Used by the selector. Sorted active-first then by active_from desc. */
    suspend fun getAllEditors(): Result<List<Editor>>

    /** One editor by slug. Used by the detail page (allows deep-link / direct nav). */
    suspend fun getEditorById(id: String): Result<Editor?>
}
```

The two methods exist independently rather than one cached list + filter so the detail page can load without the selector ever having been visited. Matches the existing `EventRepository` pattern.

### EditorApiClient

Two methods on the new `EditorApiClient.kt`:

```kotlin
suspend fun fetchAllEditors(): List<Editor> {
    val response = client.get("$restBase/editors?select=*&order=is_active.desc,active_from.desc")
    return response.body<List<EditorDto>>()
        .mapNotNull { it.toDomain() }
        .let { promoteHouseEditor(it) }
}

suspend fun fetchEditorById(id: String): Editor? {
    val response = client.get("$restBase/editors?id=eq.$id&select=*&limit=1")
    return response.body<List<EditorDto>>().firstOrNull()?.toDomain()
}

/**
 * Pulls Editor.HOUSE_EDITOR_ID to position 0 of the active section.
 * The DB-level sort can't express "house first, then guests by active_from desc"
 * in a single ORDER BY since house identity is not a sortable field.
 */
internal fun promoteHouseEditor(editors: List<Editor>): List<Editor> = …
```

`promoteHouseEditor` is extracted as a pure function so it can be unit-tested without an HTTP mock.

The active/past classification is computed in the ViewModel, not the API call. Reason: `isCurrentlyActive` depends on the current date in Asia/Seoul timezone, which is a UI concern. The API returns all editors; the ViewModel splits.

## Apps Script sync

`gas/SyncExhibitions.gs` changes:

1. **`KNOWN_COLUMNS` array**: remove `'is_editors_pick'`, remove `'guest_editor_id'`, add `'editor_id'`. This is the most critical sync change — without `editor_id` in `KNOWN_COLUMNS`, every 5-minute sync wipes the column to NULL (the KNOWN_COLUMNS trap).
2. **`fetchKnownGuestEditorIds` renames to `fetchKnownEditorIds`**. Query path changes from `/rest/v1/guest_editors?select=id` to `/rest/v1/editors?select=id`. Behavior unchanged.
3. **Per-row FK pre-validation** in `syncToSupabase`: rename the variable from `knownGuestEditorIds` to `knownEditorIds`, rename the cell variable from `guestEditorIdCell` to `editorIdCell`, update the column-header reference from `guest_editor_id` to `editor_id`, update the error message to reference the `editors` table.
4. **`buildRecord` FK branch**: rename the `guest_editor_id` branch to `editor_id`. Same null-coalescing logic.
5. **Remove the `is_editors_pick` Boolean branch in `buildRecord`** (the existing `if (header === 'is_featured' || header === 'is_editors_pick')` branch becomes `if (header === 'is_featured')`).

### Sheet workflow change (admin-facing)

`gas/README.md` adds a section explaining:
- The `is_editors_pick` column in the gallr exhibition sheet is **deprecated**. Delete it.
- The `guest_editor_id` column **renames to `editor_id`** (admins can rename the header in-place; data unchanged).
- For exhibitions previously tagged with `is_editors_pick = TRUE`, admin needs a one-time bulk-edit: type `gallr-editors` into the new `editor_id` column for those rows.

Sheet formula tip for the bulk-replace (Google Sheets ARRAYFORMULA): `=ARRAYFORMULA(IF(I2:I = TRUE, "gallr-editors", J2:J))` where I is the legacy `is_editors_pick` column and J is the legacy `guest_editor_id` column. Result becomes the new `editor_id` column.

The CHANGELOG includes a brief "Admin migration" note pointing to `gas/README.md`.

## UI

### `EditorSelectorScreen`

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt`.

Layout: top bar (back arrow + "EDITORS" label) → LazyColumn with two sections.

ViewModel: `EditorSelectorViewModel` exposes a `state: StateFlow<EditorSelectorState>` with `Loading`, `Error`, and `Success(active: List<Editor>, past: List<Editor>, exhibitionCounts: Map<String, Int>)`. The ViewModel:
- Loads all editors via `editorRepository.getAllEditors()`.
- Splits by `editor.isCurrentlyActive(todayInSeoul)`.
- Computes per-editor exhibition counts by joining client-side against `TabsViewModel.allExhibitions` (in-memory, already loaded; no extra DB hit).

`gallr-editors` always appears as the first tile in `Currently curating`, regardless of `active_from` ordering (post-sort step in `promoteHouseEditor`).

### `EditorDetailScreen`

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt`.

Layout: top bar → `EditorBanner` → LazyColumn of exhibition cards.

ViewModel: `EditorDetailViewModel(editorId)` exposes `editor: StateFlow<Editor?>` and `exhibitions: StateFlow<List<Exhibition>>`. It:
- Fetches the editor via `editorRepository.getEditorById(editorId)`. Failure or null → silent fallback (UI shows the existing error empty-state).
- Joins client-side against `TabsViewModel.allExhibitions` for the exhibition list, filtering to `exhibition.editorId == editorId`.

The two ViewModels are scoped to the screen via `viewModel(key = "editor-$editorId", factory = ...)` and destroyed when the screen pops.

### `EditorBanner`

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt` (renamed from `composeApp/ui/components/GuestEditorBanner.kt`, moved into the new editor folder).

Same layout as v1.5: 3 dp `onSurface` left accent bar, `labelSmall` uppercase "GUEST EDITOR" or "HOUSE EDITOR" label, editor name in `titleLarge`, title in `bodyMedium`, italic bio in `bodyMedium`.

One addition: optional `exhibitionCount: Int = 0` parameter. When `> 0`, renders a meta line below the bio with format `[formatted active_from] — [formatted active_to or "ongoing"] · N exhibitions`. The format follows existing date-range conventions in `Exhibition.localizedDateRange`. When `exhibitionCount == 0`, the meta line is hidden (the empty state below handles that case).

### `EditorTile`

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTile.kt`.

A single tile rendering:
- Small uppercase label (`HOUSE EDITOR` / `GUEST · NOW` / `MAY 2026` for past)
- Editor name in `titleLarge` (or `titleMedium` for non-house tiles to maintain hierarchy)
- Title/institution in `bodyMedium` `onSurfaceVariant`
- Exhibition count in `labelSmall`

The `gallr-editors` tile uses a 3 dp left-border accent (echoing the banner). Past tiles use `onSurfaceVariant` for dimmed text.

### `EditorTopBar`

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt`.

Reused by both selector and detail screens. Layout: back arrow on the left, small uppercase label ("EDITORS" / "EDITOR") centered or left-aligned.

### `ListScreen.kt` changes

- Remove the conditional guest-editor chip block (the leftmost chip rendered when `activeGuestEditor != null`).
- Remove the `AnimatedVisibility` banner block.
- Remove the `filter.showGuestPick && activeGuestEditor != null` empty-state branch.
- Remove the `showEditorsPick` chip.
- Insert a new always-visible chip `Editors ›` in its place. Calls `onEditorsChipTap()` (a new callback parameter on `ListScreen`).
- The remaining chips (Featured, Opening This Week, Closing This Week, plus the conditional event chip) keep using `viewModel.updateFilter { copy(...) }`. The `updateNonGuestFilter` helper goes away.

### `App.kt` navigation wiring

New state flags inside the `App()` composable:

```kotlin
var editorSelectorOpen by remember { mutableStateOf(false) }
var selectedEditorId by remember { mutableStateOf<String?>(null) }
```

Extend the existing `AnimatedContent` tree to include two new branches, in priority order (more specific first):

```kotlin
when {
    exhibition != null -> ExhibitionDetailScreen(...)
    eventId != null -> EventDetailScreen(...)
    selectedEditorId != null -> EditorDetailScreen(
        viewModel = viewModel(key = "editor-$selectedEditorId", factory = EditorDetailViewModel.factory(selectedEditorId!!, editorRepository, viewModel)),
        onBack = { selectedEditorId = null },
        onExhibitionTap = { selectedExhibition = it },
    )
    editorSelectorOpen -> EditorSelectorScreen(
        viewModel = viewModel(factory = EditorSelectorViewModel.factory(editorRepository, viewModel)),
        onBack = { editorSelectorOpen = false },
        onEditorTap = { selectedEditorId = it },
    )
    else -> Scaffold(...) // existing tab content
}
```

`ListScreen` receives a new callback parameter `onEditorsChipTap = { editorSelectorOpen = true }`.

Back stack: tapping back on detail clears `selectedEditorId` → selector becomes visible (since `editorSelectorOpen` is still true). Tapping back on selector clears `editorSelectorOpen` → tab content visible.

### `TabsViewModel.kt` cleanup

Remove:
- Field `activeGuestEditor: StateFlow<GuestEditor?>`
- Method `loadActiveGuestEditor()`
- Method `toggleGuestPick()`
- Method `updateNonGuestFilter()`
- `showGuestPick = false` / `activeGuestEditorId` clearing from `setCity`, `toggleRegion`, `clearAllFilters`
- The `_activeGuestEditor.collect` defensive collector in `init`
- Constructor parameter `guestEditorRepository: GuestEditorRepository`
- The corresponding factory parameter

Add: nothing. The chip is a pure navigation portal — no ViewModel state required for it.

### `FilterState.kt` cleanup

Remove:
- Field `showEditorsPick: Boolean`
- Field `showGuestPick: Boolean`
- Field `activeGuestEditorId: String?`
- The `picksMatch` clause in `matches()` (was `!showEditorsPick || exhibition.isEditorsPick`)
- The `guestPickMatch` clause in `matches()`

The `matches()` function reduces to region + featured + week filters.

### DI wiring

`composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`:
- `GuestEditorApiClient` rename → `EditorApiClient`
- `GuestEditorRepositoryImpl` rename → `EditorRepositoryImpl`
- Variable `guestEditorRepository` renames to `editorRepository`

`composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`: same renames.

`composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`:
- `App(...)` parameter `guestEditorRepository: GuestEditorRepository` → `editorRepository: EditorRepository`
- `TabsViewModel.factory(...)` call loses the editor-repo arg (since `TabsViewModel` no longer needs it)

## Bilingual strings

| Element | Korean | English |
|---|---|---|
| Filter chip | `에디터 ›` | `Editors ›` |
| Top bar (selector) | `에디터` | `Editors` |
| Top bar (detail) | `에디터` | `Editor` |
| Section: active | `현재 큐레이션` | `Currently curating` |
| Section: past | `지난 에디터` | `Past editors` |
| Tile label: house | `하우스 에디터` | `House Editor` |
| Tile label: active guest | `게스트 · 현재` | `Guest · Now` |
| Tile label: past guest | `2026.04` (year.month) | `Apr 2026` (Mon Year) |
| Tile exhibition count | `전시 N개` | `N exhibitions` |
| Banner label: house | `하우스 에디터` | `House Editor` |
| Banner label: guest | `게스트 에디터` | `Guest Editor` |
| Empty state | `선택된 전시가 없습니다` | `No exhibitions in this list` |
| Seed `gallr-editors` name | `gallr 에디터즈` | `gallr Editors` |
| Seed `gallr-editors` title | `하우스 에디터` | `House Editor` |
| Seed `gallr-editors` bio | `gallr 팀이 선정한 상시 큐레이션.` | `Always-on selection by the gallr team.` |

All UI strings live inline in their composable via the existing `if (lang == AppLanguage.KO) … else …` pattern. No new resource files. The seed strings live in migration `017_unify_editors.sql` (in the `INSERT`).

## Acceptance criteria

1. With the migration applied: `editors` table exists, contains the `gallr-editors` seed row, and previously `is_editors_pick = true` exhibitions now have `editor_id = 'gallr-editors'`. Previously `guest_editor_id` rows have the same value in `editor_id`. The `is_editors_pick` and `guest_editor_id` columns no longer exist.
2. App on `develop@v1.5.1` data: List tab shows the new chip layout (`Featured`, `Editors ›`, `Opening This Week`, `Closing This Week`). The old `[Name]'s Picks` chip and `Editor's Picks` chip are gone.
3. Tapping `Editors ›` opens the selector screen with `gallr Editors` as the first tile under `Currently curating`. The tile shows the seed row's localized name + title + exhibition count.
4. Tapping any tile opens the detail screen with the editor's banner and filtered exhibition list. Back arrow returns to the selector.
5. Multiple active guest editors all appear as tiles in `Currently curating`. Past guest editors (`is_active = false` OR `active_to < today`) appear under `Past editors`. The selector hides the `Past editors` section header when no past editors exist.
6. Bilingual: switching language between EN ↔ KO updates every label, name, title, bio, section heading, tile label, and empty state.
7. Network failure on the editor list fetch: selector shows the standard `GallrEmptyState` error message. Detail-page failure: empty list with no banner (silent fail).
8. Pull-to-refresh on the List tab triggers `TabsViewModel.refresh()` and re-loads exhibitions. Editor data is per-screen-instance so it's always fresh on screen entry.
9. `is_featured` and the Featured tab continue to work unchanged.
10. Apps Script sync run after deploying the migration: rows with valid `editor_id` slugs sync correctly. Rows with invalid (orphan) `editor_id` are skipped with a log message referencing the `editors` table.

## Test plan

### Shared module unit tests

- `EditorLocalizationTest.kt` (renamed from `GuestEditorLocalizationTest.kt`): 5 existing tests pass after renaming `GuestEditor` → `Editor` and adding the three new fields (`isActive`, `activeFrom`, `activeTo`) to the test fixture with default values.

- `EditorClassificationTest.kt` (new): `Editor.isCurrentlyActive(today)`:
  - `is_active = true, active_to = null, active_from <= today` → true
  - `is_active = true, active_to > today, active_from <= today` → true
  - `is_active = true, active_to = today, active_from <= today` → true (boundary, inclusive)
  - `is_active = true, active_to < today` → false
  - `is_active = false` → false (regardless of dates)
  - `is_active = true, active_from > today` → false (future-scheduled editor)

- `EditorApiClientSortTest.kt` (new): tests `promoteHouseEditor(list)` — pure function extracted for testability:
  - Empty list → empty list
  - List without house editor → list unchanged
  - List with house editor at position N → list with house at position 0, rest unchanged in order

- `FilterStateTest.kt` cleanup: remove the 5 guest-pick tests and the `showEditorsPick` test added in v1.4 / v1.5 (they reference fields that no longer exist). Test count drops from 14 to 8.

No tests for the new ViewModels or Compose screens — matches the existing codebase pattern (zero existing tests for `TabsViewModel`, `EventDetailViewModel`, any composable). Adding such infrastructure is a separate project-level decision.

### Manual smoke test (for PR description)

1. **Migration sanity check.** Apply `017_unify_editors.sql` to staging. Run `select * from editors where id = 'gallr-editors'` — confirm the seed row exists. Run `select count(*) from exhibitions where editor_id = 'gallr-editors'` — confirm previously-flagged rows backfilled. Run `select column_name from information_schema.columns where table_name = 'exhibitions' and column_name in ('is_editors_pick', 'guest_editor_id')` — confirm both columns are gone.
2. **Sheet migration.** Delete `is_editors_pick` column. Rename `guest_editor_id` to `editor_id`. Bulk-fill `gallr-editors` into `editor_id` for previously-flagged rows (per the formula tip in `gas/README.md`). Save and wait for the 5-min sync.
3. **App on List tab.** Launch the app. Filter row shows `Featured`, `Editors ›`, `Opening This Week`, `Closing This Week`. No `Editor's Picks` or `[Name]'s Picks` chip.
4. **Selector flow.** Tap `Editors ›`. Selector screen pushes in. `gallr Editors` is the first tile (left-border accent). Below it: active guest editors (if any) in `Currently curating`. Below: past editors (if any) in `Past editors`.
5. **Detail flow.** Tap the `gallr Editors` tile. Detail screen shows the house banner + all `editor_id = 'gallr-editors'` exhibitions. Back arrow returns to selector. Tap a guest editor tile. Detail screen shows that guest's banner + filtered list.
6. **Empty editor.** Tap an active editor with zero tagged exhibitions. Banner renders; empty-state copy below.
7. **Bilingual.** Switch EN ↔ KO at each step. Every label localizes. The `gallr Editors` seed row shows `gallr Editors` in EN, `gallr 에디터즈` in KO.
8. **Multiple active guests.** Insert a second active editor row in Supabase Studio with `is_active = true`, distinct `active_from`. Pull-to-refresh; selector shows both as tiles in `Currently curating`.
9. **Past editors.** Set an editor row's `active_to` to yesterday. Pull-to-refresh; that editor moves to `Past editors` section.
10. **Network failure.** Disable network. Open selector — error empty-state. Open detail page directly — banner missing, exhibition list empty.

## Out of scope

- **Editor avatars / photos.** Future iteration. The mockup doesn't include avatars.
- **Follow-editor / per-user editor subscriptions.** Future iteration.
- **Editor profile pages with social links / external bios.** The banner has the bio inline; richer profile content is a future iteration.
- **In-app editor management UI.** Admins continue to use Supabase Studio for inserts/updates.
- **`is_featured` unification.** Featured tab stays independent.
- **Migration rollback automation.** Restore from backup is the documented rollback path; no reverse migration script in this PR.

## Risk

- **Migration is destructive.** Two columns dropped atomically. Mitigation: take a Supabase backup before applying. Document the rollback path in the migration's leading comment.
- **Sheet migration is one-shot human work.** Admin (you) must bulk-replace `is_editors_pick = TRUE` rows with `editor_id = 'gallr-editors'`. If skipped, the next sync clears those rows' `editor_id` and the gallr Editors tile silently undercounts. Mitigation: explicit step in PR description, formula snippet in `gas/README.md`. No code-level enforcement.
- **Inter-version compatibility.** App users on v1.5.x running against the migrated schema will see the `Editor's Picks` and `[Name]'s Picks` chips break (the fields they query are gone). Acceptable for a mobile rolling-release; v1.6 binary takes over per-user as the App Store / Play Store push out.
- **`gallr-editors` magic string.** Hardcoded in: the migration, `Editor.HOUSE_EDITOR_ID` constant, `gas/README.md` formula tip. Three places, all commented. Acceptable since the seed row is conceptual identity, not data.
- **Sort logic drift.** `promoteHouseEditor` is a post-sort step. If a future refactor consolidates the sort into the DB query, the house tile drifts to wherever its `active_from` lands. Mitigation: dedicated unit test + code comment naming the contract.
- **Multiple actives + house tile.** When 3+ guest editors are active, the `Currently curating` section grows. Visual quality depends on admin discipline. No code-level cap.
- **Apps Script error message references.** Renaming `fetchKnownGuestEditorIds` → `fetchKnownEditorIds` changes the skip-reason log strings. Admins reading historical sync logs will see both styles for a while. Not a defect, but worth noting.

## File summary

### New
- `supabase/migrations/017_unify_editors.sql`
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt` (rename from `GuestEditor.kt`, +3 fields)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt` (rename, `toDomain()` carries new fields)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt` (rename, 2 methods + extracted `promoteHouseEditor`)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt` (rename, 2 methods)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt` (rename)
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt` (rename + relocate from `ui/components/GuestEditorBanner.kt`)
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTile.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorSelectorViewModel.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorDetailViewModel.kt`
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt` (rename + expand fixture)
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorClassificationTest.kt`
- `shared/src/commonTest/kotlin/com/gallr/shared/data/network/EditorApiClientSortTest.kt`

### Modified
- `gas/SyncExhibitions.gs` (KNOWN_COLUMNS, FK validation rename, buildRecord branch swap, remove is_editors_pick branch)
- `gas/README.md` (admin migration section + sheet formula tip)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt` (`guestEditorId` → `editorId`, drop `isEditorsPick`)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt` (same)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt` (drop 3 fields, drop 2 match clauses)
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt` (drop 6 obsolete tests)
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` (remove guest editor state + methods + repo param)
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt` (replace guest chip + banner + empty-state branch with single `Editors ›` portal chip)
- `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` (add `editorSelectorOpen` + `selectedEditorId` state, two new AnimatedContent branches, remove guest editor repo plumbing)
- `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` (DI renames)
- `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` (DI renames)
- `CHANGELOG.md`, `VERSION`, `TODOS.md`, `composeApp/build.gradle.kts`, `iosApp/iosApp.xcodeproj/project.pbxproj` (release bump for v1.6.0)

### Deleted
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt` (moved + renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt` (renamed)
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt` (renamed)

(Git detects most of these as renames if content is structurally similar.)

## Verification after implementation

- `./gradlew :shared:testDebugUnitTest` passes. Net test count: ~177 (180 from v1.5.1 minus 6 obsolete FilterStateTest cases plus 3+ new editor tests).
- `./gradlew :composeApp:compileDebugKotlinAndroid` passes.
- Manual smoke test above passes on Android.
- Manual smoke test above passes on iOS.
- Apps Script next sync run after deploying the migration shows `editor_id` populated, no orphan-ID skip messages for valid slugs, no `is_editors_pick` references in the script.
- `gas/README.md` reflects the new sheet workflow with the formula tip.
