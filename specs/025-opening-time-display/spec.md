# Feature Specification: Opening Time Display

**Feature Branch**: `025-opening-time-display`  
**Created**: 2026-04-07  
**Status**: Draft  
**Input**: Feature request from Design (260406-feature-request-opening-time-p1.md)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View opening time on exhibition detail (Priority: P1)

As a user browsing exhibitions with upcoming openings, I want to see the opening time alongside the opening date label so I know when to arrive without looking it up elsewhere.

**Why this priority**: This is the core value of the feature. Users currently see "Opening today" but have no idea if it's at noon or 6 PM. Adding the time directly addresses the problem.

**Independent Test**: Can be fully tested by viewing any exhibition detail page where both an opening date and opening time are populated. The label should display the time appended to the existing date text.

**Acceptance Scenarios**:

1. **Given** an exhibition with opening date = today and opening time = "5 PM", **When** user views the exhibition detail page, **Then** the opening label reads "Opening today, 5 PM"
2. **Given** an exhibition with opening date = this Saturday and opening time = "6:30 PM", **When** user views the exhibition detail page, **Then** the opening label reads "Opening Saturday, 6:30 PM"
3. **Given** an exhibition with opening date = tomorrow and opening time = "3 PM", **When** user views the exhibition detail page, **Then** the opening label reads "Opening tomorrow, 3 PM"
4. **Given** an exhibition whose opening date has passed but is still running, with opening time = "5 PM", **When** user views the exhibition detail page, **Then** the opening label reads "Opening Apr 5, 5 PM" (with the actual date)

---

### User Story 2 - Graceful fallback when no opening time exists (Priority: P1)

As a user browsing exhibitions that do not have an opening time recorded, I want the opening label to display exactly as it does today so the experience remains consistent.

**Why this priority**: Equal to P1 because most existing exhibitions will not have opening times initially. The app must not break or show incomplete labels for existing data.

**Independent Test**: Can be tested by viewing any exhibition that has an opening date but no opening time populated. The label should match current behavior exactly.

**Acceptance Scenarios**:

1. **Given** an exhibition with opening date = today and no opening time, **When** user views the exhibition detail page, **Then** the opening label reads "Opening today" with no time component
2. **Given** an exhibition with opening date = this Saturday and no opening time, **When** user views the exhibition detail page, **Then** the opening label reads "Opening Saturday" with no time component
3. **Given** an exhibition with no opening date, **When** user views the exhibition detail page, **Then** no opening label is shown regardless of whether opening time has a value

---

### User Story 3 - Data entry for opening times (Priority: P2)

As a content manager entering exhibition data in the spreadsheet, I want an optional opening time column so I can record when openings start. The time should sync automatically to the app.

**Why this priority**: The data pipeline must exist before users can see opening times, but the user-facing label logic is the primary value driver.

**Independent Test**: Can be tested by entering a time value in the spreadsheet, triggering sync, and verifying the time appears in the data store and subsequently in the app.

**Acceptance Scenarios**:

1. **Given** a new exhibition row with opening time = "5 PM", **When** the data sync runs, **Then** the opening time is stored and available to the app
2. **Given** an existing exhibition row with opening time left blank, **When** the data sync runs, **Then** the exhibition's opening time remains empty and no errors occur
3. **Given** an exhibition row where opening time is later updated from blank to "7 PM", **When** the data sync runs, **Then** the updated time is reflected in the app

---

### Edge Cases

- What happens when opening time is provided but opening date is missing? No opening label is shown. Time alone is meaningless without a date.
- What happens when the opening time has an unexpected format (e.g., "late afternoon")? The raw string is displayed as-is after the comma separator. No validation or transformation is applied.
- What happens when the exhibition has ended? The opening label (including any time) is hidden entirely, matching current behavior.
- What happens when the opening is more than one week away? The opening label is hidden, matching current behavior. The time is not shown independently.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display the opening time appended to the existing opening date label, separated by a comma and space (e.g., "Opening today, 5 PM"), when both opening date and opening time are available
- **FR-002**: System MUST display the existing opening date label without modification when an opening time is not available
- **FR-003**: System MUST NOT display any opening label when no opening date exists, regardless of whether an opening time value is present
- **FR-004**: System MUST support an optional opening time field in the exhibition data, allowing content managers to leave it blank
- **FR-005**: System MUST sync the opening time from the data entry source to the app's data store without altering the entered value
- **FR-006**: System MUST display the opening time string exactly as entered by the content manager (no formatting or parsing applied)
- **FR-007**: System MUST hide the opening label (including time) when the exhibition has ended or when the opening is more than one week away, consistent with existing opening date visibility rules
- **FR-008**: System MUST use the same label styling (orange color, same position) for labels with and without opening times

### Key Entities

- **Exhibition**: Existing entity representing a gallery exhibition. Gains an optional **opening time** attribute (free-text string, e.g., "5 PM", "6:30 PM"). Related to existing opening date attribute.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of exhibitions with both opening date and opening time display the combined label correctly across all label states (today, tomorrow, this week, past)
- **SC-002**: 100% of exhibitions without an opening time display labels identically to current behavior (zero visual regressions)
- **SC-003**: Opening time data entered by content managers appears in the app within one sync cycle without manual intervention
- **SC-004**: Users can determine the opening time for an exhibition in under 2 seconds by reading the label on the detail page, without navigating elsewhere

## Assumptions

- All times are local (KST). No time zone conversion or display is needed.
- The opening time field uses free-text format (e.g., "5 PM", "6:30 PM") rather than a structured time format. This was recommended in the feature request for simplicity.
- The time is displayed on the exhibition detail page only. List/card views are not affected.
- The opening time field is additive. No existing opening date logic, label positioning, or styling changes are required.
- Both Android and iOS platforms share the same label display behavior. Web is out of scope.

## Scope Boundaries

### In Scope

- Adding an optional opening time data field to the exhibition data pipeline (entry, storage, sync)
- Appending the opening time to the existing opening date label on the exhibition detail page
- Handling all edge cases for missing or unexpected time values

### Out of Scope

- Changing existing opening date logic or label positioning
- Time zone handling or conversion
- Countdown timers or push notifications for upcoming openings
- Displaying opening time on list views, cards, or map markers
- Validating or normalizing the time format
- Web platform support
