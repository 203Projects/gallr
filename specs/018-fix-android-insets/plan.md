# Implementation Plan: Fix Android System Bar Insets and Display Cutout Handling

**Branch**: `018-fix-android-insets` | **Date**: 2026-03-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/018-fix-android-insets/spec.md`

## Summary

The Android app does not handle system bar insets or display cutouts, causing content to be obscured by the status bar (top) and navigation bar (bottom) on modern devices. The fix enables edge-to-edge mode via `enableEdgeToEdge()` in `MainActivity.kt`, adds display cutout handling in `AndroidManifest.xml`, and ensures the Scaffold's default inset handling properly pads content. The app already passes `innerPadding` from Scaffold to all screens, so no shared code changes are required.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP), composeApp Android target
**Primary Dependencies**: androidx.activity:activity-compose 1.9.3, Compose Multiplatform 1.8.0, Material3
**Storage**: N/A
**Testing**: Manual device/emulator testing across device configurations
**Target Platform**: Android (minSdk 26, targetSdk 35)
**Project Type**: Mobile app (KMP + Compose Multiplatform)
**Performance Goals**: N/A — layout configuration changes only
**Constraints**: Changes must be Android-specific; shared code should not be modified unless necessary
**Scale/Scope**: 2 files modified (MainActivity.kt, AndroidManifest.xml)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spec-First Development | PASS | Spec written and clarified before planning |
| II. Test-First (NON-NEGOTIABLE) | PASS | No testable business logic added — this is platform UI configuration. Manual device testing is the appropriate verification method. |
| III. Simplicity & YAGNI | PASS | Minimal changes: `enableEdgeToEdge()` in MainActivity + manifest attribute. Leverages Scaffold's existing inset handling. |
| IV. Incremental Delivery | PASS | 3 user stories, each independently testable |
| V. Observability | PASS | N/A — no runtime behavior to observe |
| VI. Shared-First Architecture | PASS | Changes are Android-specific platform configuration (MainActivity.kt, AndroidManifest.xml). No business logic added to platform module. Shared Scaffold code unchanged. |

## Project Structure

### Documentation (this feature)

```text
specs/018-fix-android-insets/
├── plan.md              # This file
├── research.md          # Phase 0 output
└── spec.md              # Feature specification
```

### Source Code (repository root)

```text
composeApp/
└── src/
    └── androidMain/
        ├── kotlin/com/gallr/app/
        │   └── MainActivity.kt          # Add enableEdgeToEdge() + theme-aware system bar styling
        └── AndroidManifest.xml          # Add display cutout mode attribute
```

**Structure Decision**: This feature modifies only Android platform files. No new files created. The shared Scaffold code in `App.kt` already handles `innerPadding` correctly and does not need changes.

## Implementation Approach

### File 1: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`

**Changes**:
1. Call `enableEdgeToEdge()` before `setContent {}` — this enables edge-to-edge mode with transparent system bars
2. Inside `setContent {}`, after the theme is determined, add a side-effect that re-calls `enableEdgeToEdge()` with appropriate `SystemBarStyle` for the current theme (light/dark) so status bar icons are readable

**Why this works**: Once edge-to-edge is enabled, the app draws behind system bars. The existing Scaffold automatically includes `WindowInsets.systemBars` in its `innerPadding`, so all screen content (which already applies `Modifier.padding(innerPadding)`) will be correctly offset from system bars. The top bar and bottom bar are positioned by Scaffold above/below the system bar areas.

### File 2: `composeApp/src/androidMain/AndroidManifest.xml`

**Changes**:
1. Add `android:windowLayoutInDisplayCutoutMode="shortEdges"` to the `<activity>` element — this tells Android to allow the app to render into cutout areas (notches, punch-holes) rather than letterboxing

### What does NOT need to change

- **App.kt** (shared): Scaffold already passes `innerPadding` to screens; default `contentWindowInsets` handles system bars
- **Screen composables**: All already apply `Modifier.padding(innerPadding)`
- **GallrNavigationBar.kt**: Positioned by Scaffold's `bottomBar` slot; insets handled automatically
- **GallrTheme.kt**: Theme colors are already Material3; no system bar color resources needed

## Complexity Tracking

No violations. Two files modified with minimal changes — simplest possible fix leveraging existing Scaffold infrastructure.
