# Guest Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface a partner curator's exhibition list in gallr through a dynamic filter chip + editorial banner, with a new `guest_editors` table, an FK column on `exhibitions`, and Apps Script sync wired to handle both.

**Architecture:** New shared-module slice (DTO + domain + repository + API method) mirrors the existing `Event` slice exactly — same patterns, same wiring location. `FilterState` gains a `showGuestPick` boolean plus a passive `activeGuestEditorId` companion field; mutual exclusivity with other filters is enforced at the `TabsViewModel` layer (six existing toggle paths each get one new line). UI changes are confined to `ListScreen.kt` (add chip + animated banner block) plus one new component `GuestEditorBanner.kt`. The Apps Script sync gets three additions (KNOWN_COLUMNS entry, FK pre-validation function, `buildRecord` branch) modeled on the existing `event_id` flow. DB changes ship in one new migration `015_add_guest_editors.sql`.

**Tech Stack:** Kotlin 2.1.20 (KMP commonMain + commonTest), Compose Multiplatform 1.8.0, Material3, Ktor 2.9+, kotlinx-serialization, kotlinx-datetime, Supabase Postgres + PostgREST, Google Apps Script V8. Gradle tasks: `:shared:testDebugUnitTest` (fast JVM tests), `:shared:allTests` (all platforms — note: pre-existing iOS compile issue in `CityRegionFilterTest.kt` flagged in spec under "Verification" — does not block this branch).

**Spec:** `specs/040-guest-editor/spec.md`
**Branch:** `040-guest-editor` (already created off `develop`, spec already committed)

---

## File Structure

**New files**
- `supabase/migrations/015_add_guest_editors.sql` — creates `guest_editors`, RLS read policy, indexes, adds `guest_editor_id` column on `exhibitions`.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt` — domain model with `localized*` helpers.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt` — `@Serializable` DTO with `toDomain()`.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt` — thin Ktor client for one query.
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt` — interface.
- `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt` — `runCatching` wrapper.
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt` — localized-* helpers.
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt` — composable.

**Modified files**
- `gas/SyncExhibitions.gs` — add `guest_editor_id` to `KNOWN_COLUMNS`; new `fetchKnownGuestEditorIds()`; FK pre-validation step; FK column branch in `buildRecord`.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt` — add `guestEditorId: String? = null` field.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt` — add `guest_editor_id` mapping + pass-through in `toDomain()`.
- `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt` — add `showGuestPick: Boolean = false`, `activeGuestEditorId: String? = null`, `guestPickMatch` clause in `matches()`.
- `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt` — add guest-pick test cases.
- `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` — new `activeGuestEditor` state, `loadActiveGuestEditor()`, `toggleGuestPick()`, mutual-exclusivity rule applied to six existing toggle paths, hook into `refresh()`.
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt` — chip insertion as leftmost item, animated banner block, empty-state copy.
- `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` — DI wiring for `GuestEditorRepository`.
- `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` — DI wiring for `GuestEditorRepository`.

**Order rationale:** the plan goes bottom-up (DB → sync → shared model → DTO → API → repo → FilterState → UI component → ViewModel → ListScreen → DI wiring) so each step has tested dependencies in place. Tests live alongside the layer they cover.

---

## Task 1: Database migration

**Files:**
- Create: `supabase/migrations/015_add_guest_editors.sql`

- [ ] **Step 1: Create the migration file**

Write this SQL to `supabase/migrations/015_add_guest_editors.sql`:

```sql
-- Migration 015 — Guest Editor feature
-- Adds the guest_editors table (admin-managed via Supabase Studio) and
-- a nullable guest_editor_id FK column on exhibitions.

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

-- No insert/update policies — admin writes via the service role through
-- Supabase Studio. Without service-role auth, writes fail by default.

create index guest_editors_active_idx
  on guest_editors (is_active, active_from desc);

alter table exhibitions
  add column guest_editor_id text references guest_editors(id) on delete set null;

create index exhibitions_guest_editor_idx
  on exhibitions (guest_editor_id) where guest_editor_id is not null;
```

- [ ] **Step 2: Verify the migration is well-formed**

Locally (no DB connection needed):

```bash
cd /Users/hanshin/Documents/Projects/gallr
ls -la supabase/migrations/015_add_guest_editors.sql
```

Expected: file exists, non-empty. We are not running the migration in this step — it will be applied to Supabase out-of-band by the admin via Supabase Studio's migration runner or the Supabase CLI before the app code that queries the new table reaches a tester. Document this in the commit.

- [ ] **Step 3: Commit the migration**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add supabase/migrations/015_add_guest_editors.sql
git commit -m "feat(db): add guest_editors table + exhibitions FK (spec 040)

Migration creates guest_editors with bilingual name/title/bio, is_active
flag and active_from/active_to dates. Admin populates via Supabase Studio
(no insert policy — service-role only). Adds guest_editor_id FK column
on exhibitions, nullable, set-null on parent delete.

Apply via Supabase Studio or CLI before deploying app changes that read
the new table."
```

---

## Task 2: Apps Script sync — KNOWN_COLUMNS entry

**Files:**
- Modify: `gas/SyncExhibitions.gs` (the `KNOWN_COLUMNS` array around lines 287-305)

This task is intentionally small and isolated. Without this entry, the next 5-minute sync wipes `guest_editor_id` to NULL on every row. The remaining sync changes (FK validation + buildRecord branch) come in Task 3.

- [ ] **Step 1: Add `'guest_editor_id'` to `KNOWN_COLUMNS`**

Open `gas/SyncExhibitions.gs`. Locate the `KNOWN_COLUMNS` array (currently lines 287-305). Find the line `'event_id',` (the last entry) and add `'guest_editor_id',` immediately after it. The array becomes:

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
  'is_featured', 'is_editors_pick',
  'latitude', 'longitude',
  'cover_image_url',
  'hours',
  'contact',
  'reception_date',
  'opening_time',
  'event_id',
  'guest_editor_id',
];
```

- [ ] **Step 2: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add gas/SyncExhibitions.gs
git commit -m "feat(sync): allow guest_editor_id column through KNOWN_COLUMNS (spec 040)

Add 'guest_editor_id' to the KNOWN_COLUMNS array so the 5-minute
delete-and-insert sync preserves the column. Without this entry,
every sync would wipe the column to NULL.

Per the KNOWN_COLUMNS trap noted in project memory: new exhibitions
columns must be added here or they're stripped on every sync."
```

---

## Task 3: Apps Script sync — FK validation + buildRecord branch

**Files:**
- Modify: `gas/SyncExhibitions.gs`

Mirror the existing `event_id` pattern: pre-fetch the set of valid `guest_editors.id` values once per sync run, skip rows referencing unknown editors with a clear log message, and handle the column in `buildRecord` as a nullable trimmed slug.

- [ ] **Step 1: Add `fetchKnownGuestEditorIds()` function**

In `gas/SyncExhibitions.gs`, find `fetchKnownEventIds` (currently around line 57). Immediately after that function's closing `}` (and the trailing blank line), add this new function:

```javascript
// ---------------------------------------------------------------------------
// Guest editor id validation — fetches all guest_editor ids once per sync run.
// Returns a Set-like object: knownIds[id] === true when the id exists.
// ---------------------------------------------------------------------------

function fetchKnownGuestEditorIds(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/guest_editors?select=id';
  var response = UrlFetchApp.fetch(url, {
    method: 'get',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 200) {
    Logger.log('WARN: guest_editors fetch returned ' + code + ' — guest_editor_id validation disabled this run');
    return null; // null signals "validation disabled" so we don't accidentally skip every row
  }
  var rows = JSON.parse(response.getContentText());
  var set = {};
  rows.forEach(function(r) { if (r && r.id) set[r.id] = true; });
  return set;
}
```

- [ ] **Step 2: Hook `fetchKnownGuestEditorIds` into `syncToSupabase` next to `fetchKnownEventIds`**

In `syncToSupabase()` (currently around line 142), find the line:

```javascript
  var knownEventIds = fetchKnownEventIds(supabaseUrl, serviceKey);
```

Add immediately after it:

```javascript
  var knownGuestEditorIds = fetchKnownGuestEditorIds(supabaseUrl, serviceKey);
```

- [ ] **Step 3: Add FK pre-validation in the per-row loop**

Find the `event_id` pre-validation block in `syncToSupabase()` (currently around lines 157-162):

```javascript
    // Validate event_id FK if a value is present and validation is enabled
    var eventIdCell = String(getCell(row, headerMap, 'event_id') || '').trim();
    if (eventIdCell && knownEventIds !== null && !knownEventIds[eventIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': event_id "' + eventIdCell + '" not found in events table — sync events first');
      return;
    }
```

Add an identical block for `guest_editor_id` immediately after that closing `}`:

```javascript
    // Validate guest_editor_id FK if a value is present and validation is enabled
    var guestEditorIdCell = String(getCell(row, headerMap, 'guest_editor_id') || '').trim();
    if (guestEditorIdCell && knownGuestEditorIds !== null && !knownGuestEditorIds[guestEditorIdCell]) {
      skippedReasons.push('Row ' + rowNum + ': guest_editor_id "' + guestEditorIdCell + '" not found in guest_editors table — insert editor row first');
      return;
    }
```

- [ ] **Step 4: Add `guest_editor_id` branch to `buildRecord`**

In `buildRecord()` (currently around lines 311-410), find the `event_id` branch (currently lines 377-381):

```javascript
    // FK column — blank cell must become null, never empty string,
    // or Postgres rejects with FK violation 23503 (no events row has id="").
    if (header === 'event_id') {
      var eid = String(raw || '').trim();
      record[header] = eid || null;
      return;
    }
```

Add an identical branch for `guest_editor_id` immediately after the `event_id` branch's closing `}`:

```javascript
    // FK column — blank cell must become null, never empty string,
    // or Postgres rejects with FK violation 23503 (no guest_editors row has id="").
    if (header === 'guest_editor_id') {
      var gid = String(raw || '').trim();
      record[header] = gid || null;
      return;
    }
```

- [ ] **Step 5: Verify the script still parses (syntax check)**

Apps Script is not run locally; we can't execute it here. But we can do a syntax-level sanity check using `node`:

```bash
cd /Users/hanshin/Documents/Projects/gallr
node -e "require('fs').readFileSync('gas/SyncExhibitions.gs','utf8'); new Function(require('fs').readFileSync('gas/SyncExhibitions.gs','utf8')); console.log('OK: parses');"
```

Expected: `OK: parses`. Any output starting with `SyntaxError` means there's a bracket / paren mismatch from the edit — fix before continuing.

- [ ] **Step 6: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add gas/SyncExhibitions.gs
git commit -m "feat(sync): validate guest_editor_id FK during sync (spec 040)

Mirror the event_id pattern: fetch the set of known guest_editors.id
values once per sync, skip rows whose guest_editor_id slug doesn't
exist with a clear log message, and convert blank cells to null in
buildRecord so Postgres FK doesn't reject empty strings."
```

---

## Task 4: Shared module — Exhibition + DTO field

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`

Smallest possible model + DTO change. No new test file — the existing `FilterStateTest` exhibition helper has `guestEditorId` default-to-null thanks to Kotlin defaults, so the helper compiles untouched after this task.

- [ ] **Step 1: Add `guestEditorId` to `Exhibition`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`. Find the field declarations (currently lines 5-30). The last field is `val eventId: String? = null,` at line 30. Add immediately after it (before the closing `)`):

```kotlin
    val guestEditorId: String? = null,
```

The full constructor now ends:

```kotlin
    val openingTime: String? = null,
    val eventId: String? = null,
    val guestEditorId: String? = null,
) {
```

- [ ] **Step 2: Add `guest_editor_id` mapping to `ExhibitionDto`**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`. Find the field declarations (lines 12-37). The last field is `@SerialName("event_id") val eventId: String? = null,` at line 37. Add immediately after it:

```kotlin
    @SerialName("guest_editor_id") val guestEditorId: String? = null,
```

Then in `toDomain()` (lines 39-77), find the last field passed:

```kotlin
            eventId = eventId,
```

Add immediately after it (before the closing `)`):

```kotlin
            guestEditorId = guestEditorId,
```

- [ ] **Step 3: Compile the shared module**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileKotlinMetadata 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. If the build fails because some test file or other consumer references `Exhibition(...)` without `guestEditorId` and that constructor was using positional args, the failure will show the location — fix by passing `guestEditorId = null` or by leaving positional args alone (the new field has a default, so positional calls that don't pass it still work).

- [ ] **Step 4: Run the existing shared-module tests to confirm no regression**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. The existing `FilterStateTest.exhibition()` helper uses named args and Kotlin defaults — adding a new nullable defaulted field cannot break it. Other shared tests should be unaffected.

- [ ] **Step 5: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt
git commit -m "feat(model): add guest_editor_id to Exhibition + DTO (spec 040)

Nullable String FK pointing at guest_editors.id (slug). Existing
consumers pass it as null via the default; existing tests are
unaffected (all use named args)."
```

---

## Task 5: GuestEditor domain model + localization tests (TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt`
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt`

Follow the same `localized*` fallback pattern used in `Exhibition.kt` (EN falls back to KO when EN is empty, KO is unconditional).

- [ ] **Step 1: Write the failing tests**

Write `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt`:

```kotlin
package com.gallr.shared.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GuestEditorLocalizationTest {

    private fun editor(
        nameKo: String = "김민정",
        nameEn: String = "Minjung Kim",
        titleKo: String = "큐레이터",
        titleEn: String = "Curator",
        bioKo: String = "한국어 소개",
        bioEn: String = "English bio",
    ) = GuestEditor(
        id = "minjung-kim",
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
    )

    @Test
    fun `localizedName returns English when language is EN and English is set`() {
        assertEquals("Minjung Kim", editor().localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName falls back to Korean when English is empty`() {
        assertEquals("김민정", editor(nameEn = "").localizedName(AppLanguage.EN))
    }

    @Test
    fun `localizedName returns Korean when language is KO`() {
        assertEquals("김민정", editor().localizedName(AppLanguage.KO))
    }

    @Test
    fun `localizedTitle follows the same fallback pattern as name`() {
        assertEquals("Curator", editor().localizedTitle(AppLanguage.EN))
        assertEquals("큐레이터", editor(titleEn = "").localizedTitle(AppLanguage.EN))
        assertEquals("큐레이터", editor().localizedTitle(AppLanguage.KO))
    }

    @Test
    fun `localizedBio follows the same fallback pattern as name`() {
        assertEquals("English bio", editor().localizedBio(AppLanguage.EN))
        assertEquals("한국어 소개", editor(bioEn = "").localizedBio(AppLanguage.EN))
        assertEquals("한국어 소개", editor().localizedBio(AppLanguage.KO))
    }
}
```

- [ ] **Step 2: Run the test file and confirm it fails to compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.GuestEditorLocalizationTest" 2>&1 | tail -30
```

Expected: compile error like `error: unresolved reference: GuestEditor`. This is the TDD-red signal; we have not written the class yet.

- [ ] **Step 3: Implement `GuestEditor`**

Write `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt`:

```kotlin
package com.gallr.shared.data.model

data class GuestEditor(
    val id: String,
    val nameKo: String,
    val nameEn: String,
    val titleKo: String,
    val titleEn: String,
    val bioKo: String,
    val bioEn: String,
) {
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
}
```

- [ ] **Step 4: Run the tests, confirm green**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.GuestEditorLocalizationTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` with 5 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/GuestEditor.kt shared/src/commonTest/kotlin/com/gallr/shared/data/model/GuestEditorLocalizationTest.kt
git commit -m "feat(model): add GuestEditor domain model with bilingual fallback (spec 040)

Mirrors the localized* pattern from Exhibition: EN falls back to KO
when EN is empty, KO is unconditional. Five tests cover both
languages and the fallback for name, title, and bio."
```

---

## Task 6: GuestEditorDto + toDomain mapping

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt`

Mirrors `ExhibitionDto` structure: `@Serializable`, `@SerialName` for each snake_case column, `toDomain()` returns the domain model. The DTO carries `is_active`, `active_from`, `active_to` because PostgREST returns them and `ignoreUnknownKeys = true` would silently drop them anyway — but `toDomain()` does NOT pass them to `GuestEditor`. They exist as DB filter inputs only.

- [ ] **Step 1: Write `GuestEditorDto`**

Write `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt`:

```kotlin
package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.GuestEditor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GuestEditorDto(
    val id: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String = "",
    @SerialName("title_ko") val titleKo: String,
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("bio_ko") val bioKo: String,
    @SerialName("bio_en") val bioEn: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("active_from") val activeFrom: String? = null,
    @SerialName("active_to") val activeTo: String? = null,
) {
    fun toDomain(): GuestEditor = GuestEditor(
        id = id,
        nameKo = nameKo,
        nameEn = nameEn,
        titleKo = titleKo,
        titleEn = titleEn,
        bioKo = bioKo,
        bioEn = bioEn,
    )
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileKotlinMetadata 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GuestEditorDto.kt
git commit -m "feat(dto): add GuestEditorDto with toDomain mapping (spec 040)

Mirrors ExhibitionDto: @Serializable with @SerialName for each
snake_case column, toDomain() returns the domain model. The DTO
carries is_active/active_from/active_to (PostgREST returns them)
but toDomain() drops those — they exist as DB filter inputs only,
not as UI-rendered data."
```

---

## Task 7: GuestEditorApiClient

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt`

The existing `ExhibitionApiClient.kt` instantiates its own Ktor `HttpClient` per client. We follow the same pattern rather than sharing a client — consistent with how `EventApiClient` is structured. One method: query the active editor with a date filter.

- [ ] **Step 1: Write the API client**

Write `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt`:

```kotlin
package com.gallr.shared.data.network

import com.gallr.shared.data.model.GuestEditor
import com.gallr.shared.data.network.dto.GuestEditorDto
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
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json

class GuestEditorApiClient(
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
     * Fetches the single active guest editor whose active_to is in the future or null.
     * When multiple is_active rows exist, the most recent active_from wins.
     * Returns null when no active editor exists.
     */
    suspend fun fetchActiveGuestEditor(): GuestEditor? {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val query = "select=*" +
            "&is_active=eq.true" +
            "&or=(active_to.is.null,active_to.gte.$today)" +
            "&order=active_from.desc" +
            "&limit=1"
        return client.get("$restBase/guest_editors?$query")
            .body<List<GuestEditorDto>>()
            .firstOrNull()
            ?.toDomain()
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileKotlinMetadata 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/GuestEditorApiClient.kt
git commit -m "feat(network): add GuestEditorApiClient for active editor query (spec 040)

One method: fetchActiveGuestEditor() runs the PostgREST query with
is_active=true, active_to null-or-in-future, ordered by active_from
desc, limit 1. Most-recent-active-from wins when multiple rows are
flagged active. Returns null when no editor exists.

Pattern matches ExhibitionApiClient / EventApiClient exactly."
```

---

## Task 8: GuestEditorRepository interface + impl

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt`

Mirrors `ExhibitionRepository` / `ExhibitionRepositoryImpl` exactly: an interface with a `Result`-returning suspend function, and a thin `runCatching` impl that wraps the API client.

- [ ] **Step 1: Write the interface**

Write `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.GuestEditor

interface GuestEditorRepository {
    /**
     * Returns Result.success(GuestEditor) when an active editor exists.
     * Returns Result.success(null) when no active editor exists (normal state).
     * Returns Result.failure on network or parse error; UI treats both as
     * "no editor" per spec (silent fail).
     */
    suspend fun getActiveGuestEditor(): Result<GuestEditor?>
}
```

- [ ] **Step 2: Write the impl**

Write `shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt`:

```kotlin
package com.gallr.shared.repository

import com.gallr.shared.data.model.GuestEditor
import com.gallr.shared.data.network.GuestEditorApiClient

class GuestEditorRepositoryImpl(
    private val apiClient: GuestEditorApiClient,
) : GuestEditorRepository {

    override suspend fun getActiveGuestEditor(): Result<GuestEditor?> =
        runCatching { apiClient.fetchActiveGuestEditor() }
}
```

- [ ] **Step 3: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileKotlinMetadata 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepository.kt shared/src/commonMain/kotlin/com/gallr/shared/repository/GuestEditorRepositoryImpl.kt
git commit -m "feat(repo): add GuestEditorRepository + impl (spec 040)

Mirrors ExhibitionRepository pattern: interface with one suspend
function returning Result<GuestEditor?>, thin runCatching wrapper
around the API client. Null vs failure both mean 'no editor' for
the UI."
```

---

## Task 9: FilterState — guest pick clause + tests (TDD)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`

Add `showGuestPick: Boolean` + `activeGuestEditorId: String?` to `FilterState`, and a new `guestPickMatch` clause inside `matches()`. The mutual-exclusivity rule is enforced at the ViewModel layer (Task 11) — `FilterState.matches()` only implements the inclusion logic.

- [ ] **Step 1: Write the failing tests**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt`. The existing `exhibition()` helper at lines 20-47 takes only the fields the existing tests vary. We need a helper variation that can set `guestEditorId`. Update the helper signature and pass-through.

Replace the existing `exhibition()` function (lines 20-47) with:

```kotlin
    private fun exhibition(
        region: String = "London",
        isFeatured: Boolean = false,
        isEditorsPick: Boolean = false,
        openingDate: kotlinx.datetime.LocalDate = yesterday,
        closingDate: kotlinx.datetime.LocalDate = inTenDays,
        guestEditorId: String? = null,
    ) = Exhibition(
        id = "x",
        nameKo = "Test",
        nameEn = "Test",
        venueNameKo = "Venue",
        venueNameEn = "Venue",
        cityKo = "London",
        cityEn = "London",
        regionKo = region,
        regionEn = region,
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = isFeatured,
        isEditorsPick = isEditorsPick,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        guestEditorId = guestEditorId,
    )
```

Then append these new tests inside the `FilterStateTest` class, just before the final closing `}`:

```kotlin
    // ── Spec 040: guest pick ──────────────────────────────────────────────────

    @Test
    fun `showGuestPick false matches all exhibitions regardless of editor tag`() {
        val filter = FilterState()
        assertTrue(filter.matches(exhibition(guestEditorId = null)))
        assertTrue(filter.matches(exhibition(guestEditorId = "minjung-kim")))
        assertTrue(filter.matches(exhibition(guestEditorId = "mira-park")))
    }

    @Test
    fun `showGuestPick true with matching editor id passes`() {
        val filter = FilterState(showGuestPick = true, activeGuestEditorId = "minjung-kim")
        assertTrue(filter.matches(exhibition(guestEditorId = "minjung-kim")))
    }

    @Test
    fun `showGuestPick true with different editor id fails`() {
        val filter = FilterState(showGuestPick = true, activeGuestEditorId = "minjung-kim")
        assertFalse(filter.matches(exhibition(guestEditorId = "mira-park")))
    }

    @Test
    fun `showGuestPick true with null editor on exhibition fails`() {
        val filter = FilterState(showGuestPick = true, activeGuestEditorId = "minjung-kim")
        assertFalse(filter.matches(exhibition(guestEditorId = null)))
    }

    @Test
    fun `showGuestPick true with null active editor id fails defensively`() {
        // Defensive: chip should not be tappable when no active editor exists,
        // but if state somehow drifts to this combination, the filter rejects
        // all exhibitions rather than silently matching every tagged one.
        val filter = FilterState(showGuestPick = true, activeGuestEditorId = null)
        assertFalse(filter.matches(exhibition(guestEditorId = "minjung-kim")))
        assertFalse(filter.matches(exhibition(guestEditorId = null)))
    }
```

- [ ] **Step 2: Run the test file and confirm it fails to compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.FilterStateTest" 2>&1 | tail -25
```

Expected: compile error referencing unresolved names `showGuestPick`, `activeGuestEditorId`. (Plus a runtime error path "no constructor matching" — same root cause.) This is the TDD-red signal.

- [ ] **Step 3: Implement the FilterState change**

Open `shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt`. The current data class is:

```kotlin
data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // Phase 2b — filter list to active-event-linked exhibitions
)
```

Add two new fields. Place `showGuestPick` next to the other boolean toggles (after `eventOnly`) and `activeGuestEditorId` last (it's ambient context, not a toggle):

```kotlin
data class FilterState(
    val regions: List<String> = emptyList(),
    val showFeatured: Boolean = false,
    val showEditorsPick: Boolean = false,
    val openingThisWeek: Boolean = false,
    val closingThisWeek: Boolean = false,
    val eventOnly: Boolean = false, // Phase 2b — filter list to active-event-linked exhibitions
    val showGuestPick: Boolean = false, // Spec 040 — user-toggled guest-editor filter
    val activeGuestEditorId: String? = null, // Spec 040 — ambient context set by ViewModel
)
```

Now update `matches()`. The current function ends with `return regionsMatch && featuredMatch && picksMatch && weekMatch`. Replace that final return with:

```kotlin
        val guestPickMatch = !showGuestPick ||
            (activeGuestEditorId != null && exhibition.guestEditorId == activeGuestEditorId)

        return regionsMatch && featuredMatch && picksMatch && weekMatch && guestPickMatch
```

Place the `guestPickMatch` computation immediately after the existing `weekMatch` computation (between line 35 and the `return` statement). Don't forget the new `guestPickMatch` token in the `return` AND-chain.

- [ ] **Step 4: Run the tests, confirm green**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.FilterStateTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` with all `FilterStateTest` tests passing (the 9 existing + 5 new = 14). If any existing test fails, the `matches()` change has a bug — verify the AND-chain logic.

- [ ] **Step 5: Run the full shared test suite for safety**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. The `GuestEditorLocalizationTest` from Task 5 and `ReceptionDateLabelTest` from Feature 1 (already on `develop`? — verify) plus all other shared tests pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/FilterState.kt shared/src/commonTest/kotlin/com/gallr/shared/data/model/FilterStateTest.kt
git commit -m "feat(filter): add guest-pick clause to FilterState (spec 040)

Two new fields: showGuestPick (user-toggled) and activeGuestEditorId
(ambient context written by the ViewModel when the active-editor
fetch resolves). matches() rejects exhibitions whose guestEditorId
doesn't equal activeGuestEditorId when showGuestPick is on.

Five new tests cover the four combinations of (showGuestPick,
activeGuestEditorId, exhibition.guestEditorId) plus a defensive
test for the impossible chip-on-but-no-active-editor state."
```

---

## Task 10: GuestEditorBanner composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt`

The chosen layout from brainstorming: white surface, 3 dp solid black left border, monospace small-caps "GUEST EDITOR" label, name in display style, title in body, italic bio. Token names match the existing codebase (`GallrSpacing`).

- [ ] **Step 1: Write the banner**

Write `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt`:

```kotlin
package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.gallr.shared.data.model.GuestEditor

/**
 * Editorial banner shown above the exhibition list when the guest-pick
 * filter is active. Left-border accent layout (spec 040): solid 3 dp
 * onSurface bar, monospace "GUEST EDITOR" label, editor name in display
 * style, bilingual title and italic bio.
 */
@Composable
fun GuestEditorBanner(
    editor: GuestEditor,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Left accent bar (3 dp solid)
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
        Column(
            modifier = Modifier.padding(GallrSpacing.md),
        ) {
            Text(
                text = if (lang == AppLanguage.KO) "게스트 에디터" else "GUEST EDITOR",
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
        }
    }
}
```

**Note on tokens:** the spec mentioned "Playfair Display serif" and a "monospace small-caps label." The codebase ships these typefaces via `DESIGN.md` and applies them through Material3 typography tokens. The code above uses `MaterialTheme.typography.titleLarge` for the name and `labelSmall` for the label — those should map to the configured serif and mono respectively. If the implementer discovers the actual configured tokens use different names (e.g. `headlineSmall`, `labelMedium`), substitute the ones that match the design intent and note the substitution in the commit.

- [ ] **Step 2: Compile the composeApp module**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. If the build fails because an imported symbol doesn't exist (e.g. `GallrSpacing.screenMargin` was renamed), fix the imports.

- [ ] **Step 3: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GuestEditorBanner.kt
git commit -m "feat(ui): add GuestEditorBanner composable (spec 040)

Left-border accent layout: 3 dp onSurface bar, monospace 'GUEST
EDITOR' label (mixed-case in Korean — no case convention),
editor name in titleLarge, bilingual title in bodyMedium, italic
bio. Lives in ui/components alongside EventListBanner and
GallrEmptyState."
```

---

## Task 11: TabsViewModel — guest editor state + mutual exclusivity

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`

This task touches one file across several sections:

1. Take a `GuestEditorRepository` in the constructor.
2. Expose `activeGuestEditor: StateFlow<GuestEditor?>`.
3. Add `loadActiveGuestEditor()` — populates the flow AND writes `activeGuestEditorId` into `_filterState`.
4. Add `toggleGuestPick()` — turns on/off with mutual exclusivity.
5. Modify each of the six existing filter mutations to clear `showGuestPick` when they enable a different filter.
6. Hook into `refresh()` and `init { ... }`.
7. Update the `factory()` companion to pass through the new dependency.

- [ ] **Step 1: Add the import + constructor parameter**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`. Find the imports block (lines 18-22 for repositories). Add `GuestEditor` to the model imports near `Event` (around line 17):

```kotlin
import com.gallr.shared.data.model.GuestEditor
```

And add `GuestEditorRepository` to the repository imports near `EventRepository` (around line 19):

```kotlin
import com.gallr.shared.repository.GuestEditorRepository
```

Then add `guestEditorRepository` as the last constructor parameter (after `eventRepository` at line 45):

```kotlin
class TabsViewModel(
    private val exhibitionRepository: ExhibitionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val languageRepository: LanguageRepository,
    private val themeRepository: ThemeRepository,
    private val eventRepository: EventRepository,
    private val guestEditorRepository: GuestEditorRepository,
) : ViewModel() {
```

- [ ] **Step 2: Add `_activeGuestEditor` state next to the existing active-event state**

In the "Active event" section (currently around lines 85-106), find this block:

```kotlin
    private val _activeEvent = MutableStateFlow<Event?>(null)
    val activeEvent: StateFlow<Event?> = _activeEvent

    private val _activeEventsById = MutableStateFlow<Map<String, Event>>(emptyMap())
    val activeEventsById: StateFlow<Map<String, Event>> = _activeEventsById
```

Add a parallel block immediately after the existing `_activeEvent` block (after the `activeEventsById` declaration, before the `loadActiveEvents` function):

```kotlin
    // ── Active guest editor (spec 040) ───────────────────────────────────────

    private val _activeGuestEditor = MutableStateFlow<GuestEditor?>(null)
    val activeGuestEditor: StateFlow<GuestEditor?> = _activeGuestEditor

    private fun loadActiveGuestEditor() {
        viewModelScope.launch {
            guestEditorRepository.getActiveGuestEditor()
                .onSuccess { editor ->
                    _activeGuestEditor.value = editor
                    _filterState.value = _filterState.value.copy(activeGuestEditorId = editor?.id)
                }
                .onFailure {
                    println("ERROR [TabsViewModel] loadActiveGuestEditor: ${it.message}")
                    _activeGuestEditor.value = null
                    _filterState.value = _filterState.value.copy(
                        activeGuestEditorId = null,
                        showGuestPick = false,
                    )
                }
        }
    }
```

Failure path clears `showGuestPick` so a stale toggled-on state can't survive a failed refresh.

- [ ] **Step 3: Add `toggleGuestPick()` + apply mutual exclusivity to existing toggles**

Find the "Filter state" section (around lines 117-124). After the existing `updateFilter` function, add:

```kotlin
    fun toggleGuestPick() {
        _filterState.value = _filterState.value.let { current ->
            val turningOn = !current.showGuestPick
            if (turningOn) {
                FilterState(
                    activeGuestEditorId = current.activeGuestEditorId,
                    showGuestPick = true,
                )
            } else {
                current.copy(showGuestPick = false)
            }
        }
        _selectedCity.value = null  // also clear city when entering guest-pick
    }
```

Then apply the "tapping any other filter clears guest-pick" rule by editing six toggle paths. For each one, append `showGuestPick = false` to the `copy()` call:

**(a)** `setCity()` at line 131 — current:
```kotlin
    fun setCity(cityKo: String?) {
        _selectedCity.value = cityKo
        _filterState.value = _filterState.value.copy(regions = emptyList())
    }
```
Change to:
```kotlin
    fun setCity(cityKo: String?) {
        _selectedCity.value = cityKo
        _filterState.value = _filterState.value.copy(regions = emptyList(), showGuestPick = false)
    }
```

**(b)** `toggleRegion()` at line 161 — current:
```kotlin
    fun toggleRegion(regionKo: String) {
        _filterState.value = _filterState.value.let { current ->
            if (regionKo in current.regions) {
                current.copy(regions = current.regions - regionKo)
            } else {
                current.copy(regions = current.regions + regionKo)
            }
        }
    }
```
Change to:
```kotlin
    fun toggleRegion(regionKo: String) {
        _filterState.value = _filterState.value.let { current ->
            if (regionKo in current.regions) {
                current.copy(regions = current.regions - regionKo, showGuestPick = false)
            } else {
                current.copy(regions = current.regions + regionKo, showGuestPick = false)
            }
        }
    }
```

**(c)** The five inline chip onClicks live in `ListScreen.kt`, not `TabsViewModel.kt`. We address those in Task 12 — they call `viewModel.updateFilter { copy(...) }` which doesn't have a centralized chokepoint. To enforce the rule centrally, add a helper on `TabsViewModel`:

After `toggleGuestPick`, add:

```kotlin
    /**
     * Apply a FilterState transform that represents the user enabling a
     * non-guest-pick filter. Always clears showGuestPick to enforce the
     * mutual-exclusivity rule from spec 040.
     */
    fun updateNonGuestFilter(update: FilterState.() -> FilterState) {
        _filterState.value = _filterState.value.update().copy(showGuestPick = false)
    }
```

The Task 12 chip changes will route through this helper instead of `updateFilter` for the chips that need exclusivity. The `updateFilter` function itself stays put — it's still used by code paths that don't need exclusivity (e.g. the init-block stale-event clearer at line 356).

- [ ] **Step 4: Verify `clearAllFilters` resets the new fields**

Find `clearAllFilters()` at line 184:

```kotlin
    fun clearAllFilters() {
        _filterState.value = FilterState()
        _selectedCity.value = null
    }
```

It already resets to a default `FilterState()`. With the new fields added to `FilterState` (Task 9), `FilterState()` produces `showGuestPick = false, activeGuestEditorId = null`. But we want to preserve `activeGuestEditorId` (the editor still exists, even if filters are cleared). Update to:

```kotlin
    fun clearAllFilters() {
        _filterState.value = FilterState(activeGuestEditorId = _filterState.value.activeGuestEditorId)
        _selectedCity.value = null
    }
```

- [ ] **Step 5: Hook the new fetch into `refresh()` and `init`**

Find `refresh()` at line 330:

```kotlin
    fun refresh() {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
    }
```

Change to:

```kotlin
    fun refresh() {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
        loadActiveGuestEditor()
    }
```

Find the `init` block at line 345:

```kotlin
    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
        // ...
    }
```

Add the new fetch:

```kotlin
    init {
        loadFeaturedExhibitions()
        loadAllExhibitions()
        loadActiveEvents()
        loadActiveGuestEditor()
        // ...
    }
```

Then below the existing `activeEvent` collector that auto-clears stranded `eventOnly` (lines 353-359), add a parallel collector for guest pick:

```kotlin
        // Spec 040 — when the active guest editor disappears (deactivated,
        // network failure on refresh), silently clear any stranded
        // showGuestPick filter so the List tab doesn't show an empty feed
        // with no way to recover.
        viewModelScope.launch {
            _activeGuestEditor.collect { editor ->
                if (editor == null && _filterState.value.showGuestPick) {
                    _filterState.value = _filterState.value.copy(showGuestPick = false)
                }
            }
        }
```

- [ ] **Step 6: Update the `factory()` companion**

Find the `companion object` block at line 364:

```kotlin
    companion object {
        fun factory(
            exhibitionRepository: ExhibitionRepository,
            bookmarkRepository: BookmarkRepository,
            languageRepository: LanguageRepository,
            themeRepository: ThemeRepository,
            eventRepository: EventRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TabsViewModel(
                    exhibitionRepository,
                    bookmarkRepository,
                    languageRepository,
                    themeRepository,
                    eventRepository,
                )
            }
        }
    }
```

Change to:

```kotlin
    companion object {
        fun factory(
            exhibitionRepository: ExhibitionRepository,
            bookmarkRepository: BookmarkRepository,
            languageRepository: LanguageRepository,
            themeRepository: ThemeRepository,
            eventRepository: EventRepository,
            guestEditorRepository: GuestEditorRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TabsViewModel(
                    exhibitionRepository,
                    bookmarkRepository,
                    languageRepository,
                    themeRepository,
                    eventRepository,
                    guestEditorRepository,
                )
            }
        }
    }
```

- [ ] **Step 7: Compile composeApp**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -20
```

Expected: BUILD FAILED with a small set of errors at the `ListScreen.kt` call site `TabsViewModel.factory(...)` (because the factory now takes a sixth arg) AND at `MainActivity.kt` / `MainViewController.kt` (because they call `factory(...)` and don't yet pass the new repo). We fix those in Tasks 12 and 13. For now, expect the failure and move on — but verify the errors are ONLY about the missing constructor / factory arg, not about anything else in `TabsViewModel`.

- [ ] **Step 8: Commit (broken-but-isolated state)**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt
git commit -m "feat(viewmodel): wire guest editor state + mutual exclusivity (spec 040)

TabsViewModel now takes a GuestEditorRepository, exposes
activeGuestEditor as a StateFlow, fetches on init + refresh, and
clears stranded showGuestPick when the editor disappears.

toggleGuestPick() turns the filter on/off with mutual exclusivity.
setCity, toggleRegion, and clearAllFilters now clear showGuestPick.
The new updateNonGuestFilter helper is used by chip handlers in
ListScreen (Task 12).

NOTE: this commit leaves the build BROKEN until Tasks 12 + 13 land
because the factory signature changed. The errors are confined to
the factory call sites (MainActivity, MainViewController) and
ListScreen.kt's chip onClicks — no behavioral surprises."
```

(The "broken commit" is acceptable here because the immediately-following tasks restore the build. If you'd rather not have a broken intermediate commit, hold this commit until Tasks 12 + 13 are also done, and squash them together at the end.)

---

## Task 12: ListScreen — chip + animated banner + DI of factory

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`

Three sub-changes:

1. Wire the new factory parameter at the `TabsViewModel.factory(...)` call site (line 158, the one in `App.kt` is the same; verify which file).
2. Add a leftmost guest-pick chip to the filter Row.
3. Insert the animated banner between the filter Row and the action button Row.
4. Add empty-state copy when guest pick is active and the list is empty.

- [ ] **Step 1: Locate the factory call site**

Search:

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -n "TabsViewModel.factory" composeApp/src/commonMain/kotlin/com/gallr/app/App.kt composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt
```

Expected: one match in `App.kt` (which orchestrates the ViewModel construction). The screen itself receives the already-constructed ViewModel as a parameter. We fix `App.kt` in Task 13 (DI wiring) — note this and move on.

For now in this task, do NOT touch `App.kt`. We assume the build will compile once Task 13 wires the new repo through.

- [ ] **Step 2: Add the imports + new state collection**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`. Add imports near the existing component imports (around line 56):

```kotlin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.gallr.app.ui.components.GuestEditorBanner
import com.gallr.shared.data.model.GuestEditor
```

Then add the new state collection inside `ListScreen()` next to the existing collectors (around line 90):

```kotlin
    val activeGuestEditor by viewModel.activeGuestEditor.collectAsState()
```

- [ ] **Step 3: Insert the guest-pick chip as the leftmost item**

Find the filter chip Row (currently lines 290-329 of `ListScreen.kt`). The Row currently starts with the active-event chip at line 296:

```kotlin
            activeEvent?.let { event ->
                val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
                GallrEventFilterChip(...)
                Spacer(Modifier.width(GallrSpacing.sm))
            }
```

Add this block IMMEDIATELY before that `activeEvent?.let { event ->` line (so it becomes the first child of the Row):

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

- [ ] **Step 4: Apply mutual exclusivity to the five non-guest chip onClicks**

The chips at lines 298-303 (event), 306-310 (featured), 312-316 (editor's picks), 318-322 (opening this week), 324-328 (closing this week) each call `viewModel.updateFilter { copy(...) }`. Replace each one with `viewModel.updateNonGuestFilter { ... }` so the helper from Task 11 enforces the rule.

For example, the `showFeatured` chip at line 308:

```kotlin
                onClick = { viewModel.updateFilter { copy(showFeatured = !showFeatured) } },
```

becomes:

```kotlin
                onClick = { viewModel.updateNonGuestFilter { copy(showFeatured = !showFeatured) } },
```

Do the equivalent for the other four chips:

- Event filter chip onClick (around line 300): `viewModel.updateFilter { copy(eventOnly = !eventOnly) }` → `viewModel.updateNonGuestFilter { copy(eventOnly = !eventOnly) }`
- Editor's Picks chip onClick (line 314): `updateFilter { copy(showEditorsPick = !showEditorsPick) }` → `updateNonGuestFilter { ... }`
- Opening This Week (line 320): same swap
- Closing This Week (line 326): same swap

Do not change the `clearAllFilters()` call (line 336) — Task 11 already handles it via the `clearAllFilters` ViewModel method.

- [ ] **Step 5: Insert the animated banner block between the filter chip Row and the action button Row**

The filter chip Row's closing `}` is currently around line 329. The action button Row currently starts around line 332. Insert this block in between:

```kotlin
        // ── Guest editor banner (spec 040) ────────────────────────────────
        AnimatedVisibility(
            visible = filter.showGuestPick && activeGuestEditor != null,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150)),
        ) {
            activeGuestEditor?.let { GuestEditorBanner(it, lang) }
        }
```

(`AnimatedVisibility`, `expandVertically`, `shrinkVertically` are already imported at the top of the file from existing usages elsewhere — verify by checking the import list. If not, add the missing imports.)

- [ ] **Step 6: Add the empty-state copy when guest pick is the cause**

Find where the existing "no exhibitions match" rendering happens. It uses `GallrEmptyState` (imported at line 59). Search for the call:

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -n "GallrEmptyState(" composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt
```

In each `GallrEmptyState(message = ...)` site that renders when filters produced an empty list, wrap or replace the `message` value to switch on the guest-pick filter:

```kotlin
                message = when {
                    filter.showGuestPick -> if (lang == AppLanguage.KO)
                        "선택된 전시가 없습니다"
                    else
                        "No exhibitions in this list"
                    else -> /* the existing message expression that was there */
                },
```

Repeat for every empty-state site that fires when the user has filters applied. (If there's only one centralized site, change one place.) Do NOT touch empty-state copy that fires for non-filtered states (e.g. "no exhibitions loaded yet").

- [ ] **Step 7: Compile**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -20
```

Expected: still BUILD FAILED, because the `TabsViewModel.factory(...)` call in `App.kt` doesn't pass `guestEditorRepository` yet. Confirm the only remaining errors are about that single missing arg — Task 13 fixes them.

- [ ] **Step 8: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt
git commit -m "feat(ui): guest editor chip + animated banner in ListScreen (spec 040)

Leftmost chip in the filter row, only shown when an active editor
is fetched. Tap reveals the editorial banner via expandVertically +
fadeIn (~250ms). All other chips use updateNonGuestFilter to enforce
mutual exclusivity. Empty-state copy switches when guest pick is
the cause of an empty result list.

Still depends on Task 13 to wire the new repo through DI."
```

---

## Task 13: DI wiring — Android + iOS + App composable

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

Three small changes, all instantiating `GuestEditorApiClient` + `GuestEditorRepositoryImpl` and passing through to `App(...)` which passes to `TabsViewModel.factory`.

- [ ] **Step 1: Android `MainActivity.kt`**

Open `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`. Add imports:

```kotlin
import com.gallr.shared.data.network.GuestEditorApiClient
import com.gallr.shared.repository.GuestEditorRepository
import com.gallr.shared.repository.GuestEditorRepositoryImpl
```

Find the `eventRepository` construction (around line 87):

```kotlin
        val eventRepository = EventRepositoryImpl(
            EventApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
```

Add immediately after it:

```kotlin
        val guestEditorRepository: GuestEditorRepository = GuestEditorRepositoryImpl(
            GuestEditorApiClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        )
```

Then in the `App(...)` call (line 150-165), add `guestEditorRepository = guestEditorRepository` near `eventRepository`. The position should match the order declared in `App()` once we update that signature in Step 3.

- [ ] **Step 2: iOS `MainViewController.kt`**

Open `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`. Find the `ExhibitionApiClient` and `EventApiClient` instantiations (around lines 51-54):

```kotlin
        ExhibitionApiClient(supabaseUrl = supabaseUrl, anonKey = anonKey)
        EventApiClient(supabaseUrl = supabaseUrl, anonKey = anonKey)
```

(Exact surrounding code — read the file to confirm structure.) Add a parallel instantiation:

```kotlin
        val guestEditorRepository = GuestEditorRepositoryImpl(
            GuestEditorApiClient(supabaseUrl = supabaseUrl, anonKey = anonKey)
        )
```

Plus the imports:

```kotlin
import com.gallr.shared.data.network.GuestEditorApiClient
import com.gallr.shared.repository.GuestEditorRepositoryImpl
```

Pass `guestEditorRepository` to the `App(...)` invocation.

- [ ] **Step 3: `App.kt` — composable signature + factory pass-through**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`. Add the repository import near the existing `EventRepository` import:

```kotlin
import com.gallr.shared.repository.GuestEditorRepository
```

Add `guestEditorRepository: GuestEditorRepository` as a parameter to `fun App(...)` (currently starting line 98). Place it next to `eventRepository` to mirror call-site ordering.

Then find the `TabsViewModel.factory(...)` call (line 158):

```kotlin
        factory = TabsViewModel.factory(exhibitionRepository, syncBookmarkRepository, languageRepository, themeRepository, eventRepository),
```

Add `guestEditorRepository` as the sixth argument:

```kotlin
        factory = TabsViewModel.factory(exhibitionRepository, syncBookmarkRepository, languageRepository, themeRepository, eventRepository, guestEditorRepository),
```

- [ ] **Step 4: Compile composeApp Android target**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run all shared unit tests one more time for regression safety**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. The `GuestEditorLocalizationTest` (Task 5) and `FilterStateTest` (Task 9) plus all prior tests pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git add composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(di): wire GuestEditorRepository through Android + iOS entry points (spec 040)

MainActivity and MainViewController construct GuestEditorApiClient +
GuestEditorRepositoryImpl and pass to App(). App() forwards the
repo to TabsViewModel.factory.

After this commit the build is green again."
```

---

## Task 14: Full-suite verification + push

**Files:** none modified — verification only.

- [ ] **Step 1: Sanity-check that all the new symbols resolve in the right places**

```bash
cd /Users/hanshin/Documents/Projects/gallr
grep -rn "GuestEditor" shared/src/commonMain composeApp/src/commonMain composeApp/src/androidMain composeApp/src/iosMain 2>/dev/null | grep -v Test | wc -l
```

Expected: a non-trivial number (~20+ matches across model, DTO, API client, repo, repo impl, banner, ViewModel, ListScreen, MainActivity, MainViewController, App.kt).

- [ ] **Step 2: Run the JVM/Android shared test suite**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:testDebugUnitTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Compile both targets**

```bash
cd /Users/hanshin/Documents/Projects/gallr
./gradlew :shared:compileKotlinMetadata :composeApp:compileDebugKotlinAndroid 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. If iOS targets fail, note as `DONE_WITH_CONCERNS` per the pre-existing iOS issue documented in spec — they fail on `CityRegionFilterTest.kt`, unrelated to this branch.

- [ ] **Step 4: Push the branch**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git push -u origin 040-guest-editor 2>&1 | tail -10
```

Expected: `branch '040-guest-editor' set up to track 'origin/040-guest-editor'`.

- [ ] **Step 5: Capture the commit log for the PR description**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git log --oneline origin/develop..HEAD
```

Record the output — useful for the PR description's "What's in this branch" section.

- [ ] **Step 6: Document the manual smoke-test plan in the PR description (no code change in this step)**

Refer to spec section "Test plan → Manual smoke test." The exact steps live in `specs/040-guest-editor/spec.md` and should be copy-pasted into the PR description. They are not repeated here to avoid drift.

Stop here. PR creation is a separate step the user will trigger.

---

## Spec Coverage Self-Check

- Spec **"Data model"** (table + FK + indices + active-editor query): Task 1 (migration).
- Spec **"Sync pipeline"** (KNOWN_COLUMNS + FK fetch + buildRecord branch): Tasks 2 (KNOWN_COLUMNS isolated) + 3 (FK validation + branch).
- Spec **"Shared module → Exhibition + DTO"**: Task 4.
- Spec **"Shared module → new files: GuestEditor, DTO, repo, impl"**: Tasks 5, 6, 7, 8.
- Spec **"Shared module → FilterState"** (new fields + matches clause + tests): Task 9.
- Spec **"UI → GuestEditorBanner"**: Task 10.
- Spec **"UI → ListScreen chip + animation + empty state"**: Task 12.
- Spec **"UI → ViewModel state + toggleGuestPick + mutual exclusivity"**: Task 11.
- Spec **"Acceptance criteria"** — mapped to manual smoke test, listed in spec and referenced from Task 14.
- Spec **"Bilingual strings"** — chip label uppercased EN / mixed-case KO at Task 12 step 3; banner label "GUEST EDITOR" / "게스트 에디터" at Task 10; empty state at Task 12 step 6.
- Spec **"Risk → KNOWN_COLUMNS trap"** — Task 2 is intentionally isolated so the diff is easy to inspect.
- Spec **"Risk → Editor onboarding ordering"** — operational discipline, not code. Documented in commit message for Task 1.

No placeholders. No "TBD". Function and property names are consistent (`showGuestPick`, `activeGuestEditorId`, `loadActiveGuestEditor`, `toggleGuestPick`, `updateNonGuestFilter`, `getActiveGuestEditor`, `fetchActiveGuestEditor`, `GuestEditor`, `GuestEditorDto`, `GuestEditorRepository`, `GuestEditorRepositoryImpl`, `GuestEditorApiClient`, `GuestEditorBanner`) across all tasks where they appear.

The plan has one intentional broken-state mid-commit (end of Task 11). This is flagged in the commit message and Task 12 + 13 restore the build. If the implementer prefers an always-green history, they can hold the Task 11 commit and squash with 12 + 13 at the end — both flows are valid.
