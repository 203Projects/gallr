# 039 — Hide Past Reception Date Label

**Date:** 2026-05-11
**Priority:** P2
**Source:** `gallr_feature_request/260427-hide-past-reception-label-p1.md`

## Problem

When an exhibition has `receptionDate` and `openingTime` populated, the reception label on the exhibition detail page continues to render after the reception has already happened. The label reads like an upcoming event (e.g. "Opening Apr 5, 5 PM") even on April 10, which is misleading.

The current behavior lives in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ReceptionLabel.kt`. The branch at line 60 (`receptionDate < today`) returns a formatted past date instead of `null`.

## Desired Behavior

Hide the reception label (and its inline opening time) starting the calendar day after the reception date.

- **Show** when `today <= receptionDate` (subject to the existing future-window rules — labels for receptions more than one week away are already hidden by the current code and continue to be hidden).
- **Hide** when `today > receptionDate`.

Boundary is calendar-date based in the device's current time zone, not a 24-hour window. If the reception was April 5, the label disappears at the start of April 6 regardless of clock time.

## Scope

- Display-layer change only — no schema, DTO, repo, or sync pipeline changes.
- Single function modified: `receptionDateLabel()` in `ReceptionLabel.kt`.
- Existing future-window behavior (hide receptions more than one week away; special wording for today / tomorrow / this-week) is preserved unchanged.
- `openingTime` hiding is handled implicitly: the only call site (`ExhibitionDetailScreen.kt:175-181`) already wraps both the label and its inline time in a single `if (receptionLabel != null)` block, so returning `null` hides them together.
- Both `AppLanguage.KO` and `AppLanguage.EN` behave identically.
- No other detail-page UI elements (title, date range, status label, hours, contact) are affected.

## Out of Scope

- Showing labels for receptions more than one week in the future (current code hides these; spec does not request changing that).
- Any change to the card list, map, or filter row.
- Any change to the sync pipeline or KNOWN_COLUMNS in `gas/SyncExhibitions.gs`.

## The Change

In `ReceptionLabel.kt`, replace the existing "past but exhibition still running" branch with a `null` return:

```kotlin
// Before (lines 59-67)
receptionDate < today -> {
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", …)
    if (lang == AppLanguage.KO) {
        "오프닝 ${receptionDate.monthNumber}월 ${receptionDate.dayOfMonth}일$timeSuffix"
    } else {
        "Opening ${months[receptionDate.monthNumber - 1]} ${receptionDate.dayOfMonth}$timeSuffix"
    }
}

// After
receptionDate < today -> null
```

The function's doc comment ("Returns a human-readable label … or null when the label should be hidden") already documents this contract; the only consumer already handles `null` correctly. No call-site changes needed.

## Acceptance Criteria

Mapped from the source feature request:

1. Detail page rendered with `receptionDate = yesterday` and `closingDate` in the future → no reception label shown.
2. Detail page with `receptionDate = today` → label shown as "Opening today" (EN) / "오프닝 오늘" (KO).
3. Detail page with `receptionDate = tomorrow` → label shown as "Opening tomorrow" (EN) / "오프닝 내일" (KO).
4. Both language variants behave identically across all three cases.

## Test Plan

All tests added to `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ReceptionDateLabelTest.kt` (file already exists). `receptionDateLabel()` accepts an injectable `today` parameter, so tests are deterministic.

New cases:
- `yesterday + future closing date + EN → null`
- `yesterday + future closing date + KO → null`
- `yesterday + future closing date + non-blank openingTime → null` (verifies time hiding too)

Regression guards (ensure existing in-window behavior is unchanged):
- `today → "Opening today" / "오프닝 오늘"`
- `tomorrow → "Opening tomorrow" / "오프닝 내일"`
- `this Friday (current week) → "Opening Friday" / "오프닝 금요일"`
- `next Monday or later → null` (existing >1-week-future rule)
- `exhibition already ended (closingDate < today) → null` (existing terminal-state rule, unaffected by this change)

## Risk Assessment

- **Blast radius:** one function, one branch, one call site that already handles `null`. No serialization or wire-format changes.
- **Failure mode if regression slips through:** a label that should hide stays visible (the bug's current state) — same severity as today, no new failure mode introduced.
- **Reversibility:** trivial revert of a single branch.

## Verification After Implementation

- `./gradlew :shared:allTests` passes (or equivalent CMP test task).
- Manual: open an exhibition whose `receptionDate` is in the past and `closingDate` is in the future on both EN and KO; confirm no reception label or inline opening time renders.
