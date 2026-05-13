# Editor Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the parallel `is_editors_pick` Boolean and `guest_editor_id` FK with a single unified `editor_id` FK + an "Editors" navigation portal (selector screen + dedicated editor detail page).

**Architecture:** Bottom-up: migration → Apps Script → Exhibition model (breaks consumers — fix them in the same commit) → `GuestEditor` slice renamed to `Editor` and expanded with `isActive` / `activeFrom` / `activeTo` + the new query methods → tests for the new logic → `FilterState` cleanup → new UI primitives (`EditorBanner` moved + renamed, `EditorTile`, `EditorTopBar`) → two new ViewModels → two new Screens → `TabsViewModel` and `ListScreen` cleanup → `App.kt` nav wiring + DI renames → verification + push.

**Tech Stack:** Kotlin 2.1.20 (KMP commonMain + commonTest), Compose Multiplatform 1.8.0, Material3, Ktor 2.9+, kotlinx-serialization, kotlinx-datetime, Supabase Postgres + PostgREST, Google Apps Script V8. Gradle tasks: `:shared:testDebugUnitTest` (fast JVM tests, executed not skipped), `:shared:compileKotlinMetadata` is SKIPPED by gradle in this repo — use `:shared:compileDebugKotlinAndroid` to actually verify Kotlin compiles. `:composeApp:compileDebugKotlinAndroid` for the composeApp module.

**Spec:** `specs/041-editor-hub/spec.md`
**Branch:** `041-editor-hub` (already created off `develop`, spec already committed)

---

## File Structure

**New files**
- `supabase/migrations/017_unify_editors.sql` — schema migration + seed
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt` — renamed + expanded
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt` — renamed + new fields
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt` — renamed + two methods + `promoteHouseEditor`
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt` — renamed + two methods
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt` — renamed
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt` — renamed + expanded fixture
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorClassificationTest.kt` — new
- `shared/src/commonTest/kotlin/com/gallr/shared/data/network/EditorApiClientSortTest.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt` — moved + renamed
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTile.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorSelectorViewModel.kt` — new
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorDetailViewModel.kt` — new

**Modified**
- `gas/SyncExhibitions.gs` — KNOWN_COLUMNS, FK validation rename, buildRecord branch swap, remove is_editors_pick branch
- `gas/README.md` — admin migration section + sheet formula tip
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt` — rename `guestEditorId` → `editorId`, drop `isEditorsPick`
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt` — same
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt` — drop 3 fields + 2 match clauses
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/StubExhibitionRepository.kt` — remove `isEditorsPick = …` lines
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt` — drop 6 obsolete tests + remove `isEditorsPick`/`guestEditorId` from helper
- `shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/ExhibitionDtoTest.kt` — remove `"is_editors_pick"` from JSON fixtures + drop the `isEditorsPick` assertion
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/CityRegionFilterTest.kt` — remove `isEditorsPick = false`
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt` — same
- `shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt` — same
- `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt` — same
- `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt` — same
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` — remove guest editor state + methods + repo param
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt` — replace guest chip + banner + empty-state branch with single `Editors ›` portal chip; revert 5 chip onClicks to `updateFilter`
- `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` — new state flags, two new AnimatedContent branches, remove guest editor repo plumbing
- `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` — DI renames
- `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` — DI renames
- `CHANGELOG.md`, `VERSION`, `TODOS.md`, `composeApp/build.gradle.kts`, `iosApp/iosApp.xcodeproj/project.pbxproj` — release bump to v1.6.0

**Deleted**
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt` (moved + renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt` (renamed)
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt` (renamed)
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt` (renamed)

---

## Task 1: Database migration

**Files:**
- Create: `supabase/migrations/017_unify_editors.sql`

- [ ] **Step 1: Write the migration**

Write to `/Users/hanshin/Documents/Projects/gallr/supabase/migrations/017_unify_editors.sql`:

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

- [ ] **Step 2: Verify the file exists**

```bash
cd /Users/hanshin/Documents/Projects/gallr
ls -la supabase/migrations/017_unify_editors.sql
```

Expected: file exists, ~45 lines, non-empty.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add supabase/migrations/017_unify_editors.sql
git commit -m "feat(db): unify editors — rename guest_editors → editors + drop legacy columns (spec 041)

Renames guest_editors → editors. Inserts the hardcoded gallr-editors
seed row (House Editor, always active). Adds exhibitions.editor_id FK,
backfills from is_editors_pick=true → 'gallr-editors' and from
guest_editor_id → editor_id, then drops both legacy columns.

ROLLBACK requires restoring from backup — the dropped columns lose
their original data on reversal. Take a Supabase backup before applying."
```

---

## Task 2: Apps Script sync update

**Files:**
- Modify: `gas/SyncExhibitions.gs`

The Apps Script changes are the riskiest part of the data layer (KNOWN_COLUMNS trap). Six surgical edits.

- [ ] **Step 1: Update `KNOWN_COLUMNS`**

Open `/Users/hanshin/Documents/Projects/gallr/gas/SyncExhibitions.gs`. Find the `KNOWN_COLUMNS` array (currently around lines 287-308). Two changes:

(a) Remove the line `'is_editors_pick',` (it's grouped on the same line as `'is_featured'` — split if needed).
(b) Remove the line `'guest_editor_id',`.
(c) Add a new line `'editor_id',` in their place.

After the edit, the array should look like:

```javascript
var KNOWN_COLUMNS = [
  // Bilingual text fields
  'name_ko', 'name_en',
  'venue_name_ko', 'venue_name_en',
  'city_ko', 'city_en',
  'region_ko', 'region_en',
  'description_ko', 'description_en',
  'address_ko', 'address_en',
  // Non-bilingual fields
  'opening_date', 'closing_date',
  'is_featured',
  'latitude', 'longitude',
  'cover_image_url',
  'hours',
  'contact',
  'reception_date',
  'opening_time',
  'event_id',
  'editor_id',
];
```

(Note: `is_featured` is now on its own line since `is_editors_pick` was removed.)

- [ ] **Step 2: Rename `fetchKnownGuestEditorIds` → `fetchKnownEditorIds`**

Find the `fetchKnownGuestEditorIds` function. Rename it to `fetchKnownEditorIds`. Update the URL inside from `/rest/v1/guest_editors?select=id` to `/rest/v1/editors?select=id`. Update the warning log message from `'WARN: guest_editors fetch returned '` to `'WARN: editors fetch returned '`. Update the trailing comment from `guest_editor_id validation disabled this run` to `editor_id validation disabled this run`.

- [ ] **Step 3: Update the per-row FK pre-validation**

Find the `knownGuestEditorIds = fetchKnownGuestEditorIds(...)` line in `syncToSupabase()`. Rename to `knownEditorIds = fetchKnownEditorIds(supabaseUrl, serviceKey)`.

Find the block:
```javascript
    var guestEditorIdCell = String(getCell(row, headerMap, 'guest_editor_id') || '').trim();
    if (guestEditorIdCell && knownGuestEditorIds !== null && !knownGuestEditorIds[guestEditorIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': guest_editor_id "' + guestEditorIdCell + '" not found in guest_editors table — insert editor row first');
      return;
    }
```

Replace with:
```javascript
    var editorIdCell = String(getCell(row, headerMap, 'editor_id') || '').trim();
    if (editorIdCell && knownEditorIds !== null && !knownEditorIds[editorIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': editor_id "' + editorIdCell + '" not found in editors table — insert editor row first');
      return;
    }
```

- [ ] **Step 4: Update `buildRecord` — rename FK branch + remove is_editors_pick branch**

In `buildRecord()`, find the block:

```javascript
    // FK column — blank cell must become null, never empty string,
    // or Postgres rejects with FK violation 23503 (no guest_editors row has id="").
    if (header === 'guest_editor_id') {
      var gid = String(raw || '').trim();
      record[header] = gid || null;
      return;
    }
```

Rename to:

```javascript
    // FK column — blank cell must become null, never empty string,
    // or Postgres rejects with FK violation 23503 (no editors row has id="").
    if (header === 'editor_id') {
      var eid = String(raw || '').trim();
      record[header] = eid || null;
      return;
    }
```

Then find the existing boolean branch:

```javascript
    if (header === 'is_featured' || header === 'is_editors_pick') {
      record[header] = parseBool(raw);
      return;
    }
```

Change to:

```javascript
    if (header === 'is_featured') {
      record[header] = parseBool(raw);
      return;
    }
```

- [ ] **Step 5: Syntax check + commit**

Verify parse:

```bash
cd /Users/hanshin/Documents/Projects/gallr
node -e "new Function(require('fs').readFileSync('gas/SyncExhibitions.gs','utf8')); console.log('OK: parses');"
```

Expected: `OK: parses`. If `SyntaxError`, fix the bracket/paren mismatch.

Verify all renames are complete:

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -n "guest_editor_id\|guestEditorId\|knownGuestEditorIds\|fetchKnownGuestEditorIds\|is_editors_pick" gas/SyncExhibitions.gs
```

Expected: no output (zero matches). If any match remains, fix it.

Commit:

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add gas/SyncExhibitions.gs
git commit -m "feat(sync): rename guest_editor_id → editor_id and drop is_editors_pick (spec 041)

KNOWN_COLUMNS now includes editor_id, drops is_editors_pick + guest_editor_id.
FK validation function + variable + buildRecord branch all rename.
The is_editors_pick Boolean branch in buildRecord is removed entirely.

After deploy, admins should rename the sheet column guest_editor_id →
editor_id, delete the is_editors_pick column, and bulk-fill
'gallr-editors' into editor_id for previously-flagged rows."
```

---

## Task 3: Exhibition + DTO field changes + fix all consumers

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/StubExhibitionRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/CityRegionFilterTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/ExhibitionDtoTest.kt`

This task touches 9 files. Dropping `isEditorsPick` from `Exhibition` will break the compile in every test fixture and stub repository that constructs an Exhibition. Renaming `guestEditorId` → `editorId` ripples too. Do it all in one commit — partial state is broken.

- [ ] **Step 1: Rename in `Exhibition.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`. The data class currently has these two field lines among others:

```kotlin
    val isFeatured: Boolean,
    val isEditorsPick: Boolean,
```

Remove the `isEditorsPick` line entirely:

```kotlin
    val isFeatured: Boolean,
```

Then find the field:

```kotlin
    val guestEditorId: String? = null,
```

Rename to:

```kotlin
    val editorId: String? = null,
```

No other changes to this file (`localized*` helpers are untouched).

- [ ] **Step 2: Rename in `ExhibitionDto.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`. Remove the line:

```kotlin
    @SerialName("is_editors_pick") val isEditorsPick: Boolean,
```

Rename the line:

```kotlin
    @SerialName("guest_editor_id") val guestEditorId: String? = null,
```

to:

```kotlin
    @SerialName("editor_id") val editorId: String? = null,
```

In `toDomain()`, remove the line:

```kotlin
            isEditorsPick = isEditorsPick,
```

And rename:

```kotlin
            guestEditorId = guestEditorId,
```

to:

```kotlin
            editorId = editorId,
```

- [ ] **Step 3: Fix `StubExhibitionRepository.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/repository/StubExhibitionRepository.kt`. There are 3 `isEditorsPick = …` lines (currently at lines 25, 47, 69). Delete all three lines. No other change.

- [ ] **Step 4: Fix the 6 test files that reference `isEditorsPick`**

For each of these files, remove the `isEditorsPick = false` (or `isEditorsPick = true`) line from the `Exhibition(...)` constructor call. The named-args pattern means deleting the line is safe — Kotlin's defaults handle the rest.

Files:
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/CityRegionFilterTest.kt` — line 45
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt` — line 42
- `shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt` — line 39
- `shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt` — line 37 (has `isFeatured = false, isEditorsPick = false,` on the same line — remove only `isEditorsPick = false,`)
- `shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt` — line 112 (same pattern: `isFeatured = false, isEditorsPick = false,` — remove only `isEditorsPick = false,`)

For each: open the file, locate the line, remove the `isEditorsPick = …` token (including the comma if it's mid-list). If the token was on a shared line like `isFeatured = false, isEditorsPick = false,`, the result becomes `isFeatured = false,`.

- [ ] **Step 5: Fix `ExhibitionDtoTest.kt`**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/ExhibitionDtoTest.kt`. This file has both JSON-string references (in test inputs) and a Kotlin-side assertion.

Remove these JSON keys/values from the JSON-string fixtures (currently at lines 28, 65, 133):

```
"is_editors_pick": false,
```

(or `"is_editors_pick": false` on a final-of-object line — remove the trailing comma from the preceding key in that case, since JSON requires no trailing commas).

Remove the Kotlin assertion at line 107:

```kotlin
        assertEquals(false, exhibition.isEditorsPick)
```

(Delete the entire line.)

If any test in this file also references the new `editor_id` JSON key, add a corresponding assertion for `exhibition.editorId == null` (or whatever the new test data dictates). Use your judgment: if the existing fixture doesn't include `editor_id`, the DTO default (`null`) means the field is fine without a test, and you can skip adding an assertion.

- [ ] **Step 6: Test-only update — `FilterStateTest.kt` helper**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`. The `exhibition()` helper at the top of the class has these parameters:

```kotlin
    private fun exhibition(
        region: String = "London",
        isFeatured: Boolean = false,
        isEditorsPick: Boolean = false,
        openingDate: kotlinx.datetime.LocalDate = yesterday,
        closingDate: kotlinx.datetime.LocalDate = inTenDays,
        guestEditorId: String? = null,
    ) = Exhibition(
        ...
        isFeatured = isFeatured,
        isEditorsPick = isEditorsPick,
        ...
        guestEditorId = guestEditorId,
    )
```

Remove the `isEditorsPick: Boolean = false,` parameter from the function signature. Remove the `isEditorsPick = isEditorsPick,` line from the `Exhibition(...)` call. Rename the `guestEditorId` parameter to `editorId` and the corresponding constructor line. (The body of test methods that pass `isEditorsPick = …` to the helper is fixed in Task 6, not here.) For now, just clean up the helper signature so the file compiles.

- [ ] **Step 7: Compile and run shared tests**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. If it fails inside `Exhibition.kt`, `ExhibitionDto.kt`, or `StubExhibitionRepository.kt`, the field changes have a typo — fix before continuing.

If it fails inside the test files, one of the 7 test-file edits was missed — find which test still references `isEditorsPick` and remove the line.

```bash
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: BUILD FAILED. The `FilterStateTest` file still has test bodies that call the helper with `isEditorsPick = …` — those tests will fail to compile. Task 6 fixes them. For now, confirm the failures are ONLY in `FilterStateTest.kt` referencing `isEditorsPick`, and not somewhere unexpected. If the failures span other files, the diff has more drift than the plan anticipated — investigate.

- [ ] **Step 8: Commit (intentionally broken — Task 6 fixes FilterStateTest)**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/repository/StubExhibitionRepository.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/CityRegionFilterTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionMapPinTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/TriggerRulesTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/notifications/NotificationSyncServiceTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/repository/EventRepositoryTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/ExhibitionDtoTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt
git commit -m "refactor(model): drop isEditorsPick + rename guestEditorId → editorId (spec 041)

Exhibition loses isEditorsPick; both fields' consumers in tests
and stub repos updated in the same commit. ExhibitionDto's
@SerialName('guest_editor_id') becomes @SerialName('editor_id');
@SerialName('is_editors_pick') field is removed.

FilterStateTest helper updated to drop the isEditorsPick parameter
and rename guestEditorId → editorId. Test method bodies still
reference the dropped helper parameters and will fail to compile —
Task 6 (FilterState + FilterStateTest cleanup) fixes those callers.

This commit is intentionally a broken intermediate state confined
to FilterStateTest.kt; the next tasks restore green build."
```

---

## Task 4: Rename `GuestEditor` slice → `Editor` (model + DTO + API client + repo + impl)

**Files:**
- Rename: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt` → `Editor.kt`
- Rename: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt` → `EditorDto.kt`
- Rename: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt` → `EditorApiClient.kt`
- Rename: `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt` → `EditorRepository.kt`
- Rename: `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt` → `EditorRepositoryImpl.kt`
- Rename: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt` → `EditorLocalizationTest.kt`

This task is a **pure rename** — file paths and identifiers swap from `GuestEditor` to `Editor`. Domain expansion (new fields, new methods) happens in Tasks 5 and 6. Keeping this task pure-rename means the diff is auditable as a search-and-replace.

- [ ] **Step 1: git mv each file**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git mv shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt \
       shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt
git mv shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt \
       shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt
git mv shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt \
       shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt
git mv shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt \
       shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt
git mv shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt \
       shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt
git mv shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt \
       shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt
```

- [ ] **Step 2: Identifier renames inside each file**

For each renamed file, update the identifiers. Use Edit on each one:

(a) `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt`:
- Class name `GuestEditor` → `Editor` (the `data class` declaration)
- Class name in the test fixture's return type — N/A inside the model file

(b) `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt`:
- Class name `GuestEditorDto` → `EditorDto`
- Import `import com.gallr.shared.data.model.GuestEditor` → `import com.gallr.shared.data.model.Editor`
- Return type and constructor inside `toDomain()`: `GuestEditor(...)` → `Editor(...)`
- Method signature: `fun toDomain(): GuestEditor` → `fun toDomain(): Editor`

(c) `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt`:
- Class name `GuestEditorApiClient` → `EditorApiClient`
- Import `import com.gallr.shared.data.model.GuestEditor` → `import com.gallr.shared.data.model.Editor`
- Import `import com.gallr.shared.data.network.dto.GuestEditorDto` → `import com.gallr.shared.data.network.dto.EditorDto`
- Method `suspend fun fetchActiveGuestEditor(): GuestEditor?` — DO NOT rename to `fetchActiveEditor()`; the next task replaces it with two new methods. Leave the method signature as-is for now (only rename the return type `GuestEditor?` → `Editor?` and update the DTO type in the call: `body<List<GuestEditorDto>>` → `body<List<EditorDto>>`).

(d) `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt`:
- Interface name `GuestEditorRepository` → `EditorRepository`
- Import `import com.gallr.shared.data.model.GuestEditor` → `import com.gallr.shared.data.model.Editor`
- Method signature: `suspend fun getActiveGuestEditor(): Result<GuestEditor?>` — DO NOT rename the method name yet; just update the return type `Result<GuestEditor?>` → `Result<Editor?>`. The method is replaced in Task 7.

(e) `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt`:
- Class name `GuestEditorRepositoryImpl` → `EditorRepositoryImpl`
- Constructor parameter type: `private val apiClient: GuestEditorApiClient` → `private val apiClient: EditorApiClient`
- Implementation interface: `: GuestEditorRepository` → `: EditorRepository`
- Import the new types accordingly
- Method body: `apiClient.fetchActiveGuestEditor()` — unchanged for now (method rename happens in Task 7)

(f) `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt`:
- Class name `GuestEditorLocalizationTest` → `EditorLocalizationTest`
- Helper return type: `... = GuestEditor(...)` → `... = Editor(...)`
- Helper signature already has `nameKo`, `nameEn`, etc. — keep those, but the `Editor` class is about to gain three new required fields (`isActive`, `activeFrom`, `activeTo`) in Task 5. For this task, ADD those three parameters to the helper now with safe defaults, but DON'T add them to the `Editor` data class yet (that's Task 5).

  Wait — that won't compile. The order matters: rename the class first (this task), then add fields (Task 5). If `Editor` doesn't have `isActive` etc. yet, the helper that passes them won't compile.

  **Resolution:** Don't add the new fields to the helper in this task. Just rename `GuestEditor` → `Editor` everywhere. The helper still constructs `Editor(id = …, nameKo = …, …, bioEn = …)` with the original 7 fields. Task 5 adds the new fields to BOTH `Editor.kt` AND the helper in one atomic step.

- [ ] **Step 3: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: BUILD FAILED — `TabsViewModel.kt`, `App.kt`, `MainActivity.kt`, `MainViewController.kt`, and `ListScreen.kt` all reference the old `GuestEditor*` types. Those will be fixed in Tasks 12-14. **For this task, verify the only failures are at those call sites** (search for "Unresolved reference" and confirm the symbol is one of `GuestEditor`, `GuestEditorRepository`, `GuestEditorApiClient`, or `GuestEditorRepositoryImpl`).

If any failure is INSIDE the just-renamed files (e.g. an identifier wasn't renamed), fix it.

- [ ] **Step 4: Verify the 6 renames are complete inside shared/**

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -rn "GuestEditor" shared/src 2>/dev/null
```

Expected: no output. If matches remain, there's a stray identifier to rename. (Note: this grep will still hit `composeApp/src` references — that's expected and handled in Tasks 12-14.)

- [ ] **Step 5: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt
git commit -m "refactor(model): rename GuestEditor → Editor in shared module (spec 041)

Pure rename — file paths and identifiers only. No behavior change.

Six files moved via git mv (git detects them as renames in the diff).
All identifiers updated: class names, return types, imports, interface
implementation. Method names (fetchActiveGuestEditor, getActiveGuestEditor)
keep their old names in this commit — Tasks 7-8 replace them with the
new two-method API.

Build is still broken at composeApp consumer sites (TabsViewModel,
App.kt, MainActivity, MainViewController, ListScreen). Tasks 12-14
fix those."
```

---

## Task 5: Expand `Editor` model with `isActive`, `activeFrom`, `activeTo` + new helpers

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt`

- [ ] **Step 1: Add fields + helpers to `Editor.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt`. Add the imports at the top:

```kotlin
import kotlinx.datetime.LocalDate
```

Replace the current data class with:

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

    fun localizedName(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> nameEn.ifEmpty { nameKo }
        AppLanguage.KO -> nameKo
    }

    fun localizedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> titleEn.ifEmpty { titleKo }
        AppLanguage.KO -> titleKo
    }

    fun localizedBio(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> bioEn.ifEmpty { bioKo }
        AppLanguage.KO -> bioKo
    }

    /**
     * True when today falls inside [activeFrom, activeTo] (inclusive on both
     * ends, treating null activeTo as "open-ended") AND is_active is set.
     *
     * Used by the selector ViewModel to split editors into "Currently
     * curating" vs "Past editors" sections.
     */
    fun isCurrentlyActive(today: LocalDate): Boolean =
        isActive && activeFrom <= today && (activeTo == null || activeTo >= today)

    companion object {
        const val HOUSE_EDITOR_ID = "gallr-editors"
    }
}
```

- [ ] **Step 2: Update `EditorDto.kt` to carry the new fields through `toDomain()`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt`. The DTO already had `isActive`, `activeFrom`, `activeTo` parameters (held over from v1.5 — they were already deserialized but dropped by `toDomain()`).

Add the import:

```kotlin
import kotlinx.datetime.LocalDate
```

In `toDomain()`, pass the new fields into the `Editor(...)` construction. The `activeFrom` is a non-null `LocalDate` in the domain model, so default to `LocalDate(2000, 1, 1)` if the DTO has null (defensive). `activeTo` is nullable.

Replace the existing `toDomain()` with:

```kotlin
    fun toDomain(): Editor = Editor(
        id = id,
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
        isActive = isActive,
        activeFrom = activeFrom?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { LocalDate(2000, 1, 1) }
        } ?: LocalDate(2000, 1, 1),
        activeTo = activeTo?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        },
    )
```

The DTO fields `activeFrom: String?` and `activeTo: String?` were carried over from v1.5; double-check they exist in the DTO class. If they don't, add them:

```kotlin
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("active_from") val activeFrom: String? = null,
    @SerialName("active_to") val activeTo: String? = null,
```

- [ ] **Step 3: Update the test helper**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt`. The `editor()` helper currently constructs `Editor(...)` with the old 7 fields. Add the three new ones with sensible defaults.

Add imports at the top:

```kotlin
import kotlinx.datetime.LocalDate
```

Update the helper:

```kotlin
    private fun editor(
        nameKo: String = "김민정",
        nameEn: String = "Minjung Kim",
        titleKo: String = "큐레이터",
        titleEn: String = "Curator",
        bioKo: String = "한국어 소개",
        bioEn: String = "English bio",
        isActive: Boolean = true,
        activeFrom: LocalDate = LocalDate(2026, 1, 1),
        activeTo: LocalDate? = null,
    ) = Editor(
        id = "minjung-kim",
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
        isActive = isActive,
        activeFrom = activeFrom,
        activeTo = activeTo,
    )
```

The 5 existing tests in this file (`localizedName returns English when …`, etc.) don't care about the new fields — they use the defaults. No test-method-body changes needed.

- [ ] **Step 4: Run the existing test class**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EditorLocalizationTest" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL with all 5 tests passing. If failure, the new field additions broke something.

- [ ] **Step 5: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Editor.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EditorDto.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorLocalizationTest.kt
git commit -m "feat(model): expand Editor with isActive/activeFrom/activeTo (spec 041)

Adds three fields previously dropped by GuestEditor's toDomain():
isActive, activeFrom (LocalDate, non-null), activeTo (LocalDate?).
Plus the HOUSE_EDITOR_ID constant, isHouseEditor computed flag,
and isCurrentlyActive(today) classification predicate used by
the selector ViewModel to split editors into sections.

EditorDto.toDomain() now passes these through with date parsing.
EditorLocalizationTest helper updated to construct the new shape
with sensible defaults; existing 5 tests pass unchanged."
```

---

## Task 6: `FilterState` cleanup + `FilterStateTest` cleanup

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`

After Task 3 finished, the build was broken inside `FilterStateTest.kt` (test methods reference the removed `isEditorsPick` helper parameter). This task fixes that AND drops the v1.4/v1.5 guest-pick fields from `FilterState` itself.

- [ ] **Step 1: Clean up `FilterState.kt`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`. Replace the entire file content with:

```kotlin
package com.gallr.shared.data.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // Phase 2b — filter list to active-event-linked exhibitions
) {
    /**
     * Returns true if [exhibition] satisfies all active filters.
     *
     * Logic:
     * - regions: OR within list; empty = no region restriction
     * - showFeatured: ANDed with the result
     * - openingThisWeek / closingThisWeek: OR'd with each other, then ANDed with rest
     */
    fun matches(exhibition: Exhibition): Boolean {
        val today = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val weekEnd = today.plus(6, DateTimeUnit.DAY)

        val regionsMatch = regions.isEmpty() || exhibition.regionKo in regions
        val featuredMatch = !showFeatured || exhibition.isFeatured
        val weekMatch = (!openingThisWeek && !closingThisWeek) ||
            (openingThisWeek && exhibition.openingDate in today..weekEnd) ||
            (closingThisWeek && exhibition.closingDate in today..weekEnd)

        return regionsMatch && featuredMatch && weekMatch
    }
}
```

Compared to the v1.5.1 version, this removes:
- `val showEditorsPick: Boolean = false`
- `val showGuestPick: Boolean = false`
- `val activeGuestEditorId: String? = null`
- The `picksMatch` clause in `matches()`
- The `guestPickMatch` clause in `matches()`
- The corresponding docstring line about `showEditorsPick`

- [ ] **Step 2: Clean up `FilterStateTest.kt`**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`. Two cleanups:

(a) Remove the entire `showEditorsPick true only matches editors pick exhibitions` test (was added in v1.4 — references `showEditorsPick` which no longer exists). Removes one `@Test fun` block.

(b) Remove the entire "// ── Spec 040: guest pick" section, which contains 5 tests:
- `showGuestPick false matches all exhibitions regardless of editor tag`
- `showGuestPick true with matching editor id passes`
- `showGuestPick true with different editor id fails`
- `showGuestPick true with null editor on exhibition fails`
- `showGuestPick true with null active editor id fails defensively`

Remove the section header comment too.

(c) The `multiple active filters are ANDed` test references `showFeatured = true` — keep that. Just confirm no references to `showEditorsPick`, `showGuestPick`, `activeGuestEditorId`, or `isEditorsPick` remain in the body of any test method.

(d) The `exhibition()` helper from Task 3 step 6 should already have `isEditorsPick` removed and `guestEditorId` renamed to `editorId`. Confirm.

- [ ] **Step 3: Compile + run**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.FilterStateTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. All remaining `FilterStateTest` tests pass (8 total: default-matches-all, showFeatured true filters featured, regions OR-list, regions empty matches all, openingThisWeek, closingThisWeek, opening+closing OR, multiple ANDed, default eventOnly false = 9 — actually, the existing v1.5.1 file had 14 tests = 9 original + 5 guest pick. After removing the 5 guest-pick tests + the 1 showEditorsPick test = 8 remaining tests).

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` for the entire shared test suite. The build was broken before this task; it's now green.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt
git commit -m "refactor(filter): drop showEditorsPick/showGuestPick/activeGuestEditorId from FilterState (spec 041)

These three fields existed because Editor's Picks and Guest Editor
were filter chips with toggle state. Both chips now collapse into
a single navigation portal that pushes a new screen — no filter
state needed.

matches() simplifies to region + featured + week (loses the
picksMatch and guestPickMatch clauses). FilterStateTest drops the
6 obsolete tests (1 showEditorsPick + 5 guest-pick from v1.4/v1.5).

Restores green build after Task 3's intentional broken intermediate."
```

---

## Task 7: `EditorRepository` interface + impl — switch to two-method API

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt`

The v1.5 single-active-editor method (`getActiveGuestEditor`) is replaced with two: `getAllEditors()` for the selector, `getEditorById(id)` for the detail page.

- [ ] **Step 1: Replace `EditorRepository.kt`**

Replace the contents of `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt` with:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor

interface EditorRepository {
    /**
     * Returns Result.success with all editors (active and past).
     * Sorted active-first then by active_from desc. The 'gallr-editors'
     * seed row is promoted to position 0 of the active subset.
     * Returns Result.failure on network/parse error.
     */
    suspend fun getAllEditors(): Result<List<Editor>>

    /**
     * Returns Result.success(Editor) for the matching slug.
     * Returns Result.success(null) when no editor matches.
     * Returns Result.failure on network/parse error.
     */
    suspend fun getEditorById(id: String): Result<Editor?>
}
```

The old `getActiveGuestEditor` method is gone.

- [ ] **Step 2: Replace `EditorRepositoryImpl.kt`**

Replace the contents of `shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt` with:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.EditorApiClient

class EditorRepositoryImpl(
    private val apiClient: EditorApiClient,
) : EditorRepository {

    override suspend fun getAllEditors(): Result<List<Editor>> =
        runCatching { apiClient.fetchAllEditors() }

    override suspend fun getEditorById(id: String): Result<Editor?> =
        runCatching { apiClient.fetchEditorById(id) }
}
```

- [ ] **Step 3: Compile (build remains broken until Task 8 + 12-14)**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: BUILD FAILED — `EditorApiClient.fetchAllEditors()` and `EditorApiClient.fetchEditorById(id)` don't exist yet (those are Task 8). Plus composeApp consumers are still broken.

Confirm the failure is the missing API client methods (and the existing composeApp issues from Task 4), not anything else.

- [ ] **Step 4: Commit (still broken — Task 8 adds the API client methods)**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepository.kt \
        shared/src/commonMain/kotlin/com/gallr/shared/repository/EditorRepositoryImpl.kt
git commit -m "feat(repo): switch EditorRepository to two-method API (spec 041)

getAllEditors() for the selector (returns full list, classification
done in ViewModel) and getEditorById(id) for the detail page
(allows deep-link / direct nav without selector ever loading).

Replaces the v1.5 single-active-editor method. Build is broken at
EditorApiClient call sites until Task 8 adds the new methods."
```

---

## Task 8: `EditorApiClient` — replace single-active method with two new methods + `promoteHouseEditor`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt`

- [ ] **Step 1: Replace `EditorApiClient.kt`**

Replace the file contents with:

```kotlin
package com.gallr.shared.data.network

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.dto.EditorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// gallr's editorial schedule is anchored to Seoul. Computing "today" against
// device-local time would let a user temporarily abroad see a future editor
// activate ~24h early (or a finished editor linger ~24h late). Pinning the
// query date to Asia/Seoul keeps every device in sync with the editor's
// stated active_from / active_to dates.
// (Used by callers that need a current-date filter — currently the
// selector ViewModel computes isCurrentlyActive client-side, not here.)

class EditorApiClient(
    supabaseUrl: String,
    anonKey: String,
) {
    private val restBase = "$supabaseUrl/rest/v1"

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.INFO
        }
        defaultRequest {
            headers.append("apikey", anonKey)
            headers.append("Authorization", "Bearer $anonKey")
        }
    }

    /**
     * Fetches all editors. The selector ViewModel splits them into "Currently
     * curating" and "Past editors" based on Editor.isCurrentlyActive(today).
     *
     * DB-side sort: is_active desc, active_from desc. Then the pure
     * promoteHouseEditor() pass pulls the gallr-editors row to position 0.
     */
    suspend fun fetchAllEditors(): List<Editor> {
        val query = "select=*&order=is_active.desc,active_from.desc"
        val editors = client.get("$restBase/editors?$query")
            .body<List<EditorDto>>()
            .map { it.toDomain() }
        return promoteHouseEditor(editors)
    }

    /**
     * Fetches a single editor by slug. Returns null when no row matches.
     */
    suspend fun fetchEditorById(id: String): Editor? {
        val query = "select=*&id=eq.$id&limit=1"
        return client.get("$restBase/editors?$query")
            .body<List<EditorDto>>()
            .firstOrNull()
            ?.toDomain()
    }
}

/**
 * Pulls the Editor.HOUSE_EDITOR_ID row to position 0 of the list.
 * If it isn't present, returns the list unchanged.
 *
 * Extracted as a top-level pure function (not a method) so the contract
 * can be unit-tested without an HTTP mock. See EditorApiClientSortTest.
 */
internal fun promoteHouseEditor(editors: List<Editor>): List<Editor> {
    val (house, rest) = editors.partition { it.id == Editor.HOUSE_EDITOR_ID }
    return house + rest
}
```

Three changes vs the v1.5.1 version:
- Method `fetchActiveGuestEditor()` is replaced with `fetchAllEditors()` + `fetchEditorById(id)`.
- The Asia/Seoul timezone import + constant are removed (no longer needed; classification is client-side in the ViewModel).
- New `promoteHouseEditor` pure function at module level for testability.

- [ ] **Step 2: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: BUILD FAILED — composeApp consumers (`TabsViewModel`, `App.kt`, etc.) still reference removed/renamed symbols. The shared module itself should now compile.

Verify the shared module compiles cleanly even if composeApp doesn't:

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid 2>&1 | grep -E "shared/src" | head -10
```

Expected: no `shared/src` errors. If there are, fix them before moving on.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/EditorApiClient.kt
git commit -m "feat(network): EditorApiClient gets fetchAllEditors + fetchEditorById (spec 041)

Replaces fetchActiveGuestEditor() with two new methods.
fetchAllEditors() returns the full list sorted by is_active desc /
active_from desc with the gallr-editors house row promoted to
position 0. fetchEditorById(id) does a single-row lookup.

promoteHouseEditor() is extracted as a top-level pure function so
it can be unit-tested without an HTTP mock (Task 9).

Asia/Seoul timezone is no longer needed in the client — the
selector ViewModel computes isCurrentlyActive(today) per-device."
```

---

## Task 9: `EditorClassificationTest` + `EditorApiClientSortTest` — TDD the pure logic

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorClassificationTest.kt`
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/data/network/EditorApiClientSortTest.kt`

Both `Editor.isCurrentlyActive` and `promoteHouseEditor` are pure functions and worth unit tests. Quick TDD pass.

- [ ] **Step 1: Write `EditorClassificationTest.kt`**

Create `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorClassificationTest.kt`:

```kotlin
package com.gallr.shared.data.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorClassificationTest {

    private val today = LocalDate(2026, 5, 12)

    private fun editor(
        isActive: Boolean = true,
        activeFrom: LocalDate = LocalDate(2026, 1, 1),
        activeTo: LocalDate? = null,
    ) = Editor(
        id = "test-editor",
        nameKo = "k", nameEn = "e",
        titleKo = "k", titleEn = "e",
        bioKo = "k", bioEn = "e",
        isActive = isActive,
        activeFrom = activeFrom,
        activeTo = activeTo,
    )

    @Test
    fun `active with open-ended window is currently active`() {
        assertTrue(editor(isActive = true, activeTo = null).isCurrentlyActive(today))
    }

    @Test
    fun `active with future activeTo is currently active`() {
        assertTrue(editor(isActive = true, activeTo = today.plus(7, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }

    @Test
    fun `active with activeTo equal to today is currently active (inclusive)`() {
        assertTrue(editor(isActive = true, activeTo = today).isCurrentlyActive(today))
    }

    @Test
    fun `active with past activeTo is NOT currently active`() {
        assertFalse(editor(isActive = true, activeTo = today.plus(-1, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }

    @Test
    fun `inactive flag overrides any date window`() {
        assertFalse(editor(isActive = false, activeTo = null).isCurrentlyActive(today))
        assertFalse(editor(isActive = false, activeFrom = LocalDate(2020, 1, 1), activeTo = LocalDate(2099, 1, 1)).isCurrentlyActive(today))
    }

    @Test
    fun `future activeFrom is NOT yet currently active`() {
        assertFalse(editor(isActive = true, activeFrom = today.plus(1, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }
}
```

- [ ] **Step 2: Write `EditorApiClientSortTest.kt`**

Create `shared/src/commonTest/kotlin/com/gallr/shared/data/network/EditorApiClientSortTest.kt`:

```kotlin
package com.gallr.shared.data.network

import com.gallr.shared.data.model.Editor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorApiClientSortTest {

    private fun editor(id: String) = Editor(
        id = id,
        nameKo = id, nameEn = id,
        titleKo = id, titleEn = id,
        bioKo = id, bioEn = id,
        isActive = true,
        activeFrom = LocalDate(2026, 1, 1),
        activeTo = null,
    )

    @Test
    fun `empty list returns empty`() {
        assertTrue(promoteHouseEditor(emptyList()).isEmpty())
    }

    @Test
    fun `list without house editor is unchanged`() {
        val input = listOf(editor("a"), editor("b"), editor("c"))
        val result = promoteHouseEditor(input)
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `house editor at the end is promoted to position 0`() {
        val input = listOf(editor("a"), editor("b"), editor(Editor.HOUSE_EDITOR_ID))
        val result = promoteHouseEditor(input)
        assertEquals(listOf(Editor.HOUSE_EDITOR_ID, "a", "b"), result.map { it.id })
    }

    @Test
    fun `house editor in the middle is promoted to position 0 preserving order of rest`() {
        val input = listOf(editor("a"), editor(Editor.HOUSE_EDITOR_ID), editor("b"))
        val result = promoteHouseEditor(input)
        assertEquals(listOf(Editor.HOUSE_EDITOR_ID, "a", "b"), result.map { it.id })
    }
}
```

- [ ] **Step 3: Run**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EditorClassificationTest" --tests "com.gallr.shared.data.network.EditorApiClientSortTest" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL with 6 + 4 = 10 tests passing.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonTest/kotlin/com/gallr/shared/data/model/EditorClassificationTest.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/network/EditorApiClientSortTest.kt
git commit -m "test(editor): cover isCurrentlyActive + promoteHouseEditor (spec 041)

6 tests for the active/past classification predicate covering:
open-ended window, future activeTo, today activeTo (inclusive),
past activeTo, inactive flag override, future activeFrom.

4 tests for the house-editor promotion pure function covering:
empty list, list without house, house at end, house in middle.
Confirms order of non-house editors is preserved on promotion."
```

---

## Task 10: `EditorBanner` move + rename + `exhibitionCount` param

**Files:**
- Rename: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt` → `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt`

The banner used to live under `ui/components/` (alongside `EventListBanner`, `GallrEmptyState`). It moves to `ui/editor/` since it's only used by the new editor screens. Also gains an optional `exhibitionCount` parameter.

- [ ] **Step 1: git mv + content update**

```bash
cd /Users/hanshin/Documents/Projects/gallr
mkdir -p composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor
git mv composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt \
       composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt
```

Then replace the file contents with:

```kotlin
package com.gallr.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor

/**
 * Editor banner shown above the exhibition list on the editor detail page.
 * Left-border accent layout (spec 040/041): solid 3 dp onSurface bar,
 * monospace small-caps label ("GUEST EDITOR" or "HOUSE EDITOR"), editor
 * name in titleLarge, bilingual title, italic bio. Optional meta line
 * below the bio shows the active window + exhibition count.
 */
@Composable
fun EditorBanner(
    editor: Editor,
    lang: AppLanguage,
    exhibitionCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val label = when {
        editor.isHouseEditor -> if (lang == AppLanguage.KO) "하우스 에디터" else "HOUSE EDITOR"
        else -> if (lang == AppLanguage.KO) "게스트 에디터" else "GUEST EDITOR"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
            .background(MaterialTheme.colorScheme.surface)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
        Column(modifier = Modifier.padding(GallrSpacing.md)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = editor.localizedTitle(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedBio(lang),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = GallrSpacing.sm),
            )
            if (exhibitionCount > 0) {
                Text(
                    text = if (lang == AppLanguage.KO) "전시 ${exhibitionCount}개" else "$exhibitionCount exhibitions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GallrSpacing.sm),
                )
            }
        }
    }
}
```

Two changes vs the v1.5 `GuestEditorBanner`:
- Package moves from `com.gallr.app.ui.components` to `com.gallr.app.ui.editor`.
- Composable renames from `GuestEditorBanner` to `EditorBanner`.
- New parameter `exhibitionCount: Int = 0`. When > 0, a small "N exhibitions" line is appended.
- The label switches between "HOUSE EDITOR" and "GUEST EDITOR" based on `editor.isHouseEditor`.

- [ ] **Step 2: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: BUILD FAILED — `ListScreen.kt` still imports `com.gallr.app.ui.components.GuestEditorBanner`. Task 13 fixes that import. Confirm the failure is only the `GuestEditorBanner` unresolved reference plus the other previously-known composeApp issues.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorBanner.kt
git commit -m "feat(ui): move + rename GuestEditorBanner → EditorBanner (spec 041)

File moves from ui/components/ to ui/editor/ (next to the new
selector and detail screens). Composable renames.

Adds optional exhibitionCount parameter — when > 0, renders a small
'N exhibitions' / '전시 N개' line below the bio.

Label switches between 'HOUSE EDITOR' / '하우스 에디터' and
'GUEST EDITOR' / '게스트 에디터' based on editor.isHouseEditor.

ListScreen import still broken — Task 13 fixes."
```

---

## Task 11: New UI primitives — `EditorTile` + `EditorTopBar`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTile.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt`

Two small composables. Both single-responsibility.

- [ ] **Step 1: Write `EditorTopBar.kt`**

Create the file with:

```kotlin
package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing

/**
 * Shared top bar for editor screens (selector + detail).
 * Back arrow on the left, small uppercase label following it.
 */
@Composable
fun EditorTopBar(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.sm, vertical = GallrSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(GallrSpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}
```

Note: `GallrSpacing.xs` may not exist in the existing tokens. If the compile fails on it, substitute `4.dp` directly.

- [ ] **Step 2: Write `EditorTile.kt`**

Create the file with:

```kotlin
package com.gallr.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor

/**
 * Single tile in the EditorSelectorScreen.
 *
 * Layout: optional left-border accent (house editor only) + Column with
 * uppercase small label (HOUSE EDITOR / GUEST · NOW / month-year for past),
 * editor name, title, and exhibition count.
 */
@Composable
fun EditorTile(
    editor: Editor,
    lang: AppLanguage,
    exhibitionCount: Int,
    isPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelText = when {
        editor.isHouseEditor -> if (lang == AppLanguage.KO) "하우스 에디터" else "HOUSE EDITOR"
        isPast -> formatPastLabel(editor.activeTo ?: editor.activeFrom, lang)
        else -> if (lang == AppLanguage.KO) "게스트 · 현재" else "GUEST · NOW"
    }
    val nameColor = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.md)
            .height(IntrinsicSize.Min),
    ) {
        if (editor.isHouseEditor) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
        Column(
            modifier = Modifier
                .padding(start = if (editor.isHouseEditor) GallrSpacing.md else 0.dp)
                .weight(1f),
        ) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editor.localizedName(lang),
                style = MaterialTheme.typography.titleLarge,
                color = nameColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = editor.localizedTitle(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (exhibitionCount > 0) {
                Text(
                    text = if (lang == AppLanguage.KO) "전시 ${exhibitionCount}개" else "$exhibitionCount exhibitions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GallrSpacing.sm),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

private fun formatPastLabel(date: kotlinx.datetime.LocalDate, lang: AppLanguage): String {
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return if (lang == AppLanguage.KO) {
        "${date.year}.${date.monthNumber.toString().padStart(2, '0')}"
    } else {
        "${months[date.monthNumber - 1]} ${date.year}"
    }
}
```

The `Row { … }.weight(1f)` requires the Row to be a `RowScope`. Inside a Row's content lambda, `.weight()` is available on Column via `RowScope.weight`. That should work directly.

If `Modifier.weight(1f)` triggers an unresolved reference (RowScope context issue), wrap the Column in `RowScope` via the standard pattern.

- [ ] **Step 3: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: shared module compiles. composeApp still fails because of pre-existing issues. Confirm the new files themselves compile cleanly.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTile.kt
git commit -m "feat(ui): EditorTopBar + EditorTile composables (spec 041)

EditorTopBar — shared back-arrow + label header for selector and
detail screens.

EditorTile — single row in the selector. House-editor tile has
3 dp left-border accent; guest tiles are flat. Labels switch based
on isHouseEditor / isPast: 'HOUSE EDITOR', 'GUEST · NOW', or
month-year for past editors.

Exhibition count rendered as 'N exhibitions' / '전시 N개' when > 0."
```

---

## Task 12: `EditorSelectorViewModel` + `EditorDetailViewModel`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorSelectorViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorDetailViewModel.kt`

Both ViewModels follow the existing `EventDetailViewModel` pattern — `viewModelFactory` companion-object factory.

- [ ] **Step 1: Write `EditorSelectorViewModel.kt`**

Create the file with:

```kotlin
package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EditorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** All-editors state for the selector screen. */
sealed class EditorSelectorState {
    data object Loading : EditorSelectorState()
    data class Success(
        val active: List<Editor>,
        val past: List<Editor>,
        val exhibitionCounts: Map<String, Int>,
    ) : EditorSelectorState()
    data class Error(val message: String) : EditorSelectorState()
}

class EditorSelectorViewModel(
    private val editorRepository: EditorRepository,
    private val tabsViewModel: TabsViewModel,
) : ViewModel() {

    private val _editorsResult = MutableStateFlow<Result<List<Editor>>?>(null)

    /** Combined state: editors split + per-editor exhibition counts. */
    val state: StateFlow<EditorSelectorState> = combine(
        _editorsResult,
        tabsViewModel.filteredExhibitions,
    ) { editorsResult, _ ->
        when {
            editorsResult == null -> EditorSelectorState.Loading
            editorsResult.isFailure -> EditorSelectorState.Error(
                editorsResult.exceptionOrNull()?.message ?: "Unknown error"
            )
            else -> {
                val editors = editorsResult.getOrThrow()
                val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                val (active, past) = editors.partition { it.isCurrentlyActive(today) }
                val allExhibitions = (tabsViewModel.filteredExhibitions.value
                    as? ExhibitionListState.Success)?.exhibitions ?: emptyList()
                val counts = editors.associate { editor ->
                    editor.id to allExhibitions.count { it.editorId == editor.id }
                }
                EditorSelectorState.Success(active = active, past = past, exhibitionCounts = counts)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSelectorState.Loading)

    val language: StateFlow<AppLanguage> = tabsViewModel.language

    init {
        loadEditors()
    }

    fun loadEditors() {
        viewModelScope.launch {
            _editorsResult.value = editorRepository.getAllEditors()
        }
    }

    companion object {
        fun factory(
            editorRepository: EditorRepository,
            tabsViewModel: TabsViewModel,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorSelectorViewModel(editorRepository, tabsViewModel)
            }
        }
    }
}
```

A couple of design notes encoded in this code:
- The ViewModel takes a reference to `TabsViewModel` to read the already-loaded exhibitions flow (saves a duplicate DB call). The same pattern is used by `EventDetailViewModel` indirectly through repository sharing.
- Per-editor exhibition counts are computed client-side by counting `exhibition.editorId == editor.id` matches in the loaded list.
- `tabsViewModel.filteredExhibitions` is used as the join source — it contains ALL exhibitions (filtered only by closing-date >= today and other ListScreen filters; for the count we ideally want the unfiltered list, but `filteredExhibitions` is what's exposed). If this proves to under-count later, swap to a direct accessor. For now: matches the existing API surface.

- [ ] **Step 2: Write `EditorDetailViewModel.kt`**

Create the file with:

```kotlin
package com.gallr.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.EditorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EditorDetailViewModel(
    private val editorId: String,
    private val editorRepository: EditorRepository,
    private val tabsViewModel: TabsViewModel,
) : ViewModel() {

    private val _editor = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = _editor

    val exhibitions: StateFlow<List<Exhibition>> = tabsViewModel.filteredExhibitions
        .map { state ->
            (state as? ExhibitionListState.Success)?.exhibitions
                ?.filter { it.editorId == editorId }
                ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val language: StateFlow<AppLanguage> = tabsViewModel.language

    init {
        loadEditor()
    }

    fun loadEditor() {
        viewModelScope.launch {
            editorRepository.getEditorById(editorId)
                .onSuccess { _editor.value = it }
                .onFailure {
                    println("ERROR [EditorDetailViewModel] loadEditor($editorId): ${it.message}")
                    _editor.value = null
                }
        }
    }

    companion object {
        fun factory(
            editorId: String,
            editorRepository: EditorRepository,
            tabsViewModel: TabsViewModel,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditorDetailViewModel(editorId, editorRepository, tabsViewModel)
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -20
```

Expected: failure still at `App.kt`, `MainActivity.kt`, `MainViewController.kt`, and `ListScreen.kt` (pre-existing). The new ViewModels themselves should compile. If a typo prevents that, fix it.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorSelectorViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/EditorDetailViewModel.kt
git commit -m "feat(viewmodel): EditorSelectorViewModel + EditorDetailViewModel (spec 041)

Selector loads all editors via getAllEditors(), splits into active/past
by isCurrentlyActive(today in device TZ), computes per-editor exhibition
counts client-side by joining against tabsViewModel.filteredExhibitions.

Detail loads a single editor by id, exposes the filtered exhibition list
as a derived StateFlow. Silent fail on fetch error (UI shows banner-less
empty state).

Both ViewModels take a TabsViewModel reference to share the already-loaded
exhibition flow without a duplicate DB call."
```

---

## Task 13: `EditorSelectorScreen` + `EditorDetailScreen`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt`

- [ ] **Step 1: Write `EditorSelectorScreen.kt`**

Create the file:

```kotlin
package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.ui.theme.GallrSpacing
import com.gallr.app.viewmodel.EditorSelectorState
import com.gallr.app.viewmodel.EditorSelectorViewModel
import com.gallr.shared.data.model.AppLanguage

@Composable
fun EditorSelectorScreen(
    viewModel: EditorSelectorViewModel,
    onBack: () -> Unit,
    onEditorTap: (editorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val lang by viewModel.language.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(
            label = if (lang == AppLanguage.KO) "에디터" else "Editors",
            onBack = onBack,
        )
        when (val s = state) {
            is EditorSelectorState.Loading -> Unit
            is EditorSelectorState.Error -> {
                GallrEmptyState(
                    message = if (lang == AppLanguage.KO) "에디터를 불러오지 못했습니다."
                              else "Could not load editors.",
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadEditors() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is EditorSelectorState.Success -> {
                LazyColumn {
                    item {
                        Text(
                            text = if (lang == AppLanguage.KO) "현재 큐레이션" else "Currently curating",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = GallrSpacing.screenMargin,
                                top = GallrSpacing.lg,
                                bottom = GallrSpacing.sm,
                            ),
                        )
                    }
                    items(s.active, key = { it.id }) { editor ->
                        EditorTile(
                            editor = editor,
                            lang = lang,
                            exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                            isPast = false,
                            onClick = { onEditorTap(editor.id) },
                        )
                    }
                    if (s.past.isNotEmpty()) {
                        item {
                            Text(
                                text = if (lang == AppLanguage.KO) "지난 에디터" else "Past editors",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = GallrSpacing.screenMargin,
                                    top = GallrSpacing.lg,
                                    bottom = GallrSpacing.sm,
                                ),
                            )
                        }
                        items(s.past, key = { it.id }) { editor ->
                            EditorTile(
                                editor = editor,
                                lang = lang,
                                exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                                isPast = true,
                                onClick = { onEditorTap(editor.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
```

`GallrSpacing.lg` should already exist; if not, substitute `24.dp` or whatever the existing token uses.

- [ ] **Step 2: Write `EditorDetailScreen.kt`**

Create the file:

```kotlin
package com.gallr.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gallr.app.ui.components.ExhibitionCard
import com.gallr.app.ui.components.GallrEmptyState
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

@Composable
fun EditorDetailScreen(
    viewModel: EditorDetailViewModel,
    bookmarkedIds: Set<String>,
    onToggleBookmark: (String) -> Unit,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor by viewModel.editor.collectAsState()
    val exhibitions by viewModel.exhibitions.collectAsState()
    val lang by viewModel.language.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(
            label = if (lang == AppLanguage.KO) "에디터" else "Editor",
            onBack = onBack,
        )
        editor?.let { ed ->
            EditorBanner(
                editor = ed,
                lang = lang,
                exhibitionCount = exhibitions.size,
            )
        }
        if (exhibitions.isEmpty()) {
            GallrEmptyState(
                message = if (lang == AppLanguage.KO) "선택된 전시가 없습니다"
                          else "No exhibitions in this list",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn {
                items(exhibitions, key = { it.id }) { exhibition ->
                    ExhibitionCard(
                        exhibition = exhibition,
                        lang = lang,
                        isBookmarked = exhibition.id in bookmarkedIds,
                        onBookmarkToggle = { onToggleBookmark(exhibition.id) },
                        onClick = { onExhibitionTap(exhibition) },
                    )
                }
            }
        }
    }
}
```

The `ExhibitionCard` signature above is approximate — the implementer should check the actual signature at `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt` and adjust the call. Bookmark + tap callbacks may have different parameter shapes; use the same shape `ListScreen.kt` does.

- [ ] **Step 3: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -20
```

Expected: failure still at `App.kt`, `MainActivity.kt`, `MainViewController.kt`, and `ListScreen.kt`. The new screens compile cleanly.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt
git commit -m "feat(ui): EditorSelectorScreen + EditorDetailScreen (spec 041)

Selector: top bar + LazyColumn with two sections (Currently curating,
Past editors). Past section is hidden when empty. Uses EditorTile for
each row. Error state has a retry action.

Detail: top bar + EditorBanner + LazyColumn of ExhibitionCard. Empty
state when no exhibitions are tagged.

Both screens are pure Compose, no navigation knowledge — they take
onBack/onEditorTap/onExhibitionTap callbacks and let App.kt orchestrate."
```

---

## Task 14: `TabsViewModel` cleanup + `ListScreen.kt` rewire + `App.kt` nav + DI

This task is intentionally large — these four files are tightly coupled (factory signature in `TabsViewModel` cascades to `App.kt`'s `viewModel(factory = ...)` call which cascades to `MainActivity.kt` and `MainViewController.kt`). Doing them in separate commits would leave the build broken across more intermediate states. One commit, one task.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`

- [ ] **Step 1: Clean up `TabsViewModel.kt`**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`. Make these specific edits:

(a) Remove the imports `com.gallr.shared.data.model.GuestEditor` and `com.gallr.shared.repository.GuestEditorRepository`.

(b) Add the import `com.gallr.shared.repository.EditorRepository`.

(c) In the constructor, remove the line `private val guestEditorRepository: GuestEditorRepository,`. Add `private val editorRepository: EditorRepository,` in the same position.

(d) Remove these blocks entirely:
- The `// ── Active guest editor (spec 040) ───────────` section (lines ~111-132 in v1.5.1, including `_activeGuestEditor`, `activeGuestEditor`, and `loadActiveGuestEditor()`).
- The `toggleGuestPick()` function (lines ~152-165).
- The `updateNonGuestFilter()` function (lines ~172-174).
- In `init { ... }`, the call `loadActiveGuestEditor()` and the entire `_activeGuestEditor.collect { ... }` block.

(e) In `setCity()`, change `copy(regions = emptyList(), showGuestPick = false)` back to `copy(regions = emptyList())`.

(f) In `toggleRegion()`, both `copy()` calls drop the `, showGuestPick = false` token.

(g) In `clearAllFilters()`, change:
```kotlin
        _filterState.value = FilterState(activeGuestEditorId = _filterState.value.activeGuestEditorId)
```
back to:
```kotlin
        _filterState.value = FilterState()
```

(h) Update the `companion object factory(...)` signature: replace the `guestEditorRepository: GuestEditorRepository` parameter with `editorRepository: EditorRepository`. Inside the `initializer { … }` block, replace `guestEditorRepository` with `editorRepository`.

(i) In `refresh()`, remove the call to `loadActiveGuestEditor()`.

- [ ] **Step 2: Clean up `ListScreen.kt`**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`. Edits:

(a) Add a parameter `onEditorsChipTap: () -> Unit` to the `ListScreen(...)` function signature. Place it next to the existing `onExhibitionTap` and `onEventTap` callbacks.

(b) Remove the import `com.gallr.app.ui.components.GuestEditorBanner`.

(c) Remove the imports `androidx.compose.animation.fadeIn`, `androidx.compose.animation.fadeOut`, `androidx.compose.animation.core.tween` (they were added in v1.5 for the banner animation, no longer needed).

(d) Remove the line `val activeGuestEditor by viewModel.activeGuestEditor.collectAsState()`.

(e) In the filter chip Row block (lines ~290-345 in v1.5.1):

  - Remove the entire leftmost `activeGuestEditor?.let { editor -> GallrFilterChip(…) Spacer(…) }` block.
  - In each of the 5 remaining chip onClicks (event, featured, editor's-picks-which-will-be-removed, opening-this-week, closing-this-week), change `viewModel.updateNonGuestFilter { copy(...) }` back to `viewModel.updateFilter { copy(...) }`.
  - Remove the entire `GallrFilterChip(selected = filter.showEditorsPick, ...)` chip block (the one with label "에디터 픽" / "EDITOR'S PICKS" and its trailing `Spacer`).
  - Insert a new chip in the same position where the "Editor's Picks" chip used to be — or as a peer after Featured. The new chip:

```kotlin
            GallrFilterChip(
                selected = false,
                onClick = onEditorsChipTap,
                label = if (lang == AppLanguage.KO) "에디터 ›" else "EDITORS ›",
            )
            Spacer(Modifier.width(GallrSpacing.sm))
```

(f) Remove the entire `// ── Guest editor banner (spec 040) ──` block including the `AnimatedVisibility` and its body (lines ~347-353 in v1.5.1).

(g) In the empty-state branch of the LazyColumn (when filtered list is empty), remove the `filter.showGuestPick && activeGuestEditor != null ->` branch from the `when` expression. The other branches stay.

- [ ] **Step 3: Update `App.kt`**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`. Edits:

(a) Remove the import `com.gallr.shared.repository.GuestEditorRepository`. Add `com.gallr.shared.repository.EditorRepository`.

(b) Rename the `App(...)` composable parameter `guestEditorRepository: GuestEditorRepository` to `editorRepository: EditorRepository`. (Both the parameter declaration and the docstring if any.)

(c) Find the `TabsViewModel.factory(...)` call (around line 160). Replace the `guestEditorRepository` argument with `editorRepository`.

(d) Inside the `App(...)` composable, add two new `remember { mutableStateOf(...) }` declarations near the existing `selectedTab`, `selectedExhibition`, `selectedEventId`:

```kotlin
        var editorSelectorOpen by remember { mutableStateOf(false) }
        var selectedEditorId by remember { mutableStateOf<String?>(null) }
```

(e) Find the existing `AnimatedContent` block (line ~236 in current `develop`). It currently switches on `selectedExhibition to selectedEventId`. Update its `targetState` to include the new flags:

The cleanest approach: extend the existing `when` inside the AnimatedContent's content lambda with two new cases, in priority order. Locate the `when { … }` inside the `AnimatedContent` body and add these branches BEFORE the `else -> { Scaffold(...) }` branch:

```kotlin
                selectedEditorId != null -> {
                    PlatformBackHandler { selectedEditorId = null }
                    val editorDetailVm: EditorDetailViewModel = viewModel(
                        key = "editor-$selectedEditorId",
                        factory = EditorDetailViewModel.factory(
                            editorId = selectedEditorId!!,
                            editorRepository = editorRepository,
                            tabsViewModel = viewModel,
                        ),
                    )
                    EditorDetailScreen(
                        viewModel = editorDetailVm,
                        bookmarkedIds = bookmarkedIds,
                        onToggleBookmark = { viewModel.toggleBookmark(it) },
                        onBack = { selectedEditorId = null },
                        onExhibitionTap = { selectedExhibition = it },
                    )
                }
                editorSelectorOpen -> {
                    PlatformBackHandler { editorSelectorOpen = false }
                    val selectorVm: EditorSelectorViewModel = viewModel(
                        key = "editor-selector",
                        factory = EditorSelectorViewModel.factory(
                            editorRepository = editorRepository,
                            tabsViewModel = viewModel,
                        ),
                    )
                    EditorSelectorScreen(
                        viewModel = selectorVm,
                        onBack = { editorSelectorOpen = false },
                        onEditorTap = { selectedEditorId = it },
                    )
                }
```

The `AnimatedContent.targetState` Triple needs to expand. Change:
```kotlin
        AnimatedContent(
            targetState = selectedExhibition to selectedEventId,
```
to:
```kotlin
        AnimatedContent(
            targetState = listOf(selectedExhibition, selectedEventId, selectedEditorId, editorSelectorOpen),
```
and update the destructuring inside the content lambda accordingly. (Or use a `data class` to hold the four-tuple if the implementer prefers; the `listOf` approach is simpler and adequate.)

(f) Locate the `ListScreen(...)` call inside the tab content `AnimatedContent`. Add the new parameter:

```kotlin
                            1 -> ListScreen(
                                viewModel = viewModel,
                                onExhibitionTap = { selectedExhibition = it },
                                onEventTap = { id -> selectedEventId = id },
                                onEditorsChipTap = { editorSelectorOpen = true },
                                modifier = Modifier.padding(innerPadding),
                            )
```

(g) Add the new editor-screen imports at the top of the file:

```kotlin
import com.gallr.app.ui.editor.EditorDetailScreen
import com.gallr.app.ui.editor.EditorSelectorScreen
import com.gallr.app.viewmodel.EditorDetailViewModel
import com.gallr.app.viewmodel.EditorSelectorViewModel
```

- [ ] **Step 4: Update `MainActivity.kt` (Android)**

Open `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`. Edits:

(a) Replace the import `com.gallr.shared.data.network.GuestEditorApiClient` with `com.gallr.shared.data.network.EditorApiClient`. Replace `com.gallr.shared.repository.GuestEditorRepository` with `com.gallr.shared.repository.EditorRepository`. Replace `com.gallr.shared.repository.GuestEditorRepositoryImpl` with `com.gallr.shared.repository.EditorRepositoryImpl`.

(b) Find the block that constructs `guestEditorRepository`:

```kotlin
        val guestEditorRepository: GuestEditorRepository = GuestEditorRepositoryImpl(
            GuestEditorApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
```

Replace with:

```kotlin
        val editorRepository: EditorRepository = EditorRepositoryImpl(
            EditorApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
```

(c) Find the `App(...)` call inside `setContent { … }`. Replace the `guestEditorRepository = guestEditorRepository,` argument with `editorRepository = editorRepository,`.

- [ ] **Step 5: Update `MainViewController.kt` (iOS)**

Open `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`. Apply the same rename as Step 4 — import swaps, variable rename, App() call argument rename.

- [ ] **Step 6: Compile + run all tests**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. The entire app now compiles. If anything fails, fix before continuing.

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. All tests pass.

- [ ] **Step 7: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/App.kt \
        composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt \
        composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
git commit -m "feat(ui): wire Editor hub into the app — chip portal + nav stack (spec 041)

TabsViewModel sheds all guest editor state: activeGuestEditor flow,
loadActiveGuestEditor, toggleGuestPick, updateNonGuestFilter, the
defensive _activeGuestEditor.collect collector, and the constructor
parameter. The factory loses one parameter; setCity/toggleRegion/
clearAllFilters drop the showGuestPick clearing.

ListScreen replaces the conditional guest chip + Editor's Picks chip
with a single 'Editors ›' / '에디터 ›' portal chip that calls
onEditorsChipTap. Removes the AnimatedVisibility banner and the
filter.showGuestPick empty-state branch. All 5 remaining chip
onClicks revert from updateNonGuestFilter to updateFilter.

App.kt adds two state flags (editorSelectorOpen, selectedEditorId)
plus two AnimatedContent branches for the selector and detail
screens. Back from detail → selector; back from selector → tab
content. Standard nav stack semantics.

Android + iOS DI wiring: GuestEditorApiClient/Repository renames
to Editor* and the App() call argument follows."
```

---

## Task 15: Apps Script README update + sheet workflow note

**Files:**
- Modify: `gas/README.md`

The admin sheet workflow changes: `is_editors_pick` column goes away, `guest_editor_id` renames to `editor_id`, previously-flagged rows need `gallr-editors` filled in.

- [ ] **Step 1: Update `gas/README.md`**

Open `/Users/hanshin/Documents/Projects/gallr/gas/README.md`. Find the section that documents the sheet column layout (it should list all the columns the sync expects).

Apply these changes:

(a) Replace the `is_editors_pick` row in the column-layout table with… nothing (delete the row). The column is no longer synced.

(b) Replace the `guest_editor_id` row with an `editor_id` row. Update the description to: "Optional. Slug pointing at editors.id. Use 'gallr-editors' for team-curated picks (previously is_editors_pick=true). Use a specific editor slug for guest curators."

(c) Add a new section titled `## Migration from v1.5.x to v1.6 (spec 041)` near the bottom of the file:

```markdown
## Migration from v1.5.x to v1.6 (spec 041)

The unified editor model replaces two legacy columns. Before applying
migration `017_unify_editors.sql`, prepare the gallr exhibition sheet:

1. **Rename `guest_editor_id` → `editor_id`** by editing the header row.
   Data is unchanged; existing slugs work as-is.
2. **Delete the `is_editors_pick` column** entirely.
3. **Bulk-fill `gallr-editors`** into `editor_id` for previously-flagged rows.
   Quick formula tip — paste into an unused column to compute the new
   `editor_id` from the legacy state, then paste-values back into `editor_id`:

   ```
   =ARRAYFORMULA(IF(I2:I = TRUE, "gallr-editors", J2:J))
   ```

   where `I` is the legacy `is_editors_pick` column and `J` is the legacy
   `guest_editor_id` column. Output is the new `editor_id` value.

After the sheet is updated, apply the SQL migration and trigger a sync.
Rows whose `editor_id` references an unknown editor are skipped with a
clear log message — insert the editor row in Supabase Studio first if
that happens.
```

- [ ] **Step 2: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add gas/README.md
git commit -m "docs(sync): admin migration tip for v1.5.x → v1.6 editor unification (spec 041)

Removes the is_editors_pick row, replaces guest_editor_id with editor_id,
documents the one-time sheet migration with an ARRAYFORMULA tip for
bulk-replacing TRUE flags with the 'gallr-editors' slug."
```

---

## Task 16: Release bookkeeping — VERSION, CHANGELOG, TODOS, Android/iOS version files

**Files:**
- Modify: `VERSION`
- Modify: `composeApp/build.gradle.kts`
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj`
- Modify: `CHANGELOG.md`
- Modify: `TODOS.md`

Release type: MINOR (new feature with breaking schema change). v1.5.1 → **v1.6.0**.

- [ ] **Step 1: Bump VERSION**

Replace the contents of `VERSION` with:

```
1.6.0
```

(Just the version string, no trailing whitespace or markdown.)

- [ ] **Step 2: Bump Android version**

Open `composeApp/build.gradle.kts`. Locate:

```kotlin
        versionCode = 10
        versionName = "1.5.1"
```

Change to:

```kotlin
        versionCode = 11
        versionName = "1.6.0"
```

- [ ] **Step 3: Bump iOS version**

Open `iosApp/iosApp.xcodeproj/project.pbxproj`. There are two occurrences each of `CURRENT_PROJECT_VERSION = 8;` and `MARKETING_VERSION = 1.5.1;` (Debug and Release configurations). Update both:

- `CURRENT_PROJECT_VERSION = 8;` → `CURRENT_PROJECT_VERSION = 9;` (×2)
- `MARKETING_VERSION = 1.5.1;` → `MARKETING_VERSION = 1.6.0;` (×2)

Use `Edit` with `replace_all=true` for each.

- [ ] **Step 4: Update CHANGELOG.md**

Open `CHANGELOG.md`. Insert a new release section above the existing `## [1.5.1]` block, right after the header:

```markdown
## [1.6.0] - 2026-05-12

### Added
- **Editor hub.** A single "Editors" filter chip replaces both "Editor's Picks" and "[Name]'s Picks". Tapping it opens a tile selector showing the gallr team's house picks alongside every active and past guest editor. Tapping a tile lands on a dedicated editor detail page with the editor's banner and their curated exhibitions.
- **Past editors browsable.** Inactive editors and their curated exhibitions are preserved in the new "Past editors" section of the selector — every editor's contribution stays attributable over time.
- **Multiple simultaneous active guest editors.** The single-banner constraint is gone; the selector cleanly shows multiple active editors as peers in the "Currently curating" section.

### Changed
- The `Editor's Picks` and `[Name]'s Picks` filter chips no longer exist. Both concepts collapse into the unified `Editors ›` portal chip.
- Editor banners no longer appear inline on the List tab. Banners now live on the dedicated editor detail page.

### Infrastructure
- New `editors` table (renamed from `guest_editors`), with a hardcoded `gallr-editors` seed row representing the gallr team's house identity.
- New `exhibitions.editor_id` foreign key column replaces both `is_editors_pick` (Boolean) and `guest_editor_id` (FK). Migration `017_unify_editors.sql` performs the rename, seeds the house editor, backfills existing flagged exhibitions, and drops the legacy columns.
- Apps Script sync: `KNOWN_COLUMNS` updated; FK validation renames from `guest_editor_id` to `editor_id`; the `is_editors_pick` Boolean branch is removed.
- Admin sheet workflow change: previously `is_editors_pick = TRUE` rows now type `gallr-editors` into the new `editor_id` column. See `gas/README.md` for the bulk-replace ARRAYFORMULA tip.

```

- [ ] **Step 5: Update TODOS.md**

Open `TODOS.md`. Update the date header (line 3) to:

```
Last updated: 2026-05-12 (no items completed in v1.6.0 — Editor hub feature)
```

- [ ] **Step 6: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add VERSION CHANGELOG.md TODOS.md composeApp/build.gradle.kts iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "chore(release): bump to 1.6.0 across Android, iOS, VERSION, CHANGELOG

Editor hub ships in this release (spec 041):
- guest_editors renamed to editors; gallr-editors seed row inserted
- exhibitions.editor_id FK replaces is_editors_pick + guest_editor_id
- Apps Script sync follows
- Editors chip becomes a navigation portal
- EditorSelectorScreen + EditorDetailScreen
- Past editors archive preserved + browsable
- Multiple simultaneous active editors supported

Android: versionCode 10 → 11, versionName 1.5.1 → 1.6.0
iOS: CURRENT_PROJECT_VERSION 8 → 9, MARKETING_VERSION 1.5.1 → 1.6.0
TODOS.md: no items completed in this release"
```

---

## Task 17: Full-suite verification + push

**Files:** none modified — verification only.

- [ ] **Step 1: Sanity-check new symbols resolve in all the right places**

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -rn "GuestEditor" shared/src composeApp/src 2>/dev/null
grep -rn "isEditorsPick\|guest_editor_id" shared/src composeApp/src gas/ 2>/dev/null | grep -v "test/results" | grep -v build/
```

Expected: both commands produce zero output. If any matches, a rename was missed.

- [ ] **Step 2: Compile both shared and composeApp Android targets**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the shared module test suite**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. Test count is approximately 177 (180 from v1.5.1 minus 6 obsolete FilterStateTest cases plus 6 + 4 new editor tests = ~184; exact count depends on minor inclusions).

- [ ] **Step 4: Verify CHANGELOG + VERSION + Android/iOS bumps are consistent**

```bash
cd /Users/hanshin/Documents/Projects/gallr
cat VERSION
grep "versionCode\|versionName" composeApp/build.gradle.kts | head -2
grep "MARKETING_VERSION\|CURRENT_PROJECT_VERSION" iosApp/iosApp.xcodeproj/project.pbxproj | head -4
grep "^## \[1\." CHANGELOG.md | head -3
```

Expected:
- VERSION shows `1.6.0`.
- Android: `versionCode = 11`, `versionName = "1.6.0"`.
- iOS: `CURRENT_PROJECT_VERSION = 9` (×2), `MARKETING_VERSION = 1.6.0` (×2).
- CHANGELOG shows `## [1.6.0]` as the most recent entry.

- [ ] **Step 5: Push the branch**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git push -u origin 041-editor-hub 2>&1 | tail -10
```

Expected: `branch '041-editor-hub' set up to track 'origin/041-editor-hub'`. If push is rejected, STOP and report (do not force-push).

- [ ] **Step 6: Capture the commit log for the PR description**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git log --oneline origin/develop..HEAD
```

Record the output. Useful for the PR body.

- [ ] **Step 7: Document the manual smoke-test plan in the PR description**

Pull the smoke test from `specs/041-editor-hub/spec.md` (the `Manual smoke test` section). Paste it into the PR body — do not duplicate it here to avoid drift.

Stop here. PR creation is a separate step the user will trigger (via `/ship` or `gh pr create`).

---

## Spec Coverage Self-Check

- Spec "Data model → Migration `017_unify_editors.sql`": Task 1.
- Spec "Apps Script sync": Task 2 + Task 15 (admin docs).
- Spec "Exhibition + DTO field changes": Task 3.
- Spec "Editor domain model expansion (`isActive`, `activeFrom`, `activeTo`, `isCurrentlyActive`, `isHouseEditor`, `HOUSE_EDITOR_ID`)": Task 5.
- Spec "Rename GuestEditor slice → Editor": Task 4.
- Spec "EditorRepository two-method API": Task 7.
- Spec "EditorApiClient — fetchAllEditors + fetchEditorById + promoteHouseEditor": Task 8.
- Spec "EditorLocalizationTest renamed + expanded fixture": Tasks 4 + 5.
- Spec "EditorClassificationTest": Task 9.
- Spec "EditorApiClientSortTest": Task 9.
- Spec "FilterState cleanup": Task 6.
- Spec "FilterStateTest cleanup": Tasks 3 + 6 (helper in 3, test method removal in 6).
- Spec "EditorBanner move + rename + exhibitionCount param": Task 10.
- Spec "EditorTile + EditorTopBar": Task 11.
- Spec "EditorSelectorViewModel + EditorDetailViewModel": Task 12.
- Spec "EditorSelectorScreen + EditorDetailScreen": Task 13.
- Spec "TabsViewModel cleanup": Task 14 (Step 1).
- Spec "ListScreen rewire": Task 14 (Step 2).
- Spec "App.kt nav + DI": Task 14 (Steps 3-5).
- Spec "Release bookkeeping": Task 16.
- Spec "Verification + push": Task 17.

No placeholders. Function and method names are consistent across tasks: `Editor`, `EditorDto`, `EditorApiClient`, `EditorRepository`, `EditorRepositoryImpl`, `getAllEditors`, `getEditorById`, `fetchAllEditors`, `fetchEditorById`, `promoteHouseEditor`, `isCurrentlyActive`, `isHouseEditor`, `HOUSE_EDITOR_ID`, `EditorBanner`, `EditorTile`, `EditorTopBar`, `EditorSelectorScreen`, `EditorDetailScreen`, `EditorSelectorViewModel`, `EditorDetailViewModel`, `editorSelectorOpen`, `selectedEditorId`, `onEditorsChipTap`.

The plan has intentional broken-state intermediate commits at Tasks 3, 4, 7, 8 (where renames cascade across files that aren't all fixed in one commit). Each is flagged in the commit message. Task 14 restores green. If the implementer prefers always-green history they can squash at the end, but every commit is logically scoped.
