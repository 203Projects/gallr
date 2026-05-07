# Feature Specification: Comprehensive UI Improvements and Polish

**Feature Branch**: `016-ui-improvements`
**Created**: 2026-03-24
**Status**: Draft
**Input**: User description: "Comprehensive UI improvements: pull-to-refresh, image loading placeholders, back gesture support, search bar, skeleton loading, tab transitions, settings indicators, enhanced empty states, localized dates, accessibility fixes, error specificity, and contrast audit."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pull-to-Refresh and Data Loading (Priority: P1)

A user opens the app and sees exhibitions load with a visual skeleton placeholder (ghost cards that pulse) instead of a thin progress line. If the data feels stale, the user pulls down on any tab to trigger a refresh with a visible loading indicator. When a cover image is loading on the detail screen, a placeholder maintains the layout instead of blank space.

**Why this priority**: Loading states are the first thing users see. Poor loading UX makes the app feel broken or slow. Pull-to-refresh is an expected mobile interaction pattern — its absence confuses users.

**Independent Test**: Open the app on a slow network. Verify skeleton cards appear during loading. Pull down on Featured tab to refresh. Open a detail screen — verify image placeholder shows before image loads.

**Acceptance Scenarios**:

1. **Given** the app is loading exhibition data, **When** the user views any tab, **Then** skeleton placeholder cards are shown instead of a blank screen.
2. **Given** exhibitions are displayed, **When** the user pulls down on the Featured tab, **Then** a refresh indicator appears and data reloads.
3. **Given** exhibitions are displayed, **When** the user pulls down on the List tab, **Then** a refresh indicator appears and data reloads.
4. **Given** the user opens an exhibition detail with a cover image, **When** the image is loading, **Then** a gray placeholder maintains the image area's aspect ratio until the image loads.
5. **Given** a pull-to-refresh completes, **When** new data arrives, **Then** the list updates smoothly without flicker.

---

### User Story 2 - Back Gesture and Screen Transitions (Priority: P1)

A user taps an exhibition card and sees a smooth transition into the detail screen. To return, they can swipe back from the left edge (iOS) or use the system back gesture (Android), in addition to tapping the back button. Switching between tabs has a subtle fade transition instead of a hard cut.

**Why this priority**: Gesture navigation is a fundamental mobile expectation. Hard cuts between screens feel jarring and non-native. These are the most frequently performed interactions in the app.

**Independent Test**: Tap an exhibition card — verify smooth transition. Swipe back — verify it works. Switch tabs — verify fade animation.

**Acceptance Scenarios**:

1. **Given** the user is on the detail screen, **When** they swipe from the left edge, **Then** the app navigates back to the previous tab.
2. **Given** the user is on the detail screen, **When** they press the system back button/gesture, **Then** the app navigates back to the previous tab.
3. **Given** the user taps an exhibition card, **When** the detail screen appears, **Then** there is a visible transition animation (fade or slide).
4. **Given** the user switches between tabs, **When** the new tab content appears, **Then** there is a subtle fade transition instead of a hard cut.

---

### User Story 3 - Exhibition Search (Priority: P2)

A user on the List tab sees a search bar above the filters. They type an exhibition name or venue name and the list instantly filters to matching results. Clearing the search restores the full list. The search works in combination with existing city and category filters.

**Why this priority**: With 70+ exhibitions, scrolling and filtering alone is slow for users who know what they're looking for. Search is the fastest path to a specific exhibition.

**Independent Test**: Type a partial exhibition name in the search bar. Verify matching results appear. Clear search. Apply a city filter then search within that city.

**Acceptance Scenarios**:

1. **Given** the user is on the List tab, **When** they see the top of the screen, **Then** a search bar is visible above the city/filter chips.
2. **Given** the user types "gallery" in the search bar, **When** the text is entered, **Then** only exhibitions with "gallery" in the name or venue name are shown.
3. **Given** the user has a city filter active, **When** they type in the search bar, **Then** results are filtered by both city and search text.
4. **Given** the user has search text entered, **When** they clear the search bar, **Then** the full (city/filter-scoped) list is restored.
5. **Given** the search matches no results, **When** the user views the screen, **Then** an appropriate "no results" message is shown.

---

### User Story 4 - Settings Menu Polish (Priority: P2)

A user opens the settings gear menu and sees the current theme selection clearly indicated with a checkmark or visual marker. The language toggle shows the current active language. The menu items have clear visual hierarchy and adequate spacing.

**Why this priority**: The settings menu currently cycles theme with no visual feedback about what's selected. Users can't tell which theme is active without reading the label text carefully.

**Independent Test**: Open settings menu. Verify current theme has a visible checkmark. Change theme. Verify checkmark moves to the new selection.

**Acceptance Scenarios**:

1. **Given** the settings menu is open, **When** the user views the theme option, **Then** the currently active theme (Light/Dark/System) has a visible selection indicator.
2. **Given** the user taps a theme option, **When** the theme changes, **Then** the selection indicator updates immediately.
3. **Given** the settings menu is open, **When** the user views language option, **Then** the current language is clearly shown.

---

### User Story 5 - Localized Date Formatting (Priority: P2)

A user viewing exhibitions sees dates formatted according to their language setting. Korean users see "2026.03.19 – 2026.05.10" format. English users see "Mar 19 – May 10, 2026" format. This applies to exhibition cards, detail screens, and map dialogs.

**Why this priority**: ISO date format (2024-12-01) is not natural for either Korean or English readers. Localized dates improve readability and feel native.

**Independent Test**: Switch language to English — dates show "Mon DD, YYYY" format. Switch to Korean — dates show "YYYY.MM.DD" format.

**Acceptance Scenarios**:

1. **Given** the language is set to Korean, **When** dates are displayed on cards, **Then** they appear as "YYYY.MM.DD" format.
2. **Given** the language is set to English, **When** dates are displayed on cards, **Then** they appear as "Mon DD – Mon DD, YYYY" format.
3. **Given** the user switches language, **When** dates are re-rendered, **Then** the format updates immediately.
4. **Given** dates appear on the detail screen, map dialog, and bottom sheet, **When** compared across all views, **Then** they use the same localized format.

---

### User Story 6 - Enhanced Empty States and Error Messages (Priority: P3)

A user encountering an empty list or error sees a helpful, specific message rather than a generic one. Error states differentiate between network problems and server issues. Empty states in the My List view and Map view have encouraging guidance text with clear visual hierarchy.

**Why this priority**: Good error/empty states build trust. Users currently see "Could not load exhibitions" with no context — they don't know if their internet is down or the server is broken.

**Independent Test**: Turn off network — verify error message mentions connectivity. View empty My List — verify encouraging guidance appears.

**Acceptance Scenarios**:

1. **Given** the network is unavailable, **When** data fails to load, **Then** the error message mentions a connection issue.
2. **Given** the server returns an error, **When** data fails to load, **Then** the error message mentions trying again later.
3. **Given** the user's My List is empty, **When** they view the My List tab, **Then** they see guidance on how to add exhibitions.
4. **Given** the map shows no pins, **When** the user views the map, **Then** a helpful message explains why.

---

### User Story 7 - Accessibility Improvements (Priority: P3)

A user relying on screen readers or keyboard navigation can use the app effectively. All interactive elements have spoken labels. The back button, settings icon, and bookmark icons announce their purpose. Color contrast meets minimum accessibility standards on all text.

**Why this priority**: Accessibility is a quality and compliance requirement. The current app has several unlabeled icons and potential contrast issues.

**Independent Test**: Enable screen reader (TalkBack/VoiceOver). Navigate through all screens. Verify all buttons and icons are announced meaningfully.

**Acceptance Scenarios**:

1. **Given** a screen reader is active, **When** the user focuses on the back button, **Then** it announces "Back" or "Go back" rather than "←".
2. **Given** a screen reader is active, **When** the user focuses on the settings icon, **Then** it announces "Settings" clearly.
3. **Given** a screen reader is active, **When** the user focuses on a bookmark button, **Then** it announces whether the item is bookmarked or not.
4. **Given** the app is in light or dark mode, **When** secondary text is displayed, **Then** it meets WCAG AA contrast ratio (4.5:1 for normal text).

---

### User Story 8 - Remove Redundant Language Toggle from Detail Screen (Priority: P3)

A user on the detail screen no longer sees a separate language toggle button, since language switching is available in the global settings menu. This simplifies the detail screen top bar and removes a source of confusion.

**Why this priority**: Having language toggle in two places (settings + detail screen) is confusing. Consolidating to settings is cleaner.

**Independent Test**: Open detail screen — verify no language toggle button is present. Open settings — verify language toggle still works.

**Acceptance Scenarios**:

1. **Given** the user opens an exhibition detail, **When** they view the top bar, **Then** only the back button and bookmark icon are present (no language toggle).
2. **Given** the user wants to change language, **When** they open the settings menu from any screen, **Then** they can toggle language there.

---

### Edge Cases

- What happens when pull-to-refresh is triggered while data is already loading? The refresh should be ignored (debounced).
- What happens when the search query contains special characters? The search should match literally without crashing.
- What happens when the user types very fast in the search bar? Results should be debounced to avoid excessive filtering.
- What happens when the user swipes back during a screen transition? The transition should cancel gracefully.
- What happens when the detail screen has no cover image? No placeholder should appear — layout should start from the exhibition name.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All three tab screens (Featured, List, Map) MUST support pull-to-refresh to reload data from the server.
- **FR-002**: Loading states MUST show skeleton placeholder cards (2-3 ghost cards with a pulse animation) instead of a minimal progress line.
- **FR-003**: Exhibition detail cover images MUST show a gray placeholder at the correct aspect ratio while loading.
- **FR-004**: The detail screen MUST support system back gesture (swipe-back on iOS, back gesture on Android) in addition to the back button.
- **FR-005**: Navigating to and from the detail screen MUST include a visible transition animation.
- **FR-006**: Switching tabs MUST include a subtle fade transition.
- **FR-007**: The List tab MUST include a search bar that filters exhibitions by name or venue name in real time.
- **FR-008**: Search MUST work in combination with existing city and category filters.
- **FR-009**: The settings dropdown MUST show a visible indicator (checkmark or similar) next to the currently selected theme.
- **FR-010**: Theme selection MUST show all three options (Light, Dark, System) simultaneously rather than cycling.
- **FR-011**: Dates MUST be formatted according to the user's language setting (Korean: YYYY.MM.DD, English: Mon DD, YYYY).
- **FR-012**: Date formatting MUST be consistent across cards, detail screen, map dialog, and bottom sheet.
- **FR-013**: Error messages MUST differentiate between network connectivity issues and server errors.
- **FR-014**: All interactive icons (back button, settings, bookmark) MUST have meaningful accessibility labels.
- **FR-015**: All text MUST meet WCAG AA contrast ratio (4.5:1 for normal text, 3:1 for large text).
- **FR-016**: The language toggle MUST be removed from the detail screen top bar (available only in settings).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Pull-to-refresh available on all 3 tab screens — 100% coverage.
- **SC-002**: Zero screens show empty blank space during loading — all show skeleton or placeholder content.
- **SC-003**: Back gesture works on 100% of screens that have a back navigation path.
- **SC-004**: Search returns relevant results within 300ms of user input (perceived instant).
- **SC-005**: 100% of interactive elements have accessibility labels (verified via screen reader).
- **SC-006**: 100% of text meets WCAG AA contrast ratios in both light and dark themes.
- **SC-007**: Date format matches the active language setting on all screens — zero inconsistencies.
- **SC-008**: Zero redundant language toggle buttons — consolidated to settings only.

## Assumptions

- The existing Compose Multiplatform framework supports pull-to-refresh, animated transitions, and back gesture handling without additional native platform code.
- Skeleton loading uses simple gray rectangles with a shimmer/pulse animation — no need for exact card shape matching.
- Search is client-side filtering (all data already loaded) — no server-side search endpoint needed.
- Date formatting uses the existing language preference (KO/EN) rather than device locale, keeping the two-language model consistent.
- The settings dropdown can expand to show three theme options as a submenu or radio group without needing a full settings screen.
- Cover image placeholder is a simple solid gray box at 16:9 aspect ratio — no blur-up or low-res preview needed.
