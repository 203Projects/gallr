# Implementation Plan: Mobile Map UI Regressions

**Branch**: `shin/057-mobile-map-ui-regressions` | **Date**: 2026-08-12 | **Spec**: [spec.md](spec.md)
**Input**: Restore full-height iOS rendering and correct MapLibre pin visuals, grouping, and gesture arbitration.

## Summary

Make Compose the sole safe-area owner by extending its SwiftUI host edge-to-edge. Replace
zoom-dependent projected grouping with exact-coordinate groups. Render visual pins and labels as
clickable MapLibre GeoJSON layers so native map gesture arbitration handles pin taps and pinches;
retain pointer-transparent accessible actions where the map layer cannot expose Compose semantics.
The unresolved current-location redesign remains deferred.

## Technical Context

**Language/Version**: Kotlin 2.4.10, Compose Multiplatform 1.11.1, Swift
**Primary Dependencies**: Material 3 1.9.0, MapLibre Compose 0.9.0, SpatialK GeoJSON 0.3.0
**Storage**: N/A
**Testing**: kotlin-test common tests, Android host tests, XcodeBuildMCP simulator build/run and screenshots
**Target Platform**: Android API 26+ and iOS 16+
**Project Type**: Kotlin Multiplatform mobile app with a thin SwiftUI iOS host
**Performance Goals**: Native map pan/pinch remains smooth with the current Seoul catalogue
**Constraints**: Bilingual UI; exact-coordinate grouping; 44dp accessible actions; one orange role;
no location-marker redesign
**Scale/Scope**: App-wide iOS viewport plus the shared Map tab on Android and iOS

## Constitution Check — Before Phase 0

- **I Spec-first**: PASS — the two dated P1 request documents are captured as independently testable
  stories in `spec.md` before source edits.
- **II Test-first**: PASS — exact-coordinate grouping begins with updated failing common tests;
  platform host sizing and native gesture arbitration require simulator/manual verification.
- **III Simplicity/YAGNI**: PASS — reuse MapLibre 0.9.0 source/layer APIs already installed; no new
  dependency or map abstraction is introduced.
- **IV Incremental delivery**: PASS — viewport, grouping/visuals, and gesture ownership can each be
  validated independently.
- **V Observability**: PASS — no new significant operation or failure boundary is introduced.
- **VI Shared-first**: PASS — grouping and map UI remain in `composeApp/commonMain`; Swift changes
  only size the native host.

## Project Structure

### Documentation

```text
specs/057-mobile-map-ui-regressions/
├── data-model.md
├── plan.md
├── quickstart.md
├── research.md
├── spec.md
└── tasks.md
```

### Source code

```text
composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/SeoulDistrictMap.kt
composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/SeoulDistrictMapDataTest.kt
iosApp/iosApp/ContentView.swift
```

**Structure Decision**: This is a focused regression fix. Pure presentation grouping and shared UI
stay in the existing common map file; the iOS host receives one sizing modifier only.

## Constitution Check — After Phase 1

- **I Spec-first**: PASS — requirements, research decisions, model, and tasks are documented.
- **II Test-first**: PASS — grouping behavior has an automatable red/green path; non-automatable
  platform behavior has explicit before/after evidence tasks.
- **III Simplicity/YAGNI**: PASS — no new module, service, or dependency.
- **IV Incremental delivery**: PASS — each user story has an independent checkpoint.
- **V Observability**: PASS — no silent failure path is added.
- **VI Shared-first**: PASS — cross-platform map behavior remains common and the Swift host stays thin.

## Complexity Tracking

No constitution violations.
