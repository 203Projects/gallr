# City-Wide Art Event — Phase 2a: Sync Stability + Hero Image

**Date:** 2026-04-24
**Status:** Spec — pending implementation plan
**Phase:** 2a of a sub-phased Phase 2 (2a/2b/2c)
**Predecessor:** `docs/superpowers/specs/2026-04-22-city-wide-biennale-phase1-design.md` (Phase 1 — shipped on `030-city-wide-event-phase1`, PR #39)

## 1. Background

Phase 1 shipped end-to-end discoverability of a single active event (Loop Lab Busan 2025) via a Featured-tab promoted card and an Event Detail page. Two known issues were left as Phase 2 prerequisites:

1. **C2 caveat** — `gas/SyncEvents.gs` uses delete-all-then-insert-all on each sync. Postgres fires `ON DELETE SET NULL` on every linked exhibition during the delete phase, even though the same `id` is reinserted milliseconds later. Linked exhibitions stay orphaned until the next exhibitions sync (up to 5 min). Hit during Phase 1 smoke testing — became operationally painful enough that the events 5-min trigger had to be disabled to keep the launch event's linked exhibitions visible.
2. **Hero image** — the Featured event promotion card today is a flat brand-color box with white text. Compared to the surrounding `ExhibitionCard` (which renders cover images), the event card looks unfinished.

Phase 2 was further decomposed into three sub-phases. **Phase 2a** (this spec) is the foundation: a stability fix (so 2b/2c can rely on `event_id` staying populated) plus the smallest visible improvement that benefits today's launch (the hero image). **Phase 2b** (separate spec, later) covers List-tab surface treatments — banner, filter chip, exhibition card edge/badge styling. **Phase 2c** covers Map-tab surface treatments — pin recoloring, map FAB.

## 2. Goals

- An events sheet edit + sync no longer transiently nulls every linked exhibition's `event_id`. The events 5-min trigger can safely be re-enabled.
- The Featured event promotion card renders a hero image filling the card background, with text overlaid via a legibility scrim. Loss of network or absence of image URL falls back gracefully to the existing flat brand-color treatment.
- Operators manage the hero image via the existing events Google Sheet, using either a full URL or a bare filename in a Supabase Storage bucket — same convention as `exhibitions.cover_image_url`.

## 3. Non-Goals

The following remain deferred to later sub-phases or out of scope:

- List-tab banner, filter chip, exhibition card edge/badge styling (Phase 2b)
- Map pin recoloring, map FAB (Phase 2c)
- Logo image asset (Phase 2c — needed for FAB)
- Purchase Tickets UI surface (Phase 2 polish; data column already exists from Phase 1)
- Multi-event simultaneous display (Phase 2 polish — Phase 1 shows the first active event by `start_date`)
- Past-events archive (out of scope)

## 4. Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Sync pattern | Upsert (`Prefer: resolution=merge-duplicates`) + diff-based delete | Eliminates the orphan window. `events.id` is `PRIMARY KEY` so the conflict target is unambiguous. |
| Diff delete strategy | Single REST call: `DELETE /events?id=not.in.(<sheet_ids>)` | Atomic, no race; one round-trip; PostgREST supports `not.in.()` natively. |
| Image storage | New `event-images` Supabase Storage bucket, public-read | Mirrors the existing `exhibition-images` bucket pattern. Operators upload via the dashboard. |
| Image URL convention | Full HTTPS URL OR bare filename (resolved at sync time) | Identical to `exhibitions.cover_image_url`. Keeps operator workflow consistent. |
| Hero image rendering | Image as background, dark-gradient scrim, text overlaid bottom-left | Matches `ExhibitionCard` visual language. Brand color stays as fallback. |
| Image loading library | `coil3.compose.AsyncImage` | Already a project dependency (used by `ExhibitionCard`). |
| Loading state | No spinner — show brand color until image arrives | Card is small; spinner adds visual noise; brand-color "loading state" is indistinguishable from "no image" which is the correct fallback anyway. |
| Card height | Grow from ~90dp to ~140dp | Comparable to `ExhibitionCard`'s image+body layout. |
| `coverImageUrl` field placement | LAST constructor parameter on `Event` (default null) | Preserves backward compat for existing positional callers in tests and KMP code. |
| Trailing "→" arrow on card | Removed unconditionally | With a hero image and overlaid text, the arrow visually conflicts with the scrim. The whole card is tappable, which is the standard mobile affordance. |

## 5. Data Model

### 5.1 Migration: `014_add_event_cover_image_url.sql`

```sql
-- Migration: 014_add_event_cover_image_url.sql
-- Run via supabase migration up --linked.

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS cover_image_url TEXT;
```

Nullable, no backfill. Existing rows (Loop Lab Busan) read `NULL` and the app falls back to brand color until an operator populates it.

### 5.2 Storage bucket: `event-images`

Created via the Supabase dashboard or via SQL:

```sql
INSERT INTO storage.buckets (id, name, public)
  VALUES ('event-images', 'event-images', true)
  ON CONFLICT (id) DO NOTHING;
```

Public-read policy mirrors `exhibition-images` (`migrations/003_create_exhibition_images_bucket.sql`).

## 6. Data Pipeline: `gas/SyncEvents.gs` rewrite

The script's `syncEventsToSupabase()` keeps its outer shape (read sheet → validate → dedupe → write to Supabase → log JSON status). Only the write phase changes.

### 6.1 Replace delete-all-then-insert-all with upsert + diff-delete

**Remove:** `deleteAllEvents()` and `insertEvents()` calls.

**Replace with:**

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
  // PostgREST not.in.() takes comma-separated raw values. Wrap each in
  // URL-encoded double quotes so commas/parens inside an id can't break
  // the parser. Safe even for plain kebab-case ids like loop-lab-busan-2025.
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

Caller in `syncEventsToSupabase()`:

```javascript
try {
  upsertEvents(uniqueRows, supabaseUrl, serviceKey);
  diffDeleteEvents(uniqueRows.map(function(r) { return r.id; }), supabaseUrl, serviceKey);
  // Log SUCCESS as before
} catch (e) {
  // Log FAILURE as before
}
```

The C2-caveat block-comment near the old `deleteAllEvents` call is removed (no longer accurate).

### 6.2 Add `cover_image_url` to `KNOWN_COLUMNS` and `buildRecord`

Add `'cover_image_url'` to the `KNOWN_COLUMNS` list.

In `buildRecord`, add a branch that mirrors the exhibitions script's resolution:

```javascript
if (col === 'cover_image_url') {
  var url = String(raw || '').trim();
  if (!url) return; // omit; null in DB
  if (/^https?:\/\//i.test(url)) {
    record[col] = url;
  } else {
    var props = PropertiesService.getScriptProperties();
    var baseUrl = props.getProperty('SUPABASE_URL');
    record[col] = baseUrl + '/storage/v1/object/public/event-images/' + encodeURIComponent(url);
  }
  return;
}
```

### 6.3 Operator workflow

Unchanged from Phase 1, plus:

1. (Optional) Upload an event hero image to the `event-images` bucket via Supabase dashboard.
2. Set the `cover_image_url` cell in the events sheet to either the full image URL or just the filename.
3. Wait for the events sync to fire (or run manually).

The 5-min time-driven trigger on `syncEventsToSupabase` can now be safely re-enabled (it was disabled at the end of Phase 1 to work around C2).

## 7. KMP Domain Layer

### 7.1 Modification: `shared/.../model/Event.kt`

Add as the LAST constructor parameter:

```kotlin
val coverImageUrl: String? = null,
```

(After `isActive: Boolean` — preserves the order all existing callers use.)

### 7.2 Modification: `shared/.../data/network/dto/EventDto.kt`

Add as the LAST DTO field:

```kotlin
@SerialName("cover_image_url") val coverImageUrl: String? = null,
```

In `toDomain()`, add `coverImageUrl = coverImageUrl,` as the last property in the `Event(...)` constructor call.

### 7.3 Tests

Extend the existing `EventDto toDomain returns Event with parsed dates and defaults` test in `EventTest.kt` with:

```kotlin
kotlin.test.assertEquals(null, event.coverImageUrl)  // optional default
```

No new test files; just the one assertion. The existing 13 tests + this assertion all run via `:shared:testDebugUnitTest`.

## 8. UI: `EventPromotionCard` hero image

**File:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/EventPromotionCard.kt` (modify)

### 8.1 Layout change

Current structure:

```
Box(background = brand) {
  Row {
    Column(weight = 1f) { eyebrow, name, meta }
    Text("→")
  }
}
```

New structure (height grows from ~90dp to ~140dp):

```
Box(modifier.fillMaxWidth().height(140.dp).background(brand).clickable(onTap)) {
  // Layer 1: image fills box (transparent fallback while loading or on error)
  AsyncImage(
    model = event.coverImageUrl,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier.fillMaxSize(),
  )
  // Layer 2: bottom-to-top dark scrim for text legibility
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
          startY = 0f,
          endY = Float.POSITIVE_INFINITY,
        )
      )
  )
  // Layer 3: text content anchored bottom-left
  Column(
    modifier = Modifier
      .align(Alignment.BottomStart)
      .padding(14.dp),
  ) {
    Text(eyebrow, ...)
    Text(nameDisplay, ...)
    Text(meta, ...)
  }
}
```

Imports needed: `coil3.compose.AsyncImage`, `androidx.compose.ui.layout.ContentScale`, `androidx.compose.ui.graphics.Brush`.

### 8.2 Behavior matrix

| `event.coverImageUrl` state | What renders |
|------|------|
| null / empty | Brand-color background, scrim, text. Identical visual to today minus the trailing "→" arrow. |
| Loading | Brand-color background visible through transparent AsyncImage state, then image fades in. No spinner. |
| Loaded successfully | Image fills card, scrim ensures text contrast, text overlaid. |
| Network error / 404 | AsyncImage stays transparent; brand-color shows; text stays legible. Coil logs the error; no crash. |

### 8.3 Other changes

- The trailing "→" arrow Text is removed. With a hero image and overlaid text, the arrow visually conflicts with the scrim and was a known iOS-26-simulator emoji-fallback risk. The whole card remains tappable.
- `Color.White.copy(alpha = 0.75f)` and `Color.White.copy(alpha = 0.85f)` calls remain — they were the iOS-26 simulator scapegoat earlier, but verified safe on iOS 18.6 and Android.
- `AnnotatedString` accent-color span on the last token of the name is preserved (also verified safe on iOS 18.6).

## 9. Edge Cases

| Scenario | Behavior |
|----------|----------|
| New event added to sheet | Upsert inserts; diff-delete preserves it |
| Existing event edited (any field, including `cover_image_url`) | Upsert updates in place; FK on linked exhibitions never fires |
| Event removed from sheet | Upsert no-ops; diff-delete drops the row; FK fires once on linked exhibitions (correct — they're now genuinely unlinked) |
| Sheet has 0 valid rows | Same safety check as today: `SKIPPED` log, no destructive write |
| Operator types same `id` for two different events | Same dedupe-by-id behavior as today; second occurrence skipped with log |
| Operator changes an existing `id` (effectively rename) | New id is upserted; old id is diff-deleted; FK on old-id's linked exhibitions fires. Operator must re-link those rows. Documented in script header comment. |
| `cover_image_url` is bare filename, but file isn't uploaded yet | App's `AsyncImage` 404s; brand-color shows. No crash. |
| `cover_image_url` is a malformed URL string | Coil emits an error; brand-color shows. No crash. |
| Network unavailable during image fetch | Brand-color shows; Coil retries on next composition. |
| Multiple sync runs while operator is editing | Upsert is idempotent; final state always reflects the most recent successful sync. No orphan window. |

## 10. Testing

**Unit tests (commonTest):**
- One added assertion in `EventTest.kt` confirming `coverImageUrl` defaults to null in `EventDto.toDomain()`.

**GAS smoke (manual):**
1. Add a non-event row's `cover_image_url` to a value, run `syncToSupabase` on the *exhibitions* script — confirm `event_id`-linked exhibitions remain linked across multiple events syncs (no flicker).
2. Edit any field on the events sheet, run `syncEventsToSupabase` 3x in a row — verify Loop Lab Busan exhibitions stay linked (`event_id` not nulled) throughout.
3. Add a new event row, sync — verify it appears in `events` table.
4. Delete the new event row from the sheet, sync — verify it's removed from `events` table; verify any linked exhibitions get `event_id` SET NULL (only this once).

**App smoke (manual):**
1. Set `cover_image_url` to a bare filename (e.g. `loop-lab-busan-hero.jpg`); upload that image to the `event-images` bucket; sync; relaunch app — Featured card shows the hero image with overlaid text.
2. Set `cover_image_url` to empty in the sheet; sync; relaunch — Featured card falls back to flat brand color (Phase 1 visual).
3. Set `cover_image_url` to a deliberately broken URL; sync; relaunch — card falls back to brand color, no crash.

## 11. Rollout

Order matters. Each step is reversible up to step 6.

1. Apply migration `014_add_event_cover_image_url.sql` via `supabase migration up --linked`.
2. Create `event-images` Storage bucket (dashboard or SQL); apply public-read policy.
3. Upload Loop Lab Busan hero image to the bucket.
4. Deploy updated `gas/SyncEvents.gs` to the events Apps Script project. Re-enable the 5-min time-driven trigger (now safe with upsert).
5. Add the image filename to the Loop Lab Busan row's `cover_image_url` cell. Wait for sync.
6. Build and ship the app (Android first; iOS after the simulator-version sort-out from Phase 1's known-issues list).
