# Implementation Plan: Readable Zoomed-Out Map Pins

**Branch**: `shin/058-map-pin-clustering` | **Date**: 2026-08-13 | **Spec**: [spec.md](./spec.md)

## Summary

Restore the 1.8.0 projected 16dp grouping rule in the shared Compose map while retaining the 1.8.1
MapLibre layer rendering that fixed pinch arbitration. Feed camera-dependent visual groups to the
existing GeoJSON layers, render groups as stacked counted pins, and allow MapLibre to suppress
colliding singleton captions.

## Technical Context

- **Language/Version**: Kotlin Multiplatform, Compose Multiplatform
- **Primary surface**: `composeApp/src/commonMain`
- **Tests**: `composeApp/src/commonTest`, Kotlin test
- **Constraints**: Shared UI only; no platform divergence; no new dependency; `DESIGN.md` tokens and
  saved-state accent rules remain authoritative.

## Constitution Check

- **Spec-First**: PASS — user stories and acceptance criteria are recorded in `spec.md` before code.
- **Test-First**: PASS when the projected grouping tests are changed and observed failing before the
  implementation is restored.
- **Simplicity/YAGNI**: PASS — reuse the proven 1.8.0 union-find grouping and current 1.8.1 layers.
- **Incremental Delivery**: PASS — the map behavior is independently testable and releasable.
- **Observability**: PASS — this deterministic UI transformation has no failure boundary requiring
  new operational logging.
- **Shared-First**: PASS — grouping and shared UI remain in `composeApp/commonMain`; no native host
  logic or business-domain behavior is introduced.

## Design

1. Project every valid exhibition pin with the current MapLibre camera projection.
2. Group projected candidates transitively when their marker anchors are at most 16dp apart.
3. Convert each visual group's projected centroid back to a map coordinate for MapLibre rendering.
4. Resolve layer clicks from the group's stable exhibition-id key to the full ordered group.
5. Render singleton and group layers separately: one pin plus collision-aware title for a singleton;
   two offset stacked pins with a separate count badge attached to the foreground pin for a group.
6. Keep Compose overlays limited to accessibility semantics, location feedback, and map controls.

## Complexity Tracking

No constitution violations. Camera-dependent GeoJSON updates are necessary to combine the requested
zoom-dependent grouping with MapLibre-owned gesture arbitration.

## Verification

- Focused projected-grouping unit tests, including transitive grouping.
- `./gradlew composeApp:ktlintCheck composeApp:testAndroidHostTest`
- `./gradlew androidApp:ktlintCheck androidApp:lintDebug androidApp:assembleDebug`
- iOS simulator framework link if the local MapLibre SPM artifact is resolved.
