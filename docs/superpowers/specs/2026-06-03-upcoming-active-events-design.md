# Surface upcoming active events with a date-aware status

**Date:** 2026-06-03
**Branch:** `044-events-feature-fixes-p1` (folds into PR #79, v1.6.4)
**Area:** gallr-events (`Event` model, `EventRepositoryImpl`, `EventPromotionCard`, `EventListBanner`)

## Problem

An event surfaces its chrome (promotion card on Featured, banner on List, FAB on
Map, ribbon on member exhibition cards) only when **today is within
`[start_date, end_date]` AND `is_active = true`**. This hides an event that an
admin has marked `is_active = true` but whose `start_date` is still in the
future — so admins cannot promote an upcoming event ahead of its opening. The
desired behavior: an `is_active = true` event should be visible **before** its
start date too.

## Decisions

1. **Visibility rule** — drop the `start_date` lower bound; keep the upper bound
   so ended events still auto-retire without a manual `is_active` flip.
   Surface when `is_active = true AND today <= end_date`.
2. **Date-aware eyebrow** — a future event must not claim "NOW ON". Before
   `start_date` the eyebrow reads `예정` / `Upcoming`; from `start_date`
   onward it reads `지금 진행 중` / `NOW ON`.
3. **Eyebrow scope** — `EventPromotionCard` (Featured) and `EventListBanner`
   (List). The detail-screen banner shows location only (set earlier in this
   branch's Fix 2) and has no "NOW ON" string to make date-aware.
4. **Packaging** — same branch / PR #79 / v1.6.4.

## Design

### `Event` model (single source of truth)

Replace `isActiveOn` (the name now misleads — it no longer means "live") with
`isVisibleOn`, and add a phase + eyebrow helper so both banners stay consistent:

```kotlin
enum class EventPhase { UPCOMING, LIVE }

fun isVisibleOn(today: LocalDate): Boolean = isActive && today <= endDate

fun phaseOn(today: LocalDate): EventPhase =
    if (today < startDate) EventPhase.UPCOMING else EventPhase.LIVE

fun statusEyebrow(today: LocalDate, lang: AppLanguage): String = when (phaseOn(today)) {
    EventPhase.UPCOMING -> if (lang == AppLanguage.KO) "예정" else "Upcoming"
    EventPhase.LIVE      -> if (lang == AppLanguage.KO) "지금 진행 중" else "NOW ON"
}
```

`phaseOn` only distinguishes UPCOMING vs LIVE because an ENDED event is never
surfaced (`isVisibleOn` already excludes `today > end_date`), so no display path
ever sees one.

### `EventRepositoryImpl`

`getActiveEvents()` filters `.filter { it.isVisibleOn(today) }` (was
`isActiveOn`). `today` already comes from
`Clock.System.todayIn(TimeZone.of("Asia/Seoul"))`. Sorting stays
`sortedBy { startDate }`.

### Banners

Both compute `today` with the same Seoul timezone (so the eyebrow and the
visibility gate agree on the day boundary) and call
`event.statusEyebrow(today, lang)`:

- `EventPromotionCard` — `val eyebrow = event.statusEyebrow(today, lang)`
  (replaces the hardcoded `지금 진행 중` / `NOW ON`).
- `EventListBanner` — `val nowOn = event.statusEyebrow(today, lang)`
  (replaces the hardcoded string; the `"  ·  $nowOn"` layout is unchanged).

## Testing

- `EventTest`:
  - `isVisibleOn` — future start → true; ongoing → true; ended → false;
    `is_active = false` → false (even when in range).
  - `phaseOn` / `statusEyebrow` — day before start → UPCOMING / `예정` /
    `Upcoming`; on start date → LIVE / `지금 진행 중` / `NOW ON`; after start
    → LIVE. Both languages.
- `EventRepositoryTest`:
  - Flip `excludes event whose start_date is after today` → **includes** it.
  - Keep `excludes event whose end_date is before today` and
    `excludes event with isActive false even when in date range`.
  - Rename any `isActiveOn` references to `isVisibleOn`.

## Emergent fix (found during on-device verification)

Surfacing the future event let it be opened on-device for the first time, which
exposed a pre-existing bug: the `EventDetailScreen` top bar didn't reserve the
status-bar / camera-cutout inset, so the back arrow and "EVENT" label drew under
the system UI. Fixed by adding `statusBarsPadding()` to the top-bar Row (the
background still paints full-bleed behind the status bar). Same class as the
`043-android-editor-screen-fix`.

## Label-fixes follow-up (260603-events-ui-label-fixes-p1)

A follow-up spec refined the wording and the detail eyebrow:

- **Eyebrow wording:** upcoming reads `예정` / `Upcoming` (was `곧 시작` /
  `COMING SOON`) to match the app's status vocabulary
  (`ExhibitionStatus.UPCOMING` → `오픈 예정`). `Event.statusEyebrow` updated;
  used by `EventPromotionCard` + `EventListBanner`.
- **Detail eyebrow:** the prior P1 fix left the detail banner eyebrow showing
  the venue, which is also on the date row — a duplicate. The eyebrow now shows
  `Event.statusLabel(today, lang)` (`예정`/`Upcoming` or `진행 중`/`NOW ON`),
  and the venue stays only on the date row. `statusLabel` uses the shorter
  `진행 중` active form (vs `statusEyebrow`'s `지금 진행 중`) because the detail
  eyebrow is a terse single line. The follow-up spec's literal text re-added the
  removed `도시 전역 · ART EVENT` prefix; we kept it removed (status-label-only)
  to honor the earlier fix.

## Out of scope

- No DB / DTO / Apps Script change — `is_active`, `start_date`, `end_date`
  already exist and carry the needed information.
- Detail-screen eyebrow (location-only) is unchanged.
- "OPENS {date}" wording was considered and declined in favor of the simpler
  `COMING SOON` / `곧 시작` (later changed to `Upcoming` / `예정` per the
  2026-06-03 label-fixes spec, to match the app's status vocabulary). The detail
  banner eyebrow now shows `Event.statusLabel` (`예정`/`진행 중`) instead of the
  duplicated venue — see the label-fixes note below.
