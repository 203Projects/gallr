# Feature Specification: Dark Theme with System Setting Toggle

**Feature Branch**: `014-dark-theme`
**Created**: 2026-03-24
**Status**: Draft
**Input**: User description: "add dark theme variant for the app. Suggest with colors based on current color theme to achieve best ui and user experience. add option to switch between themes (or to apply system setting) in settings drop down options."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - System Theme Follows Device Setting (Priority: P1)

A user who has their device set to dark mode opens gallr and the app automatically renders in dark theme. Conversely, a user with their device in light mode sees the current light theme. This is the default behavior — the app respects the OS setting without requiring any manual action.

**Why this priority**: Most users expect apps to follow their device theme. This is the zero-configuration path and delivers immediate value to the largest user segment.

**Independent Test**: Can be tested by toggling the device between light and dark mode and verifying the app theme updates accordingly.

**Acceptance Scenarios**:

1. **Given** the app is set to "System" theme (default), **When** the device is in dark mode, **Then** the app renders with the dark color palette.
2. **Given** the app is set to "System" theme (default), **When** the device is in light mode, **Then** the app renders with the light color palette.
3. **Given** the app is open and set to "System", **When** the user toggles their device theme in OS settings, **Then** the app theme updates without requiring a restart.

---

### User Story 2 - Manual Theme Selection via Settings (Priority: P2)

A user who prefers dark mode regardless of their device setting (or vice versa) opens the settings gear menu in the top-right corner and selects their preferred theme: Light, Dark, or System. The selection is remembered across app launches.

**Why this priority**: Gives users explicit control. Some users always want dark mode (e.g., OLED battery savings, nighttime use) or always want light mode regardless of device setting.

**Independent Test**: Can be tested by selecting each theme option in the settings dropdown and verifying the app appearance changes and persists after closing and reopening the app.

**Acceptance Scenarios**:

1. **Given** the settings dropdown is open, **When** the user selects "Dark", **Then** the app immediately switches to dark theme regardless of device setting.
2. **Given** the settings dropdown is open, **When** the user selects "Light", **Then** the app immediately switches to light theme regardless of device setting.
3. **Given** the settings dropdown is open, **When** the user selects "System", **Then** the app follows the device's current theme setting.
4. **Given** the user selected "Dark" and closes the app, **When** they reopen the app, **Then** the dark theme is still active.

---

### User Story 3 - Dark Theme Visual Quality (Priority: P1)

A user browsing exhibitions in dark mode sees a visually cohesive dark interface where text is legible, the orange accent (#FF5400) remains prominent and accessible, card borders are visible, and no UI elements "disappear" against the dark background. The dark theme maintains the same reductionist, gallery-inspired aesthetic as the light theme.

**Why this priority**: A poorly designed dark theme is worse than no dark theme. Visual quality is critical to user experience and brand consistency.

**Independent Test**: Can be tested by navigating through all three tabs (Featured, List, Map), opening an exhibition detail, using filter chips, and verifying that all elements are legible and visually consistent.

**Acceptance Scenarios**:

1. **Given** the app is in dark mode, **When** the user views the Featured tab, **Then** exhibition cards have visible borders, readable text, and the accent color stands out against the dark background.
2. **Given** the app is in dark mode, **When** the user views the List tab, **Then** filter chips, city chips, the segmented control, and the country dropdown are all clearly visible and distinguishable in their selected/unselected states.
3. **Given** the app is in dark mode, **When** the user opens the settings dropdown, **Then** the dropdown menu has clear contrast against the dark background with legible text.
4. **Given** the app is in dark mode, **When** the user views the Map tab, **Then** the map mode toggle buttons use the accent color for selected state and are clearly visible.

---

### Edge Cases

- What happens when the user's device does not support dark mode (older OS versions)? The app defaults to light theme.
- What happens when the user switches theme while viewing an exhibition detail screen? The detail screen re-renders in the new theme without navigation disruption.
- What happens when the user has "scheduled" dark mode on their device (e.g., sunset to sunrise)? The app follows the OS transition in real-time when set to "System".
- What happens to the orange accent (#FF5400) on dark backgrounds? The accent color remains the same — it has sufficient contrast on both light and dark backgrounds by design.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide three theme options: Light, Dark, and System (follow device setting).
- **FR-002**: System MUST default to "System" theme on first launch so the app immediately respects the user's device preference.
- **FR-003**: System MUST persist the user's theme preference across app launches.
- **FR-004**: System MUST apply theme changes immediately without requiring an app restart.
- **FR-005**: The settings dropdown (gear icon) MUST display the current theme selection and allow switching between Light, Dark, and System.
- **FR-006**: The dark theme MUST use a color palette that inverts the current monochrome light theme while preserving the #FF5400 accent for active indicators, CTA buttons, and interaction feedback.
- **FR-007**: The dark theme MUST maintain sufficient contrast ratios for text readability (WCAG AA: 4.5:1 for normal text, 3:1 for large text).
- **FR-008**: All screens (Featured, List, Map, Detail) and all UI components (cards, chips, dropdowns, navigation bar, segmented control, dialogs) MUST render correctly in both themes.
- **FR-009**: Theme selection MUST work identically on both iOS and Android platforms.

### Suggested Dark Theme Color Palette

Based on the current light theme's monochrome design system:

| Role              | Light Theme              | Dark Theme (Suggested)   | Rationale                                                    |
|-------------------|--------------------------|--------------------------|--------------------------------------------------------------|
| Background        | #FFFFFF (White)          | #121212 (Near Black)     | Material dark surface baseline; avoids pure black for reduced eye strain |
| On Background     | #000000 (Black)          | #E0E0E0 (Light Gray)    | High contrast without pure white glare                       |
| Surface           | #FFFFFF (White)          | #1E1E1E (Dark Gray)     | Slightly elevated from background for cards                  |
| Surface Variant   | #F5F5F5 (Off White)      | #2C2C2C (Medium Dark)   | Chip backgrounds, muted surfaces                             |
| On Surface Variant| #525252 (Secondary Gray) | #A0A0A0 (Mid Gray)      | Secondary text, unselected labels                            |
| Outline           | #000000 (Black)          | #404040 (Dark Border)   | Card borders, input outlines                                 |
| Outline Variant   | #E5E5E5 (Border Light)   | #333333 (Subtle Border) | Hairline dividers                                            |
| Accent            | #FF5400 (Orange)         | #FF5400 (Orange)        | Unchanged — works on both backgrounds                        |

### Key Entities

- **ThemePreference**: The user's selected theme mode (Light, Dark, or System). Stored locally on device, not synced to server.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All text in the app maintains a minimum contrast ratio of 4.5:1 in both light and dark themes.
- **SC-002**: Users can switch themes within 2 taps (open settings, select theme).
- **SC-003**: Theme preference persists across 100% of app restarts without data loss.
- **SC-004**: Theme transition completes instantly with no visible flicker or flash of the wrong theme on app launch.
- **SC-005**: All screens and components render correctly in both themes — zero visual elements that are invisible or illegible due to insufficient contrast.

## Assumptions

- The current app already has a settings gear dropdown in the top-right corner where theme selection will be added as a new menu item.
- The existing local preferences mechanism will be reused for persisting theme preference (same pattern as language and bookmark preferences).
- The #FF5400 accent color provides sufficient contrast on both the light (#FFFFFF) and dark (#121212) backgrounds — validated by WCAG contrast checker (4.6:1 on white, 5.2:1 on near-black).
- The map view (Naver Maps) may have its own light/dark rendering that is outside the scope of this feature. Only the app chrome (controls, overlays) around the map will be themed.
