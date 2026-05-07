# Feature Specification: Fix Android System Bar Insets and Display Cutout Handling

**Feature Branch**: `018-fix-android-insets`
**Created**: 2026-03-26
**Status**: Draft
**Input**: User description: "On Android app, navigation bars are not considered — top or bottom content is covered by phone UI and navigation bar. Follow good Android practice to properly handle insets, cutouts, and general layout recommendations."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Content Not Obscured by Status Bar (Priority: P1)

As a user viewing any screen in the app, I can see all content without it being hidden behind the device's status bar at the top of the screen.

**Why this priority**: The status bar overlapping app content is the most visible and frequently encountered layout issue. Every screen is affected.

**Independent Test**: Open the app on a device with a visible status bar. Verify that the top app bar text, icons, and interactive elements are fully visible and not overlapping with the status bar clock, battery icon, or notification icons.

**Acceptance Scenarios**:

1. **Given** the app is open on any screen, **When** the user looks at the top of the screen, **Then** the app's top bar and content are fully visible below the status bar with no overlap
2. **Given** the app is open on a device with a notch or punch-hole camera, **When** the user views the app in portrait mode, **Then** no content is hidden behind the notch or camera cutout
3. **Given** the app is open on a device with a notch, **When** the user rotates to landscape mode, **Then** content is not obscured by the side notch area

---

### User Story 2 - Content Not Obscured by Navigation Bar (Priority: P2)

As a user interacting with the bottom of any screen, I can see and tap all content without it being hidden behind the device's system navigation bar (gesture bar or 3-button nav).

**Why this priority**: Bottom content and the app's bottom navigation bar being covered by the system navigation area makes key app navigation unusable on some devices.

**Independent Test**: Open the app on a device using gesture navigation (thin bar at bottom). Verify that the app's bottom navigation bar items are fully visible and tappable without conflicting with the system gesture area.

**Acceptance Scenarios**:

1. **Given** the app is open on a device with gesture navigation, **When** the user views the bottom navigation bar, **Then** all navigation items are fully visible and tappable above the system gesture area
2. **Given** the app is open on a device with 3-button navigation, **When** the user views the bottom of the screen, **Then** the app's bottom navigation bar does not overlap with the system back/home/recents buttons
3. **Given** the app shows a scrollable list, **When** the user scrolls to the last item, **Then** the final item is fully visible and not hidden behind the system navigation bar

---

### User Story 3 - Edge-to-Edge Visual Presentation (Priority: P3)

As a user, I see the app drawing its background colors and visual elements behind the system bars (status bar and navigation bar are transparent/translucent), giving a modern, immersive appearance consistent with current Android design standards.

**Why this priority**: While not a functional blocker, edge-to-edge presentation is the standard expectation on modern Android devices (Android 12+) and improves visual polish.

**Independent Test**: Open the app and verify that the status bar and navigation bar areas show the app's background color or content behind them (not opaque black/white system bars), while interactive content remains properly inset.

**Acceptance Scenarios**:

1. **Given** the app is open, **When** the user views the status bar area, **Then** the status bar is transparent and the app's background extends behind it
2. **Given** the app is open, **When** the user views the navigation bar area, **Then** the navigation bar is transparent/translucent and the app's visual style extends behind it
3. **Given** the app uses a light theme, **When** the user views the status bar, **Then** the status bar icons (clock, battery) are displayed in dark color for readability against the light background

---

### Edge Cases

- What happens on devices with extra-tall aspect ratios (e.g., 21:9)? Content should remain properly inset regardless of screen aspect ratio.
- What happens when the soft keyboard appears? Interactive fields should remain visible and not be hidden behind the keyboard.
- What happens on devices with both a notch and rounded screen corners? Content should stay within the safe display area.
- What happens on foldable devices in different postures? Layout should adapt to the available display area without content being obscured.
- What happens when the app is in multi-window (split-screen) mode? Insets should apply only to the edges that border system UI, not the split boundary.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST ensure all interactive content (buttons, text, inputs) is positioned within the safe display area, clear of system bars on all screens
- **FR-002**: The app MUST handle status bar insets so that top content is never overlapped by the status bar
- **FR-003**: The app MUST handle navigation bar insets so that bottom content is never overlapped by the system navigation bar (gesture or 3-button)
- **FR-004**: The app MUST handle display cutouts (notches, punch-hole cameras) so that content is not obscured in both portrait and landscape orientations
- **FR-005**: The app MUST draw its background behind system bars for a modern edge-to-edge appearance
- **FR-006**: The app MUST ensure status bar icon colors (light/dark) are appropriate for the app's background color to maintain readability
- **FR-007**: The app MUST handle keyboard (IME) appearance so that text input fields are not hidden behind the on-screen keyboard
- **FR-008**: The app MUST maintain correct inset handling across all supported screen configurations (different aspect ratios, multi-window mode, foldables)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of app screens show no content overlap with system bars when tested on devices with gesture navigation, 3-button navigation, and notched displays
- **SC-002**: All bottom navigation items are tappable without interference from the system navigation area on all tested device types
- **SC-003**: The app presents an edge-to-edge visual appearance with transparent system bars on devices running the target OS version
- **SC-004**: Text input fields remain visible and accessible when the on-screen keyboard is displayed
- **SC-005**: No layout regressions occur on devices without notches or cutouts

## Assumptions

- This fix applies to the Android app only; the iOS app's safe area handling is separate
- The app targets modern devices (minimum OS version as defined in the project) where edge-to-edge and inset APIs are available
- The app currently uses a single-activity architecture with a top bar and bottom navigation bar
- Dark theme inset handling (if dark theme exists) should also be correct, with light-colored status bar icons on dark backgrounds
- The fix should be applied at the app-level (activity/scaffold) rather than per-screen where possible, to avoid duplication
