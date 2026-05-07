# Data Model: Status Labels & Map Pin Filtering

**Feature**: 022-status-labels-map-filter
**Date**: 2026-04-02

## No Schema Changes

This feature requires **no changes** to the database schema, network DTOs, or domain models. The exhibition status is a derived/computed value, not a stored field.

## New Type: ExhibitionStatus (Enum)

A sealed/enum type representing the temporal status of an exhibition.

**Location**: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionStatus.kt`

### Values

| Value | Condition | Label (EN) | Label (KO) |
|-------|-----------|------------|------------|
| UPCOMING | `openingDate > today` | "Upcoming" | "오픈 예정" |
| CLOSING_SOON | `openingDate <= today` AND `closingDate >= today` AND `closingDate <= today + 3 days` | "Closing Soon" | "종료 예정" |
| ACTIVE | `openingDate <= today` AND `closingDate > today + 3 days` | (no label) | (no label) |
| ENDED | `closingDate < today` | (no label) | (no label) |

### Priority Rules

- UPCOMING takes precedence over CLOSING_SOON (if opening date is in the future, always show Upcoming regardless of closing date proximity)
- CLOSING_SOON and UPCOMING are mutually exclusive

### Pure Function Signature

```
exhibitionStatus(openingDate, closingDate, today) → ExhibitionStatus
```

- **Inputs**: Three `LocalDate` values (no Clock dependency — `today` is injected for testability)
- **Output**: One of the four enum values
- **Side effects**: None

### Localized Label Function

```
ExhibitionStatus.label(lang) → String?
```

- Returns the bilingual label string for UPCOMING and CLOSING_SOON
- Returns `null` for ACTIVE and ENDED (no label displayed)

## Existing Entities (unchanged)

### Exhibition
- `openingDate: LocalDate` — used as input to `exhibitionStatus()`
- `closingDate: LocalDate` — used as input to `exhibitionStatus()`
- No new fields added

### ExhibitionMapPin
- `openingDate: LocalDate` — used as input to `exhibitionStatus()`
- `closingDate: LocalDate` — used as input to `exhibitionStatus()` and for ended-pin filtering
- No new fields added
