# Implementation Plan: Opening Time Display

**Branch**: `025-opening-time-display` | **Date**: 2026-04-07 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/025-opening-time-display/spec.md`

## Summary

Add an optional free-text `opening_time` field to the exhibition data pipeline (Google Sheet → Supabase → KMP shared module → Compose UI). When present, the time is appended to the existing reception date label (e.g., "Opening today" becomes "Opening today, 5 PM"). No changes to existing label logic, positioning, or styling.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP shared + composeApp), Google Apps Script V8 (gas/), SQL (Supabase)
**Primary Dependencies**: kotlinx-serialization 1.7.3, kotlinx-datetime 0.6.1, Compose Multiplatform 1.8.0, Material3
**Storage**: Supabase Postgres (exhibitions table), Google Sheets (gallr_gallery_list)
**Testing**: kotlin.test (commonTest), manual QA for UI
**Target Platform**: Android (API 26+), iOS (Compose Multiplatform)
**Project Type**: mobile-app (KMP cross-platform)
**Performance Goals**: N/A — trivial text append, no measurable impact
**Constraints**: No time zone handling; free-text string displayed as-is
**Scale/Scope**: ~100-200 exhibitions, single new nullable TEXT column

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spec-First Development | PASS | spec.md written and clarified before planning |
| II. Test-First (NON-NEGOTIABLE) | PASS | Will add unit tests for updated receptionDateLabel() in shared/commonTest before implementation |
| III. Simplicity & YAGNI | PASS | Single nullable TEXT column, string concatenation only. No parsing, validation, or time zone logic. |
| IV. Incremental Delivery | PASS | 3 stories decomposed: data pipeline (P2), label with time (P1), fallback without time (P1). Each independently testable. |
| V. Observability | PASS | No new significant operations. Sync script already logs row processing. |
| VI. Shared-First Architecture (NON-NEGOTIABLE) | PASS | openingTime field added to shared/ DTOs and domain model. Label logic stays in composeApp/ (existing pattern for receptionDateLabel). No business logic in platform modules. |

**Post-Phase 1 Re-check**: All gates still pass. Label formatting logic remains in the same location as existing receptionDateLabel() — composeApp commonMain, consistent with existing pattern.

## Project Structure

### Documentation (this feature)

```text
specs/025-opening-time-display/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
shared/
└── src/
    ├── commonMain/kotlin/com/gallr/shared/data/
    │   ├── network/dto/ExhibitionDto.kt       # Add openingTime field
    │   └── model/Exhibition.kt                # Add openingTime field
    └── commonTest/kotlin/
        └── ReceptionDateLabelTest.kt          # New: test label with/without time

composeApp/
└── src/commonMain/kotlin/com/gallr/app/ui/
    └── detail/ExhibitionDetailScreen.kt       # Update receptionDateLabel()

gas/
└── SyncExhibitions.gs                         # Add opening_time to KNOWN_COLUMNS

supabase/
└── migrations/
    └── 005_add_opening_time.sql               # ALTER TABLE ADD COLUMN
```

**Structure Decision**: All changes follow existing file layout. No new modules, packages, or structural changes. The opening_time field flows through the established data pipeline: Sheet → GAS sync → Supabase → Ktor fetch → DTO → Domain model → UI label.

## Complexity Tracking

> No violations. All changes are minimal additions to existing patterns.
