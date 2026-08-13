# Tasks: Readable Zoomed-Out Map Pins

## Phase 1 — Regression Contract

- [x] T001 Replace exact-coordinate-only grouping tests with the 1.8.0 projected grouping contract.
- [x] T002 Add coverage for deterministic transitive groups and centroid placement.
- [x] T003 Run the focused tests and record the expected pre-implementation failure.

## Phase 2 — Implementation

- [x] T004 Restore camera-dependent 16dp grouping without reintroducing clickable Compose visuals.
- [x] T005 Feed ordered visual groups and their projected centroids into MapLibre GeoJSON layers.
- [x] T006 Render a stacked counted glyph for groups and a single pin for singletons.
- [x] T007 Enable MapLibre collision placement for singleton titles.
- [x] T008 Preserve saved-color, click resolution, overlap-sheet, and accessibility behavior.

## Phase 3 — Verification

- [x] T009 Run focused and module-wide Compose tests and ktlint.
- [x] T010 Run Android lint and debug assembly.
- [x] T011 Build and run the iOS app on an iPhone simulator through the MapLibre SPM integration.
- [x] T012 Update task status and report the completed simulator visual verification.
