# City-Wide Art Event — Phase 1: Foundation

**Date:** 2026-04-22
**Status:** Spec — pending implementation plan
**Source:** `/Users/hanshin/Downloads/260423-city-wide-biennale-p1.md` (v1 feature request) + `/Users/hanshin/Downloads/260423-city-wide-biennale-mockup.html` (v2 mockup, Loop Lab Busan)
**Phase:** 1 of 2

## 1. Background

City-wide art events (e.g., Loop Lab Busan, Frieze Seoul, Seoul Mediacity Biennale) span multiple independent venues across a city. They do not fit gallr's single-venue exhibition model. Users attending these events need a way to discover all participating venues in one place, understand the event context, and reach individual exhibitions — without losing sight of regular exhibition listings.

The full feature was decomposed into two phases. **Phase 1 (this spec)** is the foundation: data, end-to-end discoverability of a single active event via the Featured tab, and a dedicated Event Detail page. **Phase 2 (separate spec, later)** adds surface treatments across List and Map tabs (banner, filter chip, card edge/badge, pin recoloring, map FAB).

The launch event is **Loop Lab Busan 2025** (Apr 18 – May 10, 2025), city-wide across Busan. Its visual identity uses electric blue `#0099FF` and coral `#FF5C5C`.

## 2. Goals

- Users opening the Featured tab during an active event see a distinctive, branded card promoting the event
- Tapping the card opens an Event Detail page showing event context, participating venues, and a list of associated exhibitions
- Tapping an exhibition row from Event Detail opens the standard exhibition detail screen
- Operators can manage events through a new Google Sheet + GAS sync, parallel to the existing exhibitions pipeline, without touching the live exhibitions sync script

## 3. Non-Goals

The following are deferred to Phase 2:

- List-tab banner
- List filter chip ("Loop Lab" / event-specific)
- Exhibition card surface treatment (left-edge color line, corner badge)
- Map pin recoloring (event-brand color pins)
- Map FAB (persistent floating button on Map tab)
- Logo image assets (Phase 1 is text-only)
- Purchase Tickets UI surface (the data column exists; the link is not rendered in Phase 1)
- Multi-event simultaneous display (Phase 1 shows the first active event by `start_date`)
- Past-events archive

User-generated event submissions and in-venue floor maps remain out of scope entirely (per v1 spec §5).

## 4. Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Scope split | Two phases (foundation, then surface treatments) | Phase 1 is testable end-to-end on its own; Phase 2 reads what Phase 1 wrote |
| Nav entry point | Promoted card at the top of the Featured tab | Featured is gallr's editorial surface; reuses existing IA; doesn't preempt the Phase 2 List banner |
| Data pipeline | Separate `SyncEvents.gs` against its own spreadsheet | Keeps the working exhibitions sync untouched; smaller blast radius than extending the existing script |
| FK between exhibitions and events | Real `event_id` FK, nullable, `ON DELETE SET NULL` | Phase 2 filtering and pin recoloring will work correctly with no rework |
| Brand color storage | Two columns (`brand_color`, `accent_color`); Phase 1 uses only `brand_color` | Cheap to add now; saves a migration later |
| Logo asset | Deferred | Featured card and Detail header are text-only in Phase 1 |
| Bilingual event fields | `_ko`/`_en` pairs for `name`, `description`, `location_label` | Matches existing exhibition schema convention |
| FK enforcement during sync | Skip-and-log if `event_id` references a missing event | Mirrors existing missing-required-field handling in `SyncExhibitions.gs` |
| "Active" semantics | `is_active = true` AND `today BETWEEN start_date AND end_date` | `is_active` is an editorial kill-switch independent of dates |
| Active filter location | Computed client-side | Avoids server-time edge cases; keeps Supabase queries simple |

## 5. Data Model

### 5.1 New table: `events`

```sql
CREATE TABLE IF NOT EXISTS events (
  id                  TEXT PRIMARY KEY,
  name_ko             TEXT NOT NULL,
  name_en             TEXT NOT NULL,
  description_ko      TEXT NOT NULL DEFAULT '',
  description_en      TEXT NOT NULL DEFAULT '',
  location_label_ko   TEXT NOT NULL,
  location_label_en   TEXT NOT NULL,
  start_date          DATE NOT NULL,
  end_date            DATE NOT NULL,
  brand_color         TEXT NOT NULL,
  accent_color        TEXT,
  ticket_url          TEXT,
  is_active           BOOLEAN NOT NULL DEFAULT true,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE events ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Public read" ON events FOR SELECT USING (true);
```

`brand_color` and `accent_color` are stored as `#RRGGBB` strings.

### 5.2 Modification: `exhibitions.event_id`

```sql
ALTER TABLE exhibitions
  ADD COLUMN event_id TEXT REFERENCES events(id) ON DELETE SET NULL;

CREATE INDEX idx_exhibitions_event_id
  ON exhibitions(event_id) WHERE event_id IS NOT NULL;
```

The column is nullable. Regular (non-event) exhibitions leave it blank.

### 5.3 Active-event derivation

Pure client-side, computed in `EventRepositoryImpl.getActiveEvents()`:

```
event.isActive
  && today >= event.startDate
  && today <= event.endDate
```

Where `today` is the device's current date in `Asia/Seoul`.

## 6. Data Pipeline

### 6.1 New: `gas/SyncEvents.gs`

A near-clone of the existing `gas/SyncExhibitions.gs`, scoped to events. Conventions inherited:

- Header-driven (row 1 = headers, data from row 2; column order doesn't matter)
- Reads its own spreadsheet (operator-owned, separate from the exhibitions sheet)
- Service-role key from Apps Script Properties
- `onEdit` + 5-minute time-driven trigger
- Logs structured JSON status

Required headers (sync aborts if any are missing): `id`, `name_ko`, `name_en`, `location_label_ko`, `location_label_en`, `start_date`, `end_date`, `brand_color`.

Required per-row fields (row is skipped if empty): same as required headers.

Optional headers: `description_ko`, `description_en`, `accent_color`, `ticket_url`, `is_active` (defaults to `true` when blank).

### 6.2 Update: `gas/SyncExhibitions.gs`

One additive change: when a row's `event_id` cell is non-empty, look up the value against the `events` table before insert. If it doesn't exist, skip the row and log alongside existing skip reasons. Look up once per sync (one `GET /events?select=id`), cache for the duration of the run.

This keeps existing rows (no `event_id`) working unchanged.

### 6.3 Operator workflow

1. Add the event row to the events sheet
2. Wait for the events trigger to fire (or run manually)
3. Backfill `event_id` on the participating exhibition rows in the exhibitions sheet (which exhibitions belong to the event is an editorial judgment by the operator — not derivable from existing data)
4. Wait for the exhibitions trigger to fire

The "wait" step matters: backfilling `event_id` before the events row syncs causes a skip-and-log on the exhibition row.

## 7. KMP Domain Layer

### 7.1 New: `shared/.../model/Event.kt`

```kotlin
@Serializable
data class Event(
  val id: String,
  val nameKo: String,
  val nameEn: String,
  val descriptionKo: String,
  val descriptionEn: String,
  val locationLabelKo: String,
  val locationLabelEn: String,
  val startDate: LocalDate,
  val endDate: LocalDate,
  val brandColor: String,
  val accentColor: String?,
  val ticketUrl: String?,
  val isActive: Boolean,
)
```

### 7.2 Modification: `shared/.../model/Exhibition.kt`

Add nullable field: `val eventId: String? = null`.

### 7.3 New: `shared/.../repository/EventRepository.kt`

```kotlin
interface EventRepository {
    suspend fun getActiveEvents(): List<Event>
    suspend fun getEventById(id: String): Event?
    suspend fun getExhibitionsForEvent(id: String): List<Exhibition>
}
```

Implementation (`EventRepositoryImpl`) calls Supabase REST with the anon key, mirroring `ExhibitionRepositoryImpl`. Active-event filtering happens in `getActiveEvents()` client-side using the formula in §5.3.

## 8. UI

### 8.1 Featured tab — promoted card slot

**File:** existing `composeApp/.../ui/tabs/featured/FeaturedScreen.kt`
**New component:** `composeApp/.../ui/components/EventPromotionCard.kt`

Behavior:

- The Featured screen subscribes to `FeaturedViewModel.activeEvent: StateFlow<Event?>`
- When non-null, the promoted card renders at the top of the feed (above the existing content)
- When null, the slot collapses — Featured looks identical to today
- Tap navigates to `EventDetail(eventId = event.id)`

Visual:

- Full-width card, 0dp corner radius, ~120dp tall
- Background fills with `event.brandColor`
- Eyebrow row (small uppercase): localized "지금 진행 중 · Art Event" / "Now on · Art Event"
- Event name (large, bold, white). When `accentColor` is present, the **last whitespace-separated token** of the localized name renders in `accentColor` (e.g., "Loop Lab **BUSAN**")
- Meta row: dates + location label (current language)
- Decorative radial detail in the top-right corner (per v2 mockup); CSS-equivalent in Compose using a faint repeating pattern or omit if implementation cost is high — visual fidelity is not load-bearing in Phase 1

Multi-language: text fields swap `_ko`/`_en` based on `LanguageRepository` state, recomposing live.

### 8.2 Event Detail screen

**New file:** `composeApp/.../ui/event/EventDetailScreen.kt`
**New file:** `composeApp/.../ui/event/EventDetailViewModel.kt`

Layout (top to bottom):

1. **Top bar.** White background, 1px black bottom border. Left: back arrow. Label: "아트페어" / "Art Event" (generic, not event-specific — matches v1 spec §6).
2. **Branded header.** Full-bleed `brand_color` background, ~150dp tall, padded. Eyebrow ("City-Wide Art Event · Busan" / localized), event name (with optional accent-color span on trailing word), dates + location label. Anchored to bottom-left of the header.
3. **About section.** Section label "About" / "소개". Body: `event.description` (current language). Hidden if description is empty.
4. **Participating Venues section.** Section label "참여 갤러리" / "Participating Venues". Content: deduped, alphabetically sorted list of `venue_name` from exhibitions linked to this event. Rendered as chips with 1px `brand_color` border, 0dp radius. Hidden if no exhibitions.
5. **Exhibitions section.** Section label "전시" / "Exhibitions". List of compact rows (1px border, no image). Each row: small uppercase venue name, exhibition title, date range. Tap navigates to existing exhibition detail. Hidden if no exhibitions.

Scroll: the body below the top bar is a single vertical scroll container; the branded header scrolls with the content.

### 8.3 No-event state

Featured tab works exactly as today. Event Detail is unreachable (route still exists but no entry point surfaces it).

## 9. Navigation

Add one destination to the existing navigation graph:

- Route: `event_detail/{eventId}`
- Entry: from the Featured promoted card
- Exit: back arrow returns to Featured; tapping an exhibition row pushes the existing exhibition detail (which returns to Event Detail on back)

No deep-link support in Phase 1.

## 10. State Management

`FeaturedViewModel` gains:

```kotlin
val activeEvent: StateFlow<Event?>
```

Loaded once on init via `EventRepository.getActiveEvents().firstOrNull()`. No periodic refresh in Phase 1; pull-to-refresh on the Featured tab (if present today) re-fetches.

`EventDetailViewModel` exposes:

```kotlin
val event: StateFlow<Event?>
val venues: StateFlow<List<String>>
val exhibitions: StateFlow<List<Exhibition>>
val isLoading: StateFlow<Boolean>
val error: StateFlow<String?>
```

Loading and error patterns follow existing exhibition viewmodels.

## 11. Edge Cases

| Scenario | Behavior |
|----------|----------|
| No active event | Featured card hidden; Featured tab unchanged |
| Multiple active events | Show only the first by `start_date` ascending (deterministic). Phase 1 single-event assumption. |
| Event has zero linked exhibitions | Detail page renders; Venues and Exhibitions sections hidden; About still shown |
| `brand_color` malformed | Fall back to monochrome black; log a warning. Card still renders. |
| `accent_color` null | Event name renders entirely in white; no span treatment |
| Exhibition references missing `event_id` during sync | Skip and log (per §6.2) |
| Language switch on Detail or Featured | Recomposes immediately, swaps `_ko`/`_en` fields |
| Event date passes mid-session | Card disappears on next ViewModel re-init or pull-to-refresh; no live timer in Phase 1 |
| Network failure on Event Detail load | Standard gallr error state pattern (matches exhibition detail) |

## 12. Testing

**Contract / repository tests** (commonTest):

- `EventRepository.getActiveEvents()` filters correctly by `is_active` + date range
- `EventRepository.getExhibitionsForEvent(id)` returns only matching exhibitions
- Date-edge cases: event `end_date == today` is still active; `end_date == today - 1` is not

**ViewModel tests:**

- `FeaturedViewModel` exposes `activeEvent = null` when no active events
- `FeaturedViewModel` exposes the first event when multiple are active
- `EventDetailViewModel` loads event + venues + exhibitions; venues are deduped and sorted

**Manual smoke test:**

1. Create a test event row in the events sheet pointing at 2-3 existing exhibitions (backfill `event_id`)
2. Wait for both syncs to complete
3. Open the app on Featured → see promoted card
4. Tap → see Event Detail with header, About, Venues, Exhibitions
5. Tap an exhibition row → see standard exhibition detail
6. Back twice → return to Featured
7. Toggle language → text fields swap correctly on both screens
8. Set `is_active = false` in the sheet → after sync, Featured card disappears

## 13. Rollout

Order matters; each step is reversible up to step 4.

1. Apply the Supabase migration (new `events` table, `event_id` column on `exhibitions`, index)
2. Deploy `gas/SyncEvents.gs` to its own Apps Script project against the new events spreadsheet; install triggers
3. Update `gas/SyncExhibitions.gs` with the `event_id` validation step; redeploy
4. Operator adds Loop Lab Busan event row, waits for events sync
5. Operator backfills `event_id` on Loop Lab Busan exhibition rows, waits for exhibitions sync
6. Ship app build (iOS + Android) with Featured card + Event Detail screen + new repository

If anything fails at step 6, the data layer is harmless on older app builds (existing `Exhibition` deserialization tolerates the new `event_id` field as an unknown property).

## 14. Open Items Carried Forward

These two v1 open questions are deferred without resolution because they only impact Phase 2 surfaces:

- **Biennale vs. single-venue fair distinction.** Affects map pin clustering. No impact on Phase 1.
- **Logo asset shape.** Affects the Phase 2 map FAB. No impact on Phase 1.

Both will be re-opened when Phase 2 is brainstormed.
