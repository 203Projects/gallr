# Feature Specification: Fix iOS App Display Name

**Feature Branch**: `017-fix-ios-app-name`
**Created**: 2026-03-26
**Status**: Draft
**Input**: User description: "Fix iOS app display name to match App Store marketplace name (gallr)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - App Name Matches Marketplace Name on Device (Priority: P1)

As a user who downloads "gallr" from the App Store, I see the app labeled "gallr" on my home screen so I can easily identify the app I just installed.

**Why this priority**: This is the core fix — Apple is rejecting the app submission because the device name ("iosApp") does not match the marketplace name ("gallr"). Without this fix, the app cannot be published.

**Independent Test**: Install the app on an iOS device or simulator and verify the name displayed beneath the app icon on the home screen reads "gallr".

**Acceptance Scenarios**:

1. **Given** the app is installed on an iOS device, **When** the user views the home screen, **Then** the app icon label displays "gallr"
2. **Given** the app is installed on an iOS device, **When** the user searches in Spotlight, **Then** the app appears as "gallr"
3. **Given** the app is installed on an iOS device, **When** the user views the app in Settings, **Then** the app is listed as "gallr"

---

### Edge Cases

- What happens when the display name is shown in a constrained space (e.g., narrow grid)? "gallr" is 5 characters and fits well within the standard iOS icon label width.
- What happens if the user's device language changes? The display name should remain "gallr" regardless of locale (it is a brand name, not a translatable word).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST display "gallr" as its name on the iOS home screen
- **FR-002**: The app MUST display "gallr" in iOS Spotlight search results
- **FR-003**: The app MUST display "gallr" in the iOS Settings app list
- **FR-004**: The display name MUST remain "gallr" across all device locales

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The app passes Apple's App Store review for name consistency between marketplace listing and device display
- **SC-002**: 100% of iOS device locations where the app name appears (home screen, Spotlight, Settings) show "gallr"
- **SC-003**: The app name displays correctly on all supported iOS device types (iPhone and iPad)

## Assumptions

- The marketplace name "gallr" is final and will not change
- The display name does not need localization — "gallr" is a brand name used across all locales
- No changes to the Android app name are required (this fix is iOS-specific)
