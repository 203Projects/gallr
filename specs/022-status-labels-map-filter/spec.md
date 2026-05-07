# Feature Specification: Status Labels & Map Pin Filtering

**Feature Branch**: `022-status-labels-map-filter`
**Created**: 2026-04-02
**Status**: Draft
**Input**: User description: "Status labels (Closing Soon/Upcoming) across all screens and filter ended exhibitions from map. Combines feature requests: 260402-feature-request-hours-contact-openings-p1 and 260329-feature-request-statuslabels-p1."

## Context & Background

This feature consolidates two related feature requests:

1. **260402-feature-request-hours-contact-openings-p1**: Gallery hours, contact info, and opening event labels. The data pipeline (Supabase schema, GAS sync script) and detail screen display are **already implemented**. No additional work needed for this request.

2. **260329-feature-request-statuslabels-p1**: Status labels ("Closing Soon" / "Upcoming") displayed consistently across all exhibition surfaces, plus filtering ended exhibitions from the map view. This is the **primary scope** of this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Closing Soon Badge on Exhibition Cards (Priority: P1)

A user browsing the Featured or List tabs sees an orange "Closing Soon" badge on exhibition cards when an exhibition's closing date is within 3 days. This gives users an immediate visual cue that they should visit soon or risk missing the exhibition entirely.

**Why this priority**: This is the highest-visibility surface in the app — every user sees exhibition cards when browsing. The urgency signal directly drives gallery visits, which is the app's core value proposition.

**Independent Test**: Can be fully tested by viewing the Featured or List tab with exhibitions that close within 1-3 days. The badge appears without navigating to any detail page.

**Acceptance Scenarios**:

1. **Given** an exhibition closes tomorrow, **When** a user views the exhibition card in the Featured or List tab, **Then** an orange "Closing Soon" (EN) / "종료 예정" (KO) badge is visible on the card.
2. **Given** an exhibition closes in exactly 3 days, **When** a user views the exhibition card, **Then** the "Closing Soon" badge is displayed.
3. **Given** an exhibition closes in 4 or more days, **When** a user views the exhibition card, **Then** no "Closing Soon" badge is displayed.
4. **Given** an exhibition has already closed (closing date is in the past), **When** a user views the exhibition card, **Then** no "Closing Soon" badge is displayed.
5. **Given** an exhibition has not yet opened (upcoming), **When** a user views the exhibition card, **Then** the existing "Upcoming" badge is shown instead of "Closing Soon" — the two labels are mutually exclusive.
6. **Given** the user switches language between Korean and English, **When** viewing a closing-soon exhibition, **Then** the badge text updates to match the selected language.

---

### User Story 2 - Status Labels on Map Pin Cards (Priority: P2)

A user tapping a map pin (or a cluster of pins at the same venue) sees status context — "Upcoming" or "Closing Soon" — directly in the pin's popup card or bottom sheet list. This helps users prioritize which gallery to visit when exploring by location.

**Why this priority**: The map is a key discovery surface. Users choosing between nearby galleries need urgency context without opening each exhibition's detail page individually.

**Independent Test**: Can be tested by tapping pins on the map — a single-pin popup or multi-pin bottom sheet should display the appropriate status label below the date range.

**Acceptance Scenarios**:

1. **Given** a user taps a single map pin for an exhibition closing within 3 days, **When** the pin popup appears, **Then** the status label "Closing Soon" / "종료 예정" is visible below the date range.
2. **Given** a user taps a single map pin for an upcoming exhibition (opening date in the future), **When** the pin popup appears, **Then** the status label "Upcoming" / "오픈 예정" is visible below the date range.
3. **Given** a user taps a pin cluster at a venue with multiple exhibitions, **When** the bottom sheet list appears, **Then** each exhibition item shows its individual status label (if applicable) below its date range.
4. **Given** an exhibition is currently running with more than 3 days until closing, **When** viewing its map pin card, **Then** no status label is shown (only the date range).

---

### User Story 3 - Status Label on Exhibition Detail Page (Priority: P3)

A user viewing an exhibition's full detail page sees a "Closing Soon" or "Upcoming" status indicator near the date range. This reinforces the urgency signal from the card or map and helps users plan their visit.

**Why this priority**: The detail page is visited after the user is already interested. The status label here serves as confirmation and reinforcement rather than initial discovery — still valuable but lower priority than the discovery surfaces.

**Independent Test**: Can be tested by navigating to the detail page of a closing-soon or upcoming exhibition and verifying the label appears below the date range.

**Acceptance Scenarios**:

1. **Given** a user navigates to the detail page of an exhibition closing within 3 days, **When** the page loads, **Then** a "Closing Soon" / "종료 예정" label appears below the date range in orange.
2. **Given** a user navigates to the detail page of an upcoming exhibition, **When** the page loads, **Then** an "Upcoming" / "오픈 예정" label appears below the date range in orange.
3. **Given** the exhibition also has an opening event (reception date), **When** both the reception label and the status label apply, **Then** both labels are visible without overlapping.

---

### User Story 4 - Hide Ended Exhibitions from Map (Priority: P1)

A user viewing the map (in either "All" or "My List" mode) does not see pins for exhibitions that have already ended. This prevents wasted trips and keeps the map focused on actionable information.

**Why this priority**: Showing ended exhibitions on the map is actively misleading — a user may travel to a gallery only to find the exhibition has closed. This is tied to P1 because it prevents a bad user experience.

**Independent Test**: Can be tested by having a mix of ended and active exhibitions in the database, then verifying the map only shows pins for exhibitions with a closing date of today or later.

**Acceptance Scenarios**:

1. **Given** exhibitions exist where the closing date is in the past, **When** a user views the "All" map tab, **Then** those ended exhibitions do not appear as pins on the map.
2. **Given** a user has bookmarked an exhibition that has since ended, **When** the user views the "My List" map tab, **Then** the ended exhibition does not appear as a pin.
3. **Given** an exhibition's closing date is today, **When** a user views the map, **Then** the pin is still visible (the exhibition is considered active on its closing date).
4. **Given** all exhibitions at a venue have ended, **When** viewing the map, **Then** no pin appears at that venue location.

---

### Edge Cases

- What happens when an exhibition's closing date is exactly today? It should still show as active with the "Closing Soon" badge (today is within 3 days).
- What happens when the user's device date/timezone differs from the exhibition's local timezone? The app uses the device's local date consistently for all comparisons (dates are stored as date-only without time components).
- What happens when an exhibition is both "upcoming" (opening date in future) and would qualify as "closing soon" (e.g., a 1-day exhibition opening in 2 days)? "Upcoming" takes priority since the exhibition hasn't started yet.
- What happens when the closing date equals the opening date (single-day exhibition)? If that day is within 3 days and the exhibition has opened, it shows "Closing Soon"; if it hasn't opened yet, it shows "Upcoming".
- What happens when a user has the map open and an exhibition ends (midnight passes)? The pin remains until the next data refresh or screen revisit — real-time removal is not required.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST display a "Closing Soon" / "종료 예정" badge on exhibition cards when the exhibition's closing date is today or within 3 calendar days and the exhibition has already opened.
- **FR-002**: The "Closing Soon" badge MUST NOT appear on exhibitions that have not yet opened (these show "Upcoming" instead).
- **FR-003**: The "Closing Soon" and "Upcoming" badges MUST be mutually exclusive — only one status badge appears per exhibition at a time.
- **FR-004**: The map pin popup (single-pin) MUST display the appropriate status label ("Closing Soon" or "Upcoming") below the date range when applicable.
- **FR-005**: The map bottom sheet (multi-pin venue) MUST display the appropriate status label for each exhibition in the list.
- **FR-006**: The exhibition detail page MUST display the appropriate status label below the date range when applicable.
- **FR-007**: The map view MUST NOT display pins for exhibitions whose closing date has passed (closing date < today).
- **FR-008**: The map pin filter (FR-007) MUST apply to both the "All" and "My List" map modes.
- **FR-009**: All status labels MUST be bilingual, displaying in the user's selected language (Korean or English).
- **FR-010**: Status labels MUST use the app's accent color (orange) consistently across all surfaces.
- **FR-011**: A shared status determination function MUST be used across all surfaces to ensure consistent behavior — the same exhibition must show the same status everywhere.

### Key Entities

- **Exhibition**: Existing entity. Relevant attributes: opening date, closing date. Status is derived (computed from dates and current date), not stored.
- **Exhibition Map Pin**: Lightweight projection of Exhibition for map display. Relevant attributes: opening date, closing date (used for filtering and status computation).

## Assumptions

- The "3 days" threshold for "Closing Soon" means: closing date minus today is 0, 1, 2, or 3 days (inclusive on both ends). This matches the feature request specification.
- "Upcoming" means the exhibition's opening date is strictly in the future (after today).
- Date comparisons use the device's local date (system clock), consistent with existing date logic in the app.
- The existing "Upcoming" label on ExhibitionCard already works correctly and only needs an additional branch for "Closing Soon".
- No new data fields are needed — status is computed entirely from existing opening_date and closing_date fields.
- The reception date / opening event label (from feature request 260402) is already implemented and is separate from the "Upcoming"/"Closing Soon" status labels.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of exhibitions closing within 3 days display the "Closing Soon" badge on all surfaces (cards, map pins, detail page) — zero inconsistencies across screens.
- **SC-002**: 0% of ended exhibitions appear as pins on the map in either "All" or "My List" mode.
- **SC-003**: Status labels display correctly in both Korean and English, switching instantly when the user changes language preference.
- **SC-004**: Users can identify time-sensitive exhibitions (closing soon or upcoming) at a glance without opening the detail page — reducing unnecessary navigation by providing status context on discovery surfaces.
- **SC-005**: The status badge logic is consistent: given the same exhibition and date, the same status appears on the card, map pin popup, and detail page every time.
