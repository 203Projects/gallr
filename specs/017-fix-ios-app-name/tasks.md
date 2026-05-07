# Tasks: Fix iOS App Display Name

**Input**: Design documents from `/specs/017-fix-ios-app-name/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md

**Tests**: No test tasks — this is a static configuration change with no testable code logic. Verification is manual (simulator check).

**Organization**: Single user story, single task. No phases needed beyond implementation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1)
- Include exact file paths in descriptions

## Phase 1: User Story 1 - App Name Matches Marketplace Name (Priority: P1) 🎯 MVP

**Goal**: The iOS app displays "gallr" on the device home screen, Spotlight, and Settings — matching the App Store marketplace name.

**Independent Test**: Build and install on iOS simulator. Verify the home screen icon label reads "gallr", Spotlight search shows "gallr", and Settings lists "gallr".

### Implementation

- [x] T001 [US1] Add `CFBundleDisplayName` key with value "gallr" to `iosApp/iosApp/Info.plist` (insert immediately after the existing `CFBundleName` entry on line 17-18)

**Checkpoint**: Build the app for iOS simulator, install, and verify "gallr" appears as the app name on home screen, in Spotlight, and in Settings.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (US1)**: No dependencies — single atomic change

### Parallel Opportunities

- None — single task

---

## Implementation Strategy

### MVP (Complete)

1. Execute T001: Add `CFBundleDisplayName` to Info.plist
2. **VALIDATE**: Build for iOS simulator, verify display name
3. Submit to App Store review

---

## Notes

- Single plist key addition — simplest possible fix
- `CFBundleDisplayName` takes precedence over `CFBundleName` for the user-visible app name
- No localization needed — "gallr" is a brand name
- Commit after T001, then verify on simulator before submitting
