# Investigation: Exhibition Hidden When Linked Event Is Inactive (P1)

**Date:** 2026-06-08
**Source report:** `260608-orphaned-event-id-exhibition-hidden-p1.md`
**Verdict:** **Does not reproduce.** No hiding mechanism exists in code or database.
`event_id` is already treated as inert provenance metadata everywhere. Closed with a
regression test that locks in the verified-correct behavior.

---

## What was claimed

An exhibition with a non-null `event_id` referencing an **inactive** event
(`is_active = FALSE`) disappears from every tab (List, Featured, Map). Suspected
root cause: a dashboard-applied RLS policy or view on `exhibitions` joining
`events.is_active`.

## What we found (evidence)

Systematic debugging across every layer. The entire path preserves orphaned-event
exhibitions:

| Layer | Checked | Result |
|---|---|---|
| **Live DB via anon key** (the app's exact path) | Query exhibitions linked to the *inactive* event `loop-lab-busan-2026` | **All 3 rows returned.** No server-side hiding. |
| **Live DB — `events`** | anon read of inactive event | Inactive event itself is readable; `events` is not `is_active`-gated |
| **RLS migration 001** | `exhibitions` SELECT policy | `USING (true)` — pure public read, no join |
| **Hidden views** | probed `exhibitions_view`, `active_exhibitions`, `exhibitions_with_events`, `v_exhibitions` | all HTTP 404 — no view |
| **`ExhibitionApiClient`** | `fetchExhibitions` / `fetchFeatured` | flat `exhibitions?select=*`; no join, no event filter |
| **`ExhibitionDto.toDomain()`** | null-drop logic | returns null **only** on unparseable dates; copies `eventId` through verbatim |
| **`TabsViewModel.filteredExhibitions`** | the only `event_id` use | `eventOnly` chip only — guarded (`activeEventIds.isEmpty()` short-circuit) and auto-cleared when the active set empties |
| **`Exhibition.toMapPin`** | `mapNotNull` drop | returns null **only** on missing lat/lng; a missing event yields a null badge color, not a dropped pin |
| **`FilterState.matches()`** | predicate | does not reference `event_id` at all |
| **`SyncExhibitions.gs`** | FK validation | uses **service key** (bypasses RLS), null-safe on non-200 → inactive events still pass validation; row always syncs |

### Likely origin of the report (misattribution)

With today = 2026-06-08, of the 3 exhibitions linked to inactive event
`loop-lab-busan-2026`:

- `디지털 서브컬처` (closes 2026-06-28) — **visible** (List + Map) ✓
- `Seed Stories` (closes 2026-08-30) — **visible** (List + Map) ✓
- `The Shape of Re-entry` (closed 2026-05-31) — **hidden, correctly** — the app's
  unrelated `closingDate >= today` "hide ended exhibitions" rule, **not** event activity.

An observer seeing an inactive-event exhibition disappear could reasonably—but
incorrectly—attribute it to the event being inactive. The terminology in the report
(`is_pinned`) also does not match the schema; there is no `is_pinned` column.

## Change made

No code or DB fix required. Added a regression test mandated by the report
("In all cases: Add a unit test"):

- `composeApp/.../TabsViewModelActiveEventsTest.kt` →
  `orphaned_event_id_stays_visible_when_event_only_off` — asserts an exhibition whose
  `event_id` is not in the active set stays in `filteredExhibitions` when
  `eventOnly = false`, alongside active-event and unlinked exhibitions. Fails if a
  future change reintroduces an event-activity visibility gate.
