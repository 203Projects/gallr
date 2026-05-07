# Implementation Plan: Dark Theme with System Setting Toggle

**Branch**: `014-dark-theme` | **Date**: 2026-03-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/014-dark-theme/spec.md`

## Summary

Add a dark theme variant to the gallr app with three user-selectable modes (Light, Dark, System). The dark palette inverts the existing monochrome design system while preserving the #FF5400 accent. Theme preference is persisted via DataStore and exposed as a new item in the existing settings gear dropdown. Default is "System" to respect the device setting.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP), Swift 5.9 (iOS entry point only)
**Primary Dependencies**: Compose Multiplatform 1.8.0, DataStore Preferences 1.1+, Material3
**Storage**: DataStore Preferences (existing — add theme preference key)
**Testing**: Shared module unit tests (kotlinx-coroutines-test)
**Target Platform**: Android 8+ (API 26), iOS 15+
**Project Type**: Mobile app (KMP cross-platform)
**Performance Goals**: Theme switch renders instantly (<100ms), no flash of wrong theme on launch
**Constraints**: Accent color #FF5400 must remain unchanged across themes; WCAG AA contrast ratios
**Scale/Scope**: ~5 files modified, 1 new enum, 1 new repository interface + impl

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First | PASS | spec.md completed and validated before planning |
| II. Test-First | PASS | ThemeRepository persistence will have unit tests in shared module |
| III. Simplicity & YAGNI | PASS | Minimal approach: one enum, one repository, one darkColorScheme function. No theme engine, no custom colors per-component |
| IV. Incremental Delivery | PASS | US1 (system theme) is independently deliverable; US2 (manual toggle) builds on US1; US3 (visual quality) is validated across both |
| V. Observability | PASS | Theme selection logged via println; no new crash vectors introduced |
| VI. Shared-First (KMP) | PASS | ThemeMode enum and ThemeRepository live in shared/. Only the Compose theme wiring (GallrTheme composable) lives in composeApp/ — this is UI, not business logic |

## Project Structure

### Documentation (this feature)

```text
specs/014-dark-theme/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── data/model/
│   └── ThemeMode.kt              # NEW: enum Light | Dark | System
├── repository/
│   ├── ThemeRepository.kt        # NEW: interface (observe + set)
│   └── ThemeRepositoryImpl.kt    # NEW: DataStore-backed impl

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/theme/
│   └── GallrColors.kt            # MODIFIED: add gallrDarkColorScheme()
│   └── GallrTheme.kt             # MODIFIED: accept ThemeMode, apply correct scheme
├── App.kt                        # MODIFIED: wire ThemeMode, add settings menu item
└── viewmodel/
    └── TabsViewModel.kt          # MODIFIED: expose theme state + toggle

composeApp/src/iosMain/kotlin/com/gallr/app/
└── MainViewController.kt         # MODIFIED: pass ThemeRepository

composeApp/src/androidMain/kotlin/com/gallr/app/
└── MainActivity.kt               # MODIFIED: pass ThemeRepository
```

**Structure Decision**: Follows existing KMP architecture. ThemeMode enum and ThemeRepository in shared/ (Principle VI). Theme UI wiring in composeApp/ where the Compose theme is already defined.

## Complexity Tracking

No violations. The implementation uses the same patterns already established (DataStore repository, enum model, ViewModel state flow).
