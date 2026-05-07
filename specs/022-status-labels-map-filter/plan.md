# Implementation Plan: Status Labels & Map Pin Filtering

**Branch**: `022-status-labels-map-filter` | **Date**: 2026-04-02 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/022-status-labels-map-filter/spec.md`

## Summary

Add "Closing Soon" / "종료 예정" status badges alongside the existing "Upcoming" / "오픈 예정" badges across three surfaces (ExhibitionCard, MapScreen, ExhibitionDetailScreen), and filter ended exhibitions from both map pin StateFlows. A shared pure function in the `shared` module computes the exhibition status from dates, ensuring consistency across all UI surfaces.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP)
**Primary Dependencies**: Compose Multiplatform 1.8.0, Material3, kotlinx-datetime
**Storage**: N/A — no new persistence; status is computed from existing `openingDate` / `closingDate`
**Testing**: Unit tests for the shared status function (commonTest)
**Target Platform**: Android + iOS (Compose Multiplatform)
**Project Type**: Mobile app (KMP)
**Performance Goals**: Status computation is O(1) per exhibition — negligible overhead
**Constraints**: Pure date arithmetic; no network calls, no side effects
**Scale/Scope**: 4 files modified (ExhibitionCard, MapScreen, ExhibitionDetailScreen, TabsViewModel), 1 new file (status helper in shared module)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First Development | PASS | spec.md written and clarified before this plan |
| II. Test-First (NON-NEGOTIABLE) | PASS | Unit tests planned for shared status function before implementation |
| III. Simplicity & YAGNI | PASS | Single pure function, no new abstractions, no new dependencies |
| IV. Incremental Delivery | PASS | 4 independent user stories; each can be delivered and tested separately |
| V. Observability | PASS | No new operations requiring logging; status is a pure computation |
| VI. Shared-First Architecture (NON-NEGOTIABLE) | PASS | Status function lives in `shared/` module; UI surfaces only call it. No business logic in platform modules |

**Post-Phase 1 Re-check**: All gates still pass. The shared `ExhibitionStatus` enum and `exhibitionStatus()` function live in `shared/src/commonMain/`. UI composables consume the result. No business logic leaks into platform modules.

## Project Structure

### Documentation (this feature)

```text
specs/022-status-labels-map-filter/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/data/model/
├── Exhibition.kt                    # Existing — no changes needed
├── ExhibitionMapPin.kt              # Existing — no changes needed
└── ExhibitionStatus.kt              # NEW — shared status enum + helper function

shared/src/commonTest/kotlin/com/gallr/shared/data/model/
└── ExhibitionStatusTest.kt          # NEW — unit tests for status logic

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/components/ExhibitionCard.kt  # MODIFY — add closing-soon badge branch
├── ui/tabs/map/MapScreen.kt         # MODIFY — add status labels to dialogs
├── ui/detail/ExhibitionDetailScreen.kt  # MODIFY — add status label below date
└── viewmodel/TabsViewModel.kt       # MODIFY — filter ended pins from map flows
```

**Structure Decision**: KMP shared module for business logic (status computation), composeApp for UI changes. Follows existing project layout exactly. No new modules or packages needed.

## Complexity Tracking

> No violations. All changes follow existing patterns with minimal additions.
