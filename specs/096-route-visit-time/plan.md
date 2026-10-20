# Implementation Plan: Choose when you're going

**Branch**: `096-route-visit-time` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/096-route-visit-time/spec.md`

## Summary

A visitor can tell the route composer and a public route's preview which day and time they are going, and every
stop is checked against that day and time. The rule lives in `shared`: `RouteEvaluator.evaluate` gains an optional
`RouteVisitTime` that plans the day as chosen (new `PlannedDayReason.CHOSEN`) and reuses the existing timeline and
verdicts; `visitTimeOptions` lists the next 14 days and 30-minute start times. The composer keeps the choice per
draft in a small DataStore value outside the draft, so a saved route never looks unsaved and the choice never leaves
the device; the preview keeps it for one viewing. The control is a summary row that opens a sheet of day and time
chips. The plain verdict wording (User Story 2) is already on the branch.

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform, Compose Multiplatform (versions in `gradle/libs.versions.toml`)
**Primary Dependencies**: kotlinx-datetime, DataStore preferences, Compose material3 (all present); no new dependency
**Storage**: one DataStore preference value on the device (`route_visit_time`)
**Testing**: `shared` commonTest (evaluator, options, repository via the existing in-memory DataStore test pattern),
`composeApp` commonTest (presentation, view models), `iosTest` Compose UI test for the sheet
**Target Platform**: Android and iOS through `shared` and `composeApp`
**Project Type**: mobile app (KMP)
**Performance Goals**: re-evaluation on choice is the existing in-memory computation (under a frame for 10 stops)
**Constraints**: "지금" unchanged (SC-004); choice never sent or shared (FR-008, FR-009); DESIGN.md (FR-010)
**Scale/Scope**: 1 evaluator parameter, 1 enum value, 2 new shared files, 1 repository, 2 view models, 2 screens,
1 sheet composable

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`1dc99a06`) before planning | PASS |
| II. Test-First | Evaluator, options and repository tests first in `shared`; presentation and view model tests first in `composeApp`; the wording change was test-first (`09cd7fdb`) | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | One optional parameter reuses the timeline; chips instead of platform pickers; no visit-length control | PASS |
| IV. Incremental Delivery | US2 (wording) shipped first; US1 composer then preview; US3 persistence | PASS |
| V. Observability | Storage reads log `route_visit_time_read_failed` with the exception type only; no new analytics | PASS |
| VI. Shared-First | Planning rule, options and storage in `shared`; view models only pass the choice | PASS |
| VII. Mobile-First | App only; the web page keeps its own line | PASS |

**Post-design re-check**: unchanged, all PASS.

## Project Structure

### Documentation (this feature)

```text
specs/096-route-visit-time/
├── plan.md, research.md (R1–R7), data-model.md, quickstart.md
├── contracts/visit-time-control.md
├── checklists/requirements.md
└── tasks.md             # /speckit.tasks
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── route/RouteVisitTime.kt                    # new: RouteVisitTime, VisitTimeOptions, visitTimeOptions()
├── route/RouteEvaluator.kt                    # visitTime parameter, CHOSEN planning
├── route/RouteStopVerdict.kt                  # PlannedDayReason.CHOSEN
├── repository/RouteVisitTimeRepository.kt     # new interface
└── repository/DataStoreRouteVisitTimeRepository.kt   # new implementation
shared/src/commonTest/kotlin/com/gallr/shared/
├── route/RouteEvaluatorTest.kt, route/RouteVisitTimeTest.kt
└── repository/DataStoreRouteVisitTimeRepositoryTest.kt
composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/route/VisitTime.kt                      # new: VisitTimeRow, VisitTimeSheet, labels
├── ui/route/composer/RouteComposerPresentation.kt   # reference line for CHOSEN
├── ui/route/composer/RouteComposerScreen.kt, ui/route/publicroutes/PublicRoutePreviewScreen.kt
├── viewmodel/PersonalRouteComposerViewModel.kt, viewmodel/PublicRoutesViewModel.kt
├── App.kt                                     # wires the repository
composeApp/src/iosTest/kotlin/com/gallr/app/ui/route/VisitTimeSheetTest.kt
androidApp/.../MainActivity.kt, composeApp/src/iosMain/.../MainViewController.kt  # construct the repository
DESIGN.md                                      # Route composer: visit time row and sheet
```

**Structure Decision**: Shared-first; composeApp for state and UI; the two entry points only construct the repository.

## Risks

- **Overlap with #354 / #355 / 095**: the composer and preview files change in those branches too. This branch is cut
  from `develop`; conflicts are additive (a row under the summary, new state fields) and resolved at merge.
- **Chip styling**: `GallrFilterChip` moves to `ui/components` in #351; until then the sheet uses a local chip with the
  same look, replaced when #351 lands.
- **Time zone**: all choices are Seoul time (`ROUTE_TIME_ZONE`), like the rest of route timing; a visitor abroad sees
  Seoul times, as today.

## Complexity Tracking

No violations to justify.
