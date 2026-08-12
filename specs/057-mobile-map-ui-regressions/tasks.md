# Tasks: Mobile Map UI Regressions

**Input**: Design documents from `/specs/057-mobile-map-ui-regressions/`
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`

## Phase 1: Investigation and baseline

- [x] T001 Reproduce the viewport regression on a modern iPhone simulator and capture baseline
  evidence.
- [x] T002 Compare the iOS host and dependency history to identify the double-inset trigger.
- [x] T003 Verify the installed MapLibre Compose 0.9.0 source/layer click APIs can own pin hit testing.

## Phase 2: User Story 1 — Use the full iPhone viewport (P1)

**Independent Test**: The iOS Compose canvas reaches both screen edges while shared UI respects each
safe area once.

- [x] T004 [US1] Extend `ComposeView` through the SwiftUI container safe area in
  `iosApp/iosApp/ContentView.swift`.
- [x] T005 [US1] Build/run on a modern iPhone simulator and capture Featured and Map screenshots.
- [x] T006 [US1] Confirm Android layout is unchanged through the Android host build/check gates.

## Phase 3: User Story 2 — Read and select unambiguous map pins (P1)

**Independent Test**: Exact coordinates form one counted pin, different coordinates stay separate,
and pin taps have no rectangular indication.

### Tests — write and observe failure first

- [x] T007 [US2] Replace projection-proximity assertions with exact-coordinate grouping tests in
  `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/SeoulDistrictMapDataTest.kt` and run the
  focused test to record the red state.

### Implementation

- [x] T008 [US2] Group validated pins before projection by exact `Position` in
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/SeoulDistrictMap.kt`.
- [x] T009 [US2] Replace stacked overlap glyphs with one pin and a numeric count while preserving
  all-saved orange logic and localized overlap-sheet behavior.
- [x] T010 [US2] Remove pin-only Material indication without changing zoom/recenter controls.
- [x] T011 [US2] Run the focused data tests to green.

## Phase 4: User Story 3 — Pinch the map through its markers (P1)

**Independent Test**: Five consecutive Android and iOS pin-origin pinches zoom without selecting.

- [x] T012 [US3] Move visual pin/label rendering to a MapLibre GeoJSON source and engine-owned
  layer click handlers in `SeoulDistrictMap.kt`.
- [x] T013 [US3] Preserve pointer-transparent localized accessibility actions for visual pin groups.
- [x] T014 [US3] Verify single taps, overlap-sheet selection, and pinch arbitration on Android. An
  API 36 emulator passed single-pin navigation, grouped-pin sheet selection, and five consecutive
  pin-origin multi-touch zoom attempts without opening pin content.
- [x] T015 [US3] Verify single taps, overlap-sheet selection, and pinch arbitration on iOS.
  Single-pin navigation and grouped-pin sheet selection passed, and the focused XCTest regression
  passed five pin-origin pinches with visible map changes and no opened overlap sheet.

## Phase 5: Verification and handoff

- [x] T016 Run `composeApp:ktlintCheck`, `composeApp:allTests`, Android lint/build gates, and the iOS
  simulator link/build gate.
- [x] T017 Run `git diff --check` and reconcile `spec.md`, `plan.md`, tasks, and observed behavior.
- [x] T018 Record the current-location redesign as deferred without changing `DESIGN.md` or marker code.

## Dependencies and execution order

- T004–T006 are independent of the map implementation.
- T007 must fail before T008 begins.
- T008–T011 establish deterministic groups before T012 consumes them.
- T012–T013 precede platform gesture checks T014–T015.
- T016–T018 follow all desired implementation tasks.
