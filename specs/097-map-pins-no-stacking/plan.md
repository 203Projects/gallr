# Implementation Plan: Map pins without stacking

**Branch**: `097-map-pins-no-stacking` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/097-map-pins-no-stacking/spec.md`

## Summary

From zoom 13 (about 3 km across a phone) the Map tab stops grouping pins: every exhibition gets its own logo pin and
cover, and pins that would overlap are moved onto a small brick lattice around their group's centre, so none cover
another while each stays near its place. Below zoom 13 the 28dp count pins stay, and tapping one now zooms the map in
to its members instead of opening the "이 주변 전시 N개" sheet, which is retired. The arrangement is a pure,
deterministic screen-space function beside today's grouping, tested in `commonTest`; the map layers, artwork and route
maps are unchanged.

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform, Compose Multiplatform (versions in `gradle/libs.versions.toml`)
**Primary Dependencies**: `org.maplibre.compose:maplibre-compose` 0.20.0 (present); no new dependency
**Storage**: N/A
**Testing**: `composeApp` commonTest (arrangement, camera target, labels); manual map checks on both platforms (the
map cannot be driven from Compose UI tests)
**Target Platform**: Android and iOS through `composeApp/commonMain`
**Project Type**: mobile app (KMP)
**Performance Goals**: arrangement for the full catalogue (about 200 pins) well under a frame, as today's per-frame
grouping
**Constraints**: deterministic arrangement (FR-009); free map stack only (FR-011); DESIGN.md Map pins
**Scale/Scope**: 1 new pure file, changes to `SeoulDistrictMap.kt`, removal of the overlap sheet and its helpers

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`4614da59`) before planning | PASS |
| II. Test-First | Arrangement, camera target and accessible labels are pure functions with failing tests first | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | One lattice and one merge loop; no force layout, leader lines or new dependency; the overlap sheet is removed, not kept "just in case" | PASS |
| IV. Incremental Delivery | US1 (spread when zoomed in) stands alone; US2 (count pin zooms) and US3 (labels) follow | PASS |
| V. Observability | No new failure path; no logging change | PASS |
| VI. Shared-First | No business rule moves: the arrangement is screen-pixel presentation geometry for one map view and stays beside today's grouping in `composeApp/commonMain` (research R3); domain data is unchanged in `shared` | PASS |
| VII. Mobile-First | App only; the public web has no exhibition pin map | PASS |

**Post-design re-check**: unchanged, all PASS.

## Project Structure

### Documentation (this feature)

```text
specs/097-map-pins-no-stacking/
├── plan.md, research.md (R1–R7), data-model.md, quickstart.md
├── contracts/map-pins.md
├── checklists/requirements.md
└── tasks.md             # /speckit.tasks
```

### Source Code (repository root)

```text
composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/
├── MapPinArrangement.kt       # new: arrangeMapPins, pinsOverlap, countPinCameraTarget, PIN_SPREAD_FROM_ZOOM
├── SeoulDistrictMap.kt        # use the arrangement; count pin tap zooms; target widths and labels; remove the sheet
└── MapNearbyPresentation.kt   # remove the overlap sheet helpers
composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/
├── MapPinArrangementTest.kt   # new
├── SeoulDistrictMapDataTest.kt
└── MapNearbyPresentationTest.kt   # drop the overlap sheet tests
DESIGN.md                      # Map pins: grouping only below zoom 13, spreading, count pin zooms in
```

**Structure Decision**: Presentation geometry in `composeApp/commonMain/ui/tabs/map`, next to the code it replaces.

## Risks

- **Per-frame cost**: arrangement runs on every rendered camera, like today's grouping. The merge loop is bounded by
  the number of components; with about 200 pins it stays small. If profiling shows a cost, limit the input to pins
  within a screen's margin of the view.
- **Threshold crossing during a pinch**: pins switch between grouped and spread as the zoom passes 13; accepted by the
  spec (edge case "crossing the neighbourhood threshold").
- **Overlap with #354 / #355 / 095**: those branches touch `SeoulDistrictMap.kt` (095 restyles map controls); conflicts
  are local and resolved at merge.

## Complexity Tracking

No violations to justify.
