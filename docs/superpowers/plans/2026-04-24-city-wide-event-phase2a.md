# City-Wide Art Event — Phase 2a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the events-sync FK orphan window by switching `gas/SyncEvents.gs` from delete-all-then-insert to upsert + diff-delete, and add a hero image to the Featured event promotion card backed by a new `events.cover_image_url` column.

**Architecture:** Two parallel changes. Server side: a Supabase migration adds the column, an Apps Script rewrite changes the write semantics. Client side: KMP model + DTO grow one nullable field; the Compose card layers an `AsyncImage` background under a dark gradient scrim with the existing brand-color box as the fallback. No new files in the data layer (migration counts as one); two existing UI files plus one new Storage bucket entry.

**Tech Stack:** Kotlin 2.1.20 (KMP), Compose Multiplatform 1.8.0, Coil 3.1.0 (`coil3.compose.AsyncImage` — already a dependency), kotlinx.serialization 1.7, Supabase Postgres + REST + Storage, Google Apps Script V8.

**Spec:** `docs/superpowers/specs/2026-04-24-city-wide-event-phase2a-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|------|----------------|
| `supabase/migrations/014_add_event_cover_image_url.sql` | Add nullable `cover_image_url` column to `events` |

### Files to modify

| Path | Change |
|------|--------|
| `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt` | Add `val coverImageUrl: String? = null` as last constructor parameter |
| `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt` | Add `@SerialName("cover_image_url") val coverImageUrl: String? = null`; map in `toDomain()` |
| `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt` | Add one assertion to existing `EventDto toDomain returns Event with parsed dates and defaults` test |
| `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt` | Restructure card: image background + scrim + bottom-anchored text; remove "→" arrow |
| `gas/SyncEvents.gs` | Replace `deleteAllEvents` + `insertEvents` with `upsertEvents` + `diffDeleteEvents`; add `cover_image_url` to `KNOWN_COLUMNS` and `buildRecord` |

### Files NOT to modify

- `shared/.../repository/EventRepository.kt` and `EventRepositoryImpl.kt` — surface unchanged; the new `coverImageUrl` flows through unchanged via `Event` and the existing `getActiveEvents`/`getEventById`/`getExhibitionsForEvent` calls.
- `shared/.../data/network/EventApiClient.kt` — unchanged; `select=*` already grabs the new column.
- `composeApp/.../ui/event/EventDetailScreen.kt` — Phase 2a does NOT add the hero image to the Detail page header (deferred to a later visual polish pass; the Detail page's existing 150dp branded header already has visual weight).
- `composeApp/.../viewmodel/EventDetailViewModel.kt` — unchanged.
- `composeApp/.../viewmodel/TabsViewModel.kt` — unchanged.

### Manual operator steps (not in code)

| Step | Where |
|------|-------|
| Create `event-images` Storage bucket, public-read | Supabase dashboard or one-off SQL |
| Upload Loop Lab Busan hero image to bucket | Supabase dashboard |
| Add `cover_image_url` cell value to events sheet | Google Sheet |
| Re-enable 5-min time-driven trigger on `syncEventsToSupabase` | Apps Script editor (was disabled at end of Phase 1 to work around C2) |

---

## Task Breakdown

Tasks ordered as a vertical slice: data → domain → UI → GAS → operator steps. Each task ends green + commit.

---

### Task 1: Supabase migration — add cover_image_url

**Files:**
- Create: `supabase/migrations/014_add_event_cover_image_url.sql`

- [ ] **Step 1: Write the migration**

Create `supabase/migrations/014_add_event_cover_image_url.sql` with this exact content:

```sql
-- Migration: 014_add_event_cover_image_url.sql
-- Adds a nullable cover_image_url column to the events table for the
-- Phase 2a hero image on the Featured promotion card. The column accepts
-- either a full HTTPS URL (used as-is) or a bare filename (resolved at
-- sync time to the public event-images Supabase Storage bucket).

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS cover_image_url TEXT;
```

- [ ] **Step 2: Apply the migration via Supabase CLI**

```bash
supabase migration up --linked
```

Expected: `Applying migration 014_add_event_cover_image_url.sql...` and `Local database is up to date.`

If the harness denies the production write, ask the user to confirm before retrying with `--linked`.

- [ ] **Step 3: Verify the column landed**

```bash
KEY=$(grep "^supabase.anon.key=" local.properties | cut -d= -f2-); URL="https://yhuhjxswjbrtmbpbrciq.supabase.co/rest/v1"; curl -s "$URL/events?select=id,cover_image_url&limit=1" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
```

Expected: a JSON array; the single Loop Lab Busan row should include `"cover_image_url": null`.

- [ ] **Step 4: Commit**

```bash
git add supabase/migrations/014_add_event_cover_image_url.sql
git commit -m "feat(db): add events.cover_image_url for Phase 2a hero image"
```

---

### Task 2: Add coverImageUrl to Event model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt`

This task adds a nullable field to a data class. No new tests; existing tests must continue to pass.

- [ ] **Step 1: Add the field as the LAST constructor parameter**

In `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt`, find the constructor closing:

```kotlin
    val ticketUrl: String?,
    val isActive: Boolean,
) {
```

Replace with:

```kotlin
    val ticketUrl: String?,
    val isActive: Boolean,
    val coverImageUrl: String? = null,
) {
```

Default `null` preserves backward compat for the existing positional callers in `EventRepositoryTest.kt` and the `EventTest.kt` `sample` literal.

- [ ] **Step 2: Verify existing tests still pass**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: all tests PASS. The 13 existing event-related tests should not regress.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/model/Event.kt
git commit -m "feat(shared): add nullable coverImageUrl to Event model"
```

---

### Task 3: Add cover_image_url to EventDto with mapping

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt`
- Modify: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt`

Strict TDD: extend the existing DTO test, watch it fail, add the field, watch it pass.

- [ ] **Step 1: Add the failing assertion**

Open `shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt`. Find the test named `EventDto toDomain returns Event with parsed dates and defaults`. After its existing `kotlin.test.assertEquals(null, event.accentColor)  // optional` line, add this assertion as the last line of the test body (before the closing `}`):

```kotlin
        kotlin.test.assertEquals(null, event.coverImageUrl)  // optional default
```

- [ ] **Step 2: Run the test — verify it fails to compile**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: FAIL with "unresolved reference: coverImageUrl" (because `EventDto.toDomain()` doesn't yet map a `coverImageUrl` field on the resulting `Event`, and `Event` from Task 2 has the field but the DTO doesn't yet read it from JSON).

If the test compiles and passes (because Task 2 made `Event.coverImageUrl` default to null), that's still acceptable — the DTO mapping in Step 3 will add the actual JSON-to-domain wiring.

- [ ] **Step 3: Add the field to EventDto and map it**

In `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt`:

(a) Find the closing of the constructor:

```kotlin
    @SerialName("is_active") val isActive: Boolean = true,
) {
```

Replace with:

```kotlin
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
) {
```

(b) In `toDomain()`, find the last property in the `Event(...)` call:

```kotlin
            isActive = isActive,
        )
```

Replace with:

```kotlin
            isActive = isActive,
            coverImageUrl = coverImageUrl,
        )
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
./gradlew :shared:testDebugUnitTest --tests "com.gallr.shared.data.model.EventTest"
```

Expected: ALL tests PASS (the existing 13 tests + the one new assertion = 14 within `EventTest`).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/EventDto.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/data/model/EventTest.kt
git commit -m "feat(shared): map cover_image_url through EventDto"
```

---

### Task 4: Restructure EventPromotionCard with hero image

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt`

The card moves from a flat brand-color box with a Row layout to a 140dp Box with three Z-stacked layers: image background, dark scrim, bottom-anchored text. The trailing "→" arrow is removed.

- [ ] **Step 1: Replace the file contents**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt` and replace the entire file with this exact content:

```kotlin
package com.gallr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Event
import com.gallr.shared.util.parseHexColor

@Composable
fun EventPromotionCard(
    event: Event,
    lang: AppLanguage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = parseHexColor(event.brandColor)?.let { Color(it) } ?: Color.Black
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

    val eyebrow = if (lang == AppLanguage.KO) "지금 진행 중 · ART EVENT" else "NOW ON · ART EVENT"
    val meta = "${event.localizedDateRange(lang)} · ${event.localizedLocationLabel(lang)}"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(brand)
            .border(1.dp, Color.Black)
            .clickable(onClick = onTap),
    ) {
        // Layer 1: hero image fills the box; absent / failed → brand color shows through
        if (event.coverImageUrl != null) {
            AsyncImage(
                model = event.coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Layer 2: bottom-to-top dark scrim for text legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    )
                ),
        )

        // Layer 3: text content anchored bottom-left
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        ) {
            Text(
                text = eyebrow,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = nameDisplay,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun Event.localizedDateRange(lang: AppLanguage): String {
    val from = startDate
    val to = endDate
    return when (lang) {
        AppLanguage.KO -> "${from.year}.${from.monthNumber.toString().padStart(2, '0')}.${from.dayOfMonth.toString().padStart(2, '0')} – ${to.year}.${to.monthNumber.toString().padStart(2, '0')}.${to.dayOfMonth.toString().padStart(2, '0')}"
        AppLanguage.EN -> {
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${months[from.monthNumber - 1]} ${from.dayOfMonth} – ${months[to.monthNumber - 1]} ${to.dayOfMonth}, ${to.year}"
        }
    }
}
```

Notable changes vs. the previous version:
- Removed `Row`, `Arrangement` imports (no longer needed; layout is Z-stacked Box)
- Added `Brush`, `ContentScale`, `coil3.compose.AsyncImage`, `fillMaxSize` imports
- The trailing `Text("→")` is gone
- Card height fixed at 140dp
- `AsyncImage` only renders when `coverImageUrl != null` — when null, layer 1 simply doesn't draw and the brand-color background of the outer Box shows through behind the scrim. Text overlaid in white still has contrast against the brand color.

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify shared tests still pass (no regression)**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: ALL tests PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt
git commit -m "feat(featured): hero image background with scrim on event card"
```

---

### Task 5: Rewrite SyncEvents.gs to upsert + diff-delete

**Files:**
- Modify: `gas/SyncEvents.gs`

Replace the destructive write path with an additive upsert plus a single targeted diff-delete. Also adds `cover_image_url` to known columns and the `buildRecord` switch.

- [ ] **Step 1: Add `cover_image_url` to KNOWN_COLUMNS**

In `gas/SyncEvents.gs`, find:

```javascript
var KNOWN_COLUMNS = [
  'id',
  'name_ko', 'name_en',
  'description_ko', 'description_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color', 'accent_color',
  'ticket_url',
  'is_active',
];
```

Replace with:

```javascript
var KNOWN_COLUMNS = [
  'id',
  'name_ko', 'name_en',
  'description_ko', 'description_en',
  'location_label_ko', 'location_label_en',
  'start_date', 'end_date',
  'brand_color', 'accent_color',
  'ticket_url',
  'is_active',
  'cover_image_url',
];
```

- [ ] **Step 2: Add the `cover_image_url` branch to `buildRecord`**

In the same file, find `buildRecord`. The current `KNOWN_COLUMNS.forEach(...)` body has branches for `start_date`/`end_date`, `is_active`, `brand_color`/`accent_color`, and a default `String(raw).trim()`.

Find the `brand_color`/`accent_color` branch:

```javascript
    } else if (col === 'brand_color' || col === 'accent_color') {
      var s = String(raw).trim();
      record[col] = (s.charAt(0) === '#') ? s : ('#' + s);
    } else {
      record[col] = String(raw).trim();
    }
```

Insert the new `cover_image_url` branch BEFORE the `else` (final default) branch:

```javascript
    } else if (col === 'brand_color' || col === 'accent_color') {
      var s = String(raw).trim();
      record[col] = (s.charAt(0) === '#') ? s : ('#' + s);
    } else if (col === 'cover_image_url') {
      var url = String(raw || '').trim();
      if (!url) return;  // omit; null in DB
      if (/^https?:\/\//i.test(url)) {
        record[col] = url;
      } else {
        var props = PropertiesService.getScriptProperties();
        var baseUrl = props.getProperty('SUPABASE_URL');
        record[col] = baseUrl + '/storage/v1/object/public/event-images/' + encodeURIComponent(url);
      }
    } else {
      record[col] = String(raw).trim();
    }
```

- [ ] **Step 3: Add upsert and diff-delete helpers**

Find the existing `deleteAllEvents` and `insertEvents` functions (near the bottom of the file, before `logFailure`). Replace BOTH with these two new functions. The old function names are no longer called by the rewritten `syncEventsToSupabase` body in step 4.

Replace:

```javascript
function deleteAllEvents(supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events?id=neq.__never__';
  var response = UrlFetchApp.fetch(url, {
    method: 'delete',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 204 && code !== 200) {
    throw new Error('Delete events failed with code ' + code + ': ' + response.getContentText());
  }
}

function insertEvents(rows, supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events';
  var response = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    payload: JSON.stringify(rows),
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 201 && code !== 200) {
    throw new Error('Insert events failed with code ' + code + ': ' + response.getContentText());
  }
}
```

with:

```javascript
function upsertEvents(rows, supabaseUrl, serviceKey) {
  var url = supabaseUrl + '/rest/v1/events';
  var response = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'resolution=merge-duplicates,return=minimal',
    },
    payload: JSON.stringify(rows),
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 201 && code !== 200) {
    throw new Error('Upsert events failed with code ' + code + ': ' + response.getContentText());
  }
}

function diffDeleteEvents(keepIds, supabaseUrl, serviceKey) {
  if (keepIds.length === 0) return;
  // PostgREST not.in.() takes comma-separated values inside parens.
  // Wrap each id in URL-encoded double quotes so commas/parens inside
  // an id can't break the parser. Safe even for plain kebab-case ids.
  var idList = keepIds.map(function(id) {
    return '%22' + encodeURIComponent(id) + '%22';
  }).join(',');
  var url = supabaseUrl + '/rest/v1/events?id=not.in.(' + idList + ')';
  var response = UrlFetchApp.fetch(url, {
    method: 'delete',
    headers: {
      'apikey': serviceKey,
      'Authorization': 'Bearer ' + serviceKey,
      'Prefer': 'return=minimal',
    },
    muteHttpExceptions: true,
  });
  var code = response.getResponseCode();
  if (code !== 204 && code !== 200) {
    throw new Error('Diff delete failed with code ' + code + ': ' + response.getContentText());
  }
}
```

- [ ] **Step 4: Replace the `try { … }` block in `syncEventsToSupabase`**

In the same file, find the `try { … } catch (e) { … }` block inside `syncEventsToSupabase`. The current `try` body looks like this:

```javascript
  try {
    // CAVEAT: deleteAllEvents() fires Postgres ON DELETE SET NULL on every
    // exhibitions.event_id that references a deleted row, even though we
    // re-insert the same id milliseconds later. Until the next exhibitions
    // sync runs (up to 5 minutes), event-linked exhibitions appear orphaned
    // — Event Detail will show empty Venues/Exhibitions sections in that
    // window. Acceptable for Phase 1 (single rare event). Phase 2's filter
    // chip and pin recoloring will need an upsert+diff pattern to avoid
    // visible flicker.
    deleteAllEvents(supabaseUrl, serviceKey);
    insertEvents(uniqueRows, supabaseUrl, serviceKey);
```

Replace that block (the comment + the two function calls) with:

```javascript
  try {
    // Upsert via Prefer: resolution=merge-duplicates — Postgres updates
    // existing rows by id and inserts new ones. ON DELETE SET NULL never
    // fires for unchanged ids, so linked exhibitions stay linked.
    upsertEvents(uniqueRows, supabaseUrl, serviceKey);
    // Diff-delete only rows whose ids are no longer in the sheet. FK
    // fires once per genuinely-removed event (correct).
    var keepIds = uniqueRows.map(function(r) { return r.id; });
    diffDeleteEvents(keepIds, supabaseUrl, serviceKey);
```

Leave the `Logger.log({status: 'SUCCESS', ...})` and `catch` blocks unchanged.

- [ ] **Step 5: Update the file header comment**

At the top of `gas/SyncEvents.gs`, find the `GOOGLE SHEET LAYOUT:` block:

```javascript
 * GOOGLE SHEET LAYOUT:
 *   Row 1 = headers (lowercase snake_case matching Supabase column names)
 *   Data rows from row 2.
 *   Required headers: id, name_ko, name_en, location_label_ko,
 *                     location_label_en, start_date, end_date, brand_color
 *   Optional headers: description_ko, description_en, accent_color,
 *                     ticket_url, is_active
 */
```

Replace with:

```javascript
 * GOOGLE SHEET LAYOUT:
 *   Row 1 = headers (lowercase snake_case matching Supabase column names)
 *   Data rows from row 2.
 *   Required headers: id, name_ko, name_en, location_label_ko,
 *                     location_label_en, start_date, end_date, brand_color
 *   Optional headers: description_ko, description_en, accent_color,
 *                     ticket_url, is_active, cover_image_url
 *
 * COVER IMAGE CONVENTION:
 *   The cover_image_url column accepts either:
 *     a) A full HTTPS URL (e.g., https://example.com/hero.jpg) — used as-is
 *     b) A filename only (e.g., loop-lab-busan-hero.jpg) — resolved to:
 *        {SUPABASE_URL}/storage/v1/object/public/event-images/{filename}
 *   Upload images to the "event-images" bucket via Supabase dashboard.
 *
 * SYNC SEMANTICS (Phase 2a):
 *   Each run upserts every sheet row (Prefer: resolution=merge-duplicates)
 *   then diff-deletes Postgres rows whose ids are no longer in the sheet.
 *   Unchanged ids never trigger ON DELETE SET NULL on linked exhibitions.
 *   Renaming an event id (effectively delete + re-insert as a new id) WILL
 *   null exhibitions.event_id for the old id — operator must re-link those
 *   exhibition rows in the exhibitions sheet.
 */
```

- [ ] **Step 6: Verify the script parses (no automated test — manual check)**

The script can't be unit-tested locally; verification is via the Apps Script editor's lint and the next manual run in the operator workflow (Tasks 7 + 8 below). For now, eyeball the diff to confirm no syntax errors.

```bash
git diff gas/SyncEvents.gs | head -120
```

Expected: a clean diff containing only the changes from steps 1–5 above.

- [ ] **Step 7: Commit**

```bash
git add gas/SyncEvents.gs
git commit -m "feat(gas): upsert+diff-delete in SyncEvents and cover_image_url support"
```

---

### Task 6: Verify Android build runs end-to-end (no operator data yet)

**Files:** none (verification task)

This task confirms the code changes from Tasks 1–4 work when there is NO `cover_image_url` in the DB yet — i.e., the brand-color fallback works.

- [ ] **Step 1: Build and install on the connected Android device**

```bash
./gradlew :composeApp:installDebug
```

Expected: `Installed on 1 device.`

- [ ] **Step 2: Force-close and relaunch**

```bash
adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
```

- [ ] **Step 3: Visual inspection**

Open the Featured tab on the device. Confirm the Loop Lab Busan card:
- Renders at 140dp tall (slightly taller than before)
- Has the brand-color (blue) background showing
- The "→" arrow at the right edge is GONE
- Eyebrow "지금 진행 중 · ART EVENT" / name "루프랩 부산 2025" with "2025" in coral / dates + "부산 전역" still visible at the bottom
- Tapping the card still navigates to Event Detail

If any of those fail, stop and report. Don't proceed to Task 7 until this step passes.

- [ ] **Step 4: Capture and check logcat for unexpected errors**

```bash
adb logcat -d | grep -iE "FATAL|coil|AsyncImage" | tail -10
```

Expected: empty, or only Coil "no model" debug messages (since `cover_image_url` is null). No fatal errors.

- [ ] **Step 5: No commit** (verification only)

---

### Task 7: Operator workflow — apply Storage bucket and image (manual)

**Files:** none (operator steps)

This is a sign-off step performed by the human operator. Do NOT skip; later tasks depend on the bucket and image existing.

- [ ] **Step 1: Create the event-images Storage bucket**

In the Supabase dashboard:
1. Storage → New bucket
2. Name: `event-images`
3. Public bucket: ON
4. Create

Or via SQL (run in the SQL editor or via `supabase migration up` if you prefer to commit it):

```sql
INSERT INTO storage.buckets (id, name, public)
  VALUES ('event-images', 'event-images', true)
  ON CONFLICT (id) DO NOTHING;
```

- [ ] **Step 2: Upload a hero image**

Pick a Loop Lab Busan hero image (provided by the operator). Upload it to the `event-images` bucket via the Supabase dashboard. Note the filename (e.g., `loop-lab-busan-hero.jpg`).

- [ ] **Step 3: Verify public URL works**

```bash
curl -I "https://yhuhjxswjbrtmbpbrciq.supabase.co/storage/v1/object/public/event-images/<your-filename>"
```

Expected: `HTTP/2 200`. If 404, the upload didn't land or the bucket isn't public.

- [ ] **Step 4: Add filename to events sheet**

In the Loop Lab Busan row of the events Google Sheet, set the `cover_image_url` cell to the filename only (e.g., `loop-lab-busan-hero.jpg`). Don't paste the full URL — the script resolves it at sync time.

If the `cover_image_url` column header doesn't exist in the sheet yet, add it as a new header in row 1 (any column position; sync is header-driven).

- [ ] **Step 5: Re-deploy SyncEvents.gs**

Open the events Apps Script project. Replace the entire script with the contents of the local `gas/SyncEvents.gs` (after Task 5). Save (⌘S).

- [ ] **Step 6: Run sync manually + re-enable 5-min trigger**

In the Apps Script editor:
1. Select function `syncEventsToSupabase` from the dropdown → Run
2. Open Executions panel → confirm `status: SUCCESS`
3. Open Triggers panel → add a time-driven trigger: `syncEventsToSupabase`, Minutes timer, every 5 minutes (this trigger was deleted at the end of Phase 1 to work around C2; it's now safe to re-add)

- [ ] **Step 7: Verify the column landed in Postgres**

```bash
KEY=$(grep "^supabase.anon.key=" local.properties | cut -d= -f2-); URL="https://yhuhjxswjbrtmbpbrciq.supabase.co/rest/v1"; curl -s "$URL/events?select=id,cover_image_url" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
```

Expected: the Loop Lab Busan row now has `cover_image_url` set to the full Supabase Storage URL (the script resolved the filename).

- [ ] **Step 8: No commit** (manual operator action)

---

### Task 8: End-to-end smoke test (manual)

**Files:** none (verification only)

Final sign-off before merge. Walks the full smoke matrix from spec §10.

- [ ] **Step 1: Force-close and relaunch the app**

```bash
adb shell am force-stop com.gallr.app && adb shell monkey -p com.gallr.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
```

- [ ] **Step 2: Verify hero image renders**

On the device:
1. Open Featured tab → Loop Lab Busan card now shows the hero image as background
2. Text overlay (eyebrow, name with coral "2025", meta) remains legible thanks to the dark scrim
3. Tap card → Event Detail still loads correctly with About / Venues / Exhibitions sections
4. Back → return to Featured, card still showing image

- [ ] **Step 3: Verify FK orphan-window fix**

In the events Google Sheet:
1. Edit any non-id field on the Loop Lab Busan row (e.g. tweak the description, then revert)
2. Run `syncEventsToSupabase` from the Apps Script editor → confirm `status: SUCCESS`
3. Immediately verify exhibitions are still linked (NOT nulled out):

```bash
KEY=$(grep "^supabase.anon.key=" local.properties | cut -d= -f2-); URL="https://yhuhjxswjbrtmbpbrciq.supabase.co/rest/v1"; curl -s "$URL/exhibitions?select=id,name_ko&event_id=eq.loop-lab-busan-2025" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
```

Expected: the 2 linked exhibitions are still present immediately after the events sync (no orphan window). Pre-Phase-2a this query would have returned `[]` until the next exhibitions sync ran (up to 5 min later).

- [ ] **Step 4: Verify image-fallback path**

In the events sheet:
1. Clear the `cover_image_url` cell on the Loop Lab Busan row
2. Run `syncEventsToSupabase` → confirm `status: SUCCESS`
3. Force-close and relaunch the app
4. Verify Featured card now falls back to flat brand color (Phase 1 visual) — no broken-image placeholder, no crash

Then restore the cell value and re-sync so the image is back.

- [ ] **Step 5: Verify diff-delete path**

In the events sheet:
1. Add a throwaway test event row (id: `test-delete-me`, valid required fields, no exhibitions linked)
2. Run sync → verify `events` table has it via REST
3. Delete the row from the sheet
4. Run sync → verify `events` table no longer has it via REST:

```bash
KEY=$(grep "^supabase.anon.key=" local.properties | cut -d= -f2-); URL="https://yhuhjxswjbrtmbpbrciq.supabase.co/rest/v1"; curl -s "$URL/events?select=id&id=eq.test-delete-me" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
```

Expected: `[]`.

- [ ] **Step 6: No commit** (verification only)

---

## Self-Review

**Spec coverage check:**

- §5.1 (migration `014_add_event_cover_image_url.sql`) → Task 1
- §5.2 (event-images Storage bucket) → Task 7 step 1
- §6.1 (upsert + diff-delete rewrite) → Task 5 steps 3–4
- §6.2 (`cover_image_url` in `KNOWN_COLUMNS` and `buildRecord`) → Task 5 steps 1–2
- §6.3 (operator workflow + re-enable 5-min trigger) → Task 7 steps 4–6
- §7.1 (`Event.coverImageUrl` field) → Task 2
- §7.2 (`EventDto.coverImageUrl` and `toDomain` mapping) → Task 3
- §7.3 (one added test assertion) → Task 3 step 1
- §8.1 (UI restructure with image, scrim, bottom-anchored text) → Task 4
- §8.2 (behavior matrix: null/loading/loaded/error) → Task 4 implementation handles each via the `if (event.coverImageUrl != null)` gate and AsyncImage's own error tolerance; verified visually in Tasks 6 + 8
- §8.3 (remove "→" arrow) → Task 4 step 1 (the arrow is absent in the new file content)
- §9 (edge cases) → covered by AsyncImage's own behavior (null URL, error, network failure all land on the brand-color fallback) and by the diff-delete tests in Task 8 step 5
- §10 (testing) → Task 3 (unit), Task 6 (Android build smoke), Task 8 (full operator+app smoke)
- §11 (rollout) → Tasks land in rollout order (migration → bucket → script → sheet → app)

No spec section is missing a task.

**Placeholder scan:** none. Task 5 step 6 says "no automated test" but explicitly explains why and what verifies in its place (Tasks 7 + 8). Task 6 step 4's "or only Coil 'no model' debug messages" is a real expected output, not a hand-wave.

**Type consistency:**
- `Event.coverImageUrl: String?` — Task 2 declares; Task 3 maps; Task 4 reads via `event.coverImageUrl != null` and as `model = event.coverImageUrl`. Same name throughout.
- `EventDto.coverImageUrl: String?` — Task 3 declares with `@SerialName("cover_image_url")`; Task 3 maps in `toDomain()` via `coverImageUrl = coverImageUrl`. Matches.
- `cover_image_url` column name — Task 1 (SQL), Task 3 (`@SerialName`), Task 5 (`KNOWN_COLUMNS` + `buildRecord` branch). All consistent.
- `upsertEvents` and `diffDeleteEvents` signatures — defined in Task 5 step 3, called in Task 5 step 4. Match.
- `event-images` bucket name — Task 5 step 2 uses it in URL construction; Task 7 step 1 creates it under that exact name. Match.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-24-city-wide-event-phase2a.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
