# Implementation Plan: Fix iOS App Display Name

**Branch**: `017-fix-ios-app-name` | **Date**: 2026-03-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/017-fix-ios-app-name/spec.md`

## Summary

The iOS app displays "iosApp" on the device because `CFBundleName` resolves to the Xcode target name via `$(PRODUCT_NAME)` → `$(TARGET_NAME)` → "iosApp", and no `CFBundleDisplayName` is set. The fix adds `CFBundleDisplayName` with value "gallr" to the iOS Info.plist so the device-displayed name matches the App Store marketplace name.

## Technical Context

**Language/Version**: Swift 5.9 (iOS entry point), Kotlin 2.1.20 (KMP shared module — no changes needed)
**Primary Dependencies**: None — this is a plist configuration change only
**Storage**: N/A
**Testing**: Manual verification on iOS simulator (home screen, Spotlight, Settings)
**Target Platform**: iOS 15+
**Project Type**: Mobile app (KMP + Compose Multiplatform)
**Performance Goals**: N/A — configuration change only
**Constraints**: None
**Scale/Scope**: Single file change (Info.plist)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spec-First Development | PASS | Spec written and clarified before planning |
| II. Test-First (NON-NEGOTIABLE) | PASS | No testable code logic is being added — this is a static configuration value. Manual verification on simulator is the appropriate test method. No unit test applies. |
| III. Simplicity & YAGNI | PASS | Single key addition to Info.plist — minimal possible change |
| IV. Incremental Delivery | PASS | Single atomic change, independently deliverable |
| V. Observability | PASS | N/A — no runtime behavior change |
| VI. Shared-First Architecture | PASS | Change is in iOS platform config (Info.plist), not business logic — appropriate for platform module |

## Project Structure

### Documentation (this feature)

```text
specs/017-fix-ios-app-name/
├── plan.md              # This file
├── research.md          # Phase 0 output
└── spec.md              # Feature specification
```

### Source Code (repository root)

```text
iosApp/
└── iosApp/
    └── Info.plist        # Single file to modify — add CFBundleDisplayName
```

**Structure Decision**: This feature touches only `iosApp/iosApp/Info.plist`. No new files, directories, data models, or contracts are needed. The change is a single plist key addition.

## Implementation Approach

### What to change

**File**: `iosApp/iosApp/Info.plist`

**Change**: Add the `CFBundleDisplayName` key with value "gallr" immediately after the existing `CFBundleName` entry.

```xml
<key>CFBundleDisplayName</key>
<string>gallr</string>
```

### Why CFBundleDisplayName (not modifying PRODUCT_NAME)

- `CFBundleDisplayName` is the Apple-recommended key for controlling the user-visible app name on the home screen
- It takes precedence over `CFBundleName` for display purposes
- Changing `PRODUCT_NAME` or the target name would have broader side effects (bundle structure, binary name, framework references)
- This approach is the smallest, safest change

### Verification Steps

1. Build the app for an iOS simulator
2. Install and check the home screen icon label shows "gallr"
3. Search in Spotlight — confirm app appears as "gallr"
4. Check Settings app — confirm listed as "gallr"

## Complexity Tracking

No violations. Single plist key addition — simplest possible fix.
